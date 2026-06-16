package app.pillion.core

import kotlinx.coroutines.flow.StateFlow

/** Lifecycle of the dedicated-dash setup and session. */
enum class DashStage {
    /** Not connected — needs pairing (first time) or a reconnect. */
    Idle,
    Pairing,
    Connecting,
    /** Shell bootstrap is ready; the dash can be cast. */
    Connected,
    /** The helper is running and streaming the foreground app to the dash. */
    Casting,
    Error,
}

data class DashState(val stage: DashStage = DashStage.Idle, val message: String? = null)

/**
 * Drives the "dedicated dash display" feature for the UI (onboarding + settings). The UI depends
 * only on this abstraction (DIP); Android provides the implementation backed by the in-app ADB
 * bootstrap, the privileged capture helper, and the foreground service. Platforms that can't offer
 * it (e.g. iOS) simply pass `null` and the feature is hidden.
 *
 * Single responsibility: expose the connect/cast lifecycle as state + intents — it knows nothing
 * about Compose, and the screens know nothing about ADB.
 */
interface DashSetup {
    val state: StateFlow<DashState>

    /** Start pairing service discovery and show the notification used to enter the pairing code. */
    fun startPairingAssistant()

    /** Open Android's Developer options screen so the user can enable Wireless debugging. */
    fun openWirelessDebuggingSettings()

    /** Pair using auto-discovered port, or parse a fallback "port code" submission. */
    fun pair(code: String)

    /** Pair once using the code + port from Wireless debugging → "Pair device with pairing code". */
    fun pair(host: String, pairingPort: Int, code: String)

    /** Connect to the phone's own adbd (mDNS auto-discovery, reusing the stored pairing key). */
    fun connect()
}
