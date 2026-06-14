package app.pillion.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import app.pillion.core.DashSetup
import app.pillion.core.DashStage
import app.pillion.core.DashState
import app.pillion.core.MirrorSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android [DashSetup]: pairs/connects via the in-app ADB bootstrap ([PillionAdb]) and casts the
 * foreground app through the [CaptureService] dash path. Single responsibility: translate the UI's
 * connect/cast intents into ADB + service calls and report progress as [DashState].
 */
class AndroidDashSetup(private val context: Context) : DashSetup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DashState())
    override val state: StateFlow<DashState> = _state.asStateFlow()

    override fun pair(host: String, pairingPort: Int, code: String) {
        _state.value = DashState(DashStage.Pairing)
        scope.launch {
            runCatching { PillionAdb.getInstance(context).pairDevice(host, pairingPort, code) }
                .onSuccess { connect() }
                .onFailure { _state.value = DashState(DashStage.Error, it.message ?: "pairing failed") }
        }
    }

    override fun connect() {
        _state.value = DashState(DashStage.Connecting)
        scope.launch {
            runCatching { PillionAdb.getInstance(context).autoConnectDevice(context) }
                .onSuccess { ok ->
                    _state.value =
                        if (ok) DashState(DashStage.Connected)
                        else DashState(DashStage.Error, "Couldn't find the device — is Wireless debugging on?")
                }
                .onFailure { _state.value = DashState(DashStage.Error, it.message ?: "connect failed") }
        }
    }

    override fun startCast(settings: MirrorSettings) {
        val component = foregroundComponent()
        if (component == null) {
            _state.value = DashState(DashStage.Error, "Open the app you want on the dash first")
            return
        }
        val intent = Intent(context, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_QUALITY, settings.quality)
            .putExtra(CaptureService.EXTRA_MAX_FPS, settings.maxFps)
            .putExtra(CaptureService.EXTRA_DASH_COMPONENT, component)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        _state.value = DashState(DashStage.Casting)
    }

    override fun stopCast() {
        context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        _state.value = DashState(DashStage.Connected)
    }

    private fun foregroundComponent(): String? {
        val pkg = foregroundPackage() ?: return null
        return context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
    }

    /** Most recent foreground package other than Pillion (the app to promote at screen-block). */
    private fun foregroundPackage(): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 60_000, now) }.getOrNull() ?: return null
        val event = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName != context.packageName) {
                pkg = event.packageName
            }
        }
        return pkg
    }
}
