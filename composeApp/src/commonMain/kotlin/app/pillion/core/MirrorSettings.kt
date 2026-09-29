package app.pillion.core

/**
 * User-tunable session settings. Lower values trade smoothness for battery, heat and bandwidth.
 *
 * @param quality JPEG quality of each frame (10–80).
 * @param maxFps  upper bound on frames sent per second; the engine paces to this.
 * @param dashResolution off-screen display size for dedicated dash mode; output is scaled to 480x240.
 */
data class MirrorSettings(
    val quality: Int = 40,
    val maxFps: Int = 15,
    val dashResolution: DashResolution = DashResolution.DEFAULT,
    /**
     * When set (flattened launcher component), the session runs **dash-only pinned**: it renders this
     * one app in landscape on the dedicated dash display and keeps it there regardless of what the
     * phone does — no mirroring, no MediaProjection. Null → normal mirror(/promote) behaviour.
     */
    val dashApp: String? = null,
)
