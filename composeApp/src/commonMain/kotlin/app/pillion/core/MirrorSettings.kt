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
    val maxFps: Int = 25,
    val dashResolution: DashResolution = DashResolution.DEFAULT,
    /**
     * Compatibility mode: send one frame at a time (classic stop-and-wait) instead of the faster
     * 2-frames-in-flight pipeline. Turn on if a dash stutters, freezes, or drops the connection.
     */
    val compatMode: Boolean = false,
)
