package app.pillion.android

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.pillion.core.DashSetup
import app.pillion.core.DashStage
import app.pillion.core.DashState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android [DashSetup]: the one-time pair/connect via the in-app ADB bootstrap ([PillionAdb]). Casting
 * itself is automatic and lives in [CaptureService] (mirror while unlocked, dash while locked), so
 * this only handles getting the shell connection ready. Single responsibility: the setup handshake.
 */
class AndroidDashSetup(
    context: Context,
    private val requestNotificationPermission: () -> Unit = {},
) : DashSetup {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DashState())
    override val state: StateFlow<DashState> = _state.asStateFlow()

    init {
        scope.launch {
            AdbPairingCoordinator.state.collect { state ->
                _state.value = DashState(state.stage, state.message)
            }
        }
    }

    override fun startPairingAssistant() {
        requestNotificationPermission()
        AdbPairingCoordinator.start(context)
    }

    override fun openWirelessDebuggingSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun pair(code: String) {
        AdbPairingCoordinator.pairWithDiscoveredEndpoint(context, code)
    }

    override fun pair(host: String, pairingPort: Int, code: String) {
        AdbPairingCoordinator.pairExplicit(context, AdbPairingEndpoint(host, pairingPort), code)
    }

    override fun connect() {
        AdbPairingCoordinator.connect(context)
    }
}
