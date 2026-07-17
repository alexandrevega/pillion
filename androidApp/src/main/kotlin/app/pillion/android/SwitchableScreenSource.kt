package app.pillion.android

import app.pillion.core.ScreenSource

/**
 * A [ScreenSource] that the engine pulls from, switching between the phone **mirror** (while
 * unlocked) and the **dash** stream (while locked) so one Bluetooth session serves both. The dash
 * helper only encodes while [promote]d (see [DashStreamScreenSource]/`DashServer`), so the idle
 * source costs no extra battery.
 *
 * Single responsibility: choose which underlying source feeds the engine right now.
 */
class SwitchableScreenSource(
    private val mirror: ScreenSource,
    private val dash: DashStreamScreenSource,
) : ScreenSource {

    @Volatile private var useDash = false

    override fun start() {
        mirror.start()
        dash.start() // connects to the helper's loopback socket; stays idle until promoted
    }

    override fun latestFrame(): ByteArray? =
        if (useDash) dash.latestFrame() ?: mirror.latestFrame() else mirror.latestFrame()

    override fun stop() {
        runCatching { mirror.stop() }
        runCatching { dash.stop() }
    }

    /** Phone locked: promote the foreground app to the dash and stream it. */
    fun promote(component: String) {
        dash.promote(component)
        useDash = true
    }

    /** Phone unlocked: return to mirroring and move the app back to the phone. */
    fun demote() {
        useDash = false
        dash.demote()
    }

    /** End the session: stop mirroring and tell the (detached) helper to release the display + exit. */
    fun quit() {
        runCatching { mirror.stop() }
        runCatching { dash.quit() }
    }
}
