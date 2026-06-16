package app.pillion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.pillion.android.AndroidDashSetup
import app.pillion.android.AndroidMirrorController
import app.pillion.android.AndroidSettingsStore
import app.pillion.android.AdbPairingCoordinator
import app.pillion.android.CaptureService
import app.pillion.android.GitHubUpdateChecker
import app.pillion.core.AppInfo
import app.pillion.core.MirrorSettings
import app.pillion.ui.App

/**
 * Owns the Android framework plumbing for a mirroring session: runtime permissions, the
 * MediaProjection consent dialog, and starting/stopping the foreground [CaptureService].
 * The Compose UI only ever sees the [AndroidMirrorController] abstraction.
 */
class MainActivity : ComponentActivity() {

    private var pendingSettings = MirrorSettings()
    private val settingsStore by lazy { AndroidSettingsStore(applicationContext) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            CaptureService.resultCode = result.resultCode
            CaptureService.resultData = data
            val intent = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_QUALITY, pendingSettings.quality)
                .putExtra(CaptureService.EXTRA_MAX_FPS, pendingSettings.maxFps)
                // When dash mode is on, the session switches to the dash display while locked.
                .putExtra(CaptureService.EXTRA_DASH_ENABLED, settingsStore.dashEnabled())
                .putExtra(CaptureService.EXTRA_DASH_WIDTH, pendingSettings.dashResolution.width)
                .putExtra(CaptureService.EXTRA_DASH_HEIGHT, pendingSettings.dashResolution.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) requestProjection()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) AdbPairingCoordinator.start(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = AndroidMirrorController(onStart = ::startMirroring, onStop = ::stopMirroring)
        val updateChecker = GitHubUpdateChecker(AppInfo.REPO)
        val dashSetup = AndroidDashSetup(
            context = applicationContext,
            requestNotificationPermission = ::requestNotificationPermission,
        )
        setContent { App(controller, updateChecker, settingsStore, dashSetup) }
    }

    private fun startMirroring(settings: MirrorSettings) {
        pendingSettings = settings
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) requestProjection() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopMirroring() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
