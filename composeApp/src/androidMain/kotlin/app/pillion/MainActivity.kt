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
import app.pillion.android.AndroidMirrorController
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) requestProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = AndroidMirrorController(onStart = ::startMirroring, onStop = ::stopMirroring)
        val updateChecker = GitHubUpdateChecker(AppInfo.REPO)
        setContent { App(controller, updateChecker) }
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
}
