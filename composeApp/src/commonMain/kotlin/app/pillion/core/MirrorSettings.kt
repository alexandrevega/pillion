package app.pillion.core

/**
 * User-tunable session settings. Lower values trade smoothness for battery, heat and bandwidth.
 *
 * @param quality JPEG quality of each frame (10–80).
 * @param maxFps  upper bound on frames sent per second; the engine paces to this.
 * @param dashResolution off-screen display size for dedicated dash mode; output is scaled to 480x240.
 * @param tcpHost when set, the SDL session uses TCP transport to this host (the bike's Wi-Fi IP)
 *                instead of USB AOA — wireless streaming. Null = USB / wait-for-attach (no scan).
 *                The literal "auto" opts into an explicit subnet scan.
 * @param tcpPort TCP port for [tcpHost]; SDL's default is 12345.
 */
data class MirrorSettings(
    val quality: Int = 40,
    val maxFps: Int = 15,
    val dashResolution: DashResolution = DashResolution.DEFAULT,
    val tcpHost: String? = null,
    val tcpPort: Int = 12345,
)
