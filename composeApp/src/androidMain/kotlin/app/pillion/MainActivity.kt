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
import app.pillion.android.AndroidInstalledApps
import app.pillion.android.AndroidMirrorController
import app.pillion.android.AndroidSettingsStore
import app.pillion.android.AdbPairingCoordinator
import app.pillion.android.CaptureService
import app.pillion.android.GitHubUpdateChecker
import app.pillion.android.SdlMirrorController
import app.pillion.android.sdl.ProjectionHolder
import app.pillion.android.sdl.SdlService
import app.pillion.core.AppInfo
import app.pillion.core.MirrorController
import app.pillion.core.MirrorSettings
import app.pillion.core.headunit.HeadUnitProfile
import app.pillion.core.headunit.registerBuiltInHeadUnits
import app.pillion.ui.App

/**
 * Owns the Android framework plumbing for a session: runtime permissions, the MediaProjection consent
 * dialog, and starting/stopping the right foreground service for the selected head unit — the NaviLite
 * [CaptureService] (Bluetooth) or the [SdlService] (USB). The Compose UI only sees [MirrorController].
 */
class MainActivity : ComponentActivity() {

    private var pendingSettings = MirrorSettings()
    private var pendingSdl = false // whether the in-flight projection grant is for the SDL/USB path
    private var pendingDashOnly = false // dash-only pinned start waiting on runtime permissions (no projection)
    private val settingsStore by lazy { AndroidSettingsStore(applicationContext) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            if (pendingSdl) {
                // SDL path: hand the capture grant to the SDL stack; the session connects on USB attach
                // (or now, if the bike is already plugged in) and mirrors once the dash activates us.
                ProjectionHolder.resultCode = result.resultCode
                ProjectionHolder.resultData = data
                ProjectionHolder.projection = null
                startSdlService()
            } else {
                CaptureService.resultCode = result.resultCode
                CaptureService.resultData = data
                val intent = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_QUALITY, pendingSettings.quality)
                    .putExtra(CaptureService.EXTRA_MAX_FPS, pendingSettings.maxFps)
                    .putExtra(CaptureService.EXTRA_DASH_ENABLED, settingsStore.dashEnabled())
                    .putExtra(CaptureService.EXTRA_DASH_WIDTH, pendingSettings.dashResolution.width)
                    .putExtra(CaptureService.EXTRA_DASH_HEIGHT, pendingSettings.dashResolution.height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            if (pendingDashOnly) startDashOnly(pendingSettings) else requestProjection()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) AdbPairingCoordinator.start(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerBuiltInHeadUnits()
        val updateChecker = GitHubUpdateChecker(AppInfo.REPO)
        val dashSetup = AndroidDashSetup(
            context = applicationContext,
            requestNotificationPermission = ::requestNotificationPermission,
        )
        setContent {
            App(::controllerFor, updateChecker, settingsStore, dashSetup, AndroidInstalledApps(applicationContext))
        }
    }

    /** Resolve the [MirrorController] for the selected head unit (DIP — the UI doesn't know which). */
    private fun controllerFor(profile: HeadUnitProfile): MirrorController =
        if (profile.requiresUsb) {
            SdlMirrorController(
                onStart = { settings -> pendingSdl = true; startMirroring(settings) },
                onStop = ::stopSdl,
            )
        } else {
            AndroidMirrorController(
                onStart = { settings -> pendingSdl = false; startMirroring(settings) },
                onStop = ::stopMirroring,
            )
        }

    private fun startMirroring(settings: MirrorSettings) {
        pendingSettings = settings
        // Dash-only pinned mode renders one app on the dedicated display and needs NO MediaProjection,
        // so skip the screen-capture consent entirely.
        pendingDashOnly = !pendingSdl && settings.dashApp != null
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else if (pendingDashOnly) {
            startDashOnly(settings)
        } else {
            requestProjection()
        }
    }

    private fun startDashOnly(settings: MirrorSettings) {
        pendingDashOnly = false
        val intent = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_QUALITY, settings.quality)
            .putExtra(CaptureService.EXTRA_MAX_FPS, settings.maxFps)
            .putExtra(CaptureService.EXTRA_DASH_ENABLED, true)
            .putExtra(CaptureService.EXTRA_DASH_WIDTH, settings.dashResolution.width)
            .putExtra(CaptureService.EXTRA_DASH_HEIGHT, settings.dashResolution.height)
            .putExtra(CaptureService.EXTRA_DASH_ONLY, true)
            .putExtra(CaptureService.EXTRA_DASH_APP, settings.dashApp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopMirroring() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }

    private fun startSdlService() {
        val intent = Intent(this, SdlService::class.java)
            .putExtra(SdlService.EXTRA_APP_ID, SdlService.APP_ID_GARMIN)
            .putExtra(SdlService.EXTRA_MIRROR, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopSdl() {
        // ACTION_STOP (not stopService) so the service latches "user stopped" and the receiver won't
        // auto-revive it while the USB cable is still connected.
        startService(Intent(this, SdlService::class.java).setAction(SdlService.ACTION_STOP))
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
