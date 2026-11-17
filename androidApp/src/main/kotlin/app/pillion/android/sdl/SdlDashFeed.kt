package app.pillion.android.sdl

import app.pillion.android.DashStreamScreenSource
import app.pillion.android.DashSwitch

/**
 * Dedicated-dash frames for the SDL video while the phone is locked. The SDL encoder renders
 * [ScreenMirrorDisplay], which shows these frames over the (dark) phone mirror whenever [active].
 * Reached through [current] because SDL instantiates the remote display itself, by class.
 */
class SdlDashFeed(
    private val dash: DashStreamScreenSource,
    private val onDemoted: () -> Unit,
) : DashSwitch {

    @Volatile var active = false
        private set

    fun start(width: Int, height: Int) {
        dash.start()
        dash.resizeOutput(width, height)
    }

    /** Newest JPEG from the helper, or null while it is idle or stalled. */
    fun latestFrame(): ByteArray? = dash.latestFrame()

    override fun promote(component: String) {
        dash.promote(component)
        active = true
    }

    override fun demote() {
        active = false
        dash.demote()
        onDemoted()
    }

    /** End of session: release the helper's trusted display and stop reading. */
    fun quit() {
        active = false
        runCatching { dash.quit() }
    }

    companion object {
        @Volatile var current: SdlDashFeed? = null
    }
}
