package app.pillion.android

/** Something that can move the foreground app onto the dedicated dash display and back. */
interface DashSwitch {
    /** Phone locked: promote [component] to the dash display and stream it. */
    fun promote(component: String)

    /** Phone unlocked: move the app back to the phone and stop streaming the dash display. */
    fun demote()
}
