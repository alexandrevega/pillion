package app.pillion.core

/**
 * Off-screen display size used by the dedicated dash helper. Frames are still scaled to the
 * Yamaha dash's native 480x240 before they are sent over NaviLite.
 */
enum class DashResolution(
    val width: Int,
    val height: Int,
) {
    Native(480, 240),
    R640(640, 320),
    R720(720, 360),
    R800(800, 400),
    Balanced(960, 480),
    R1024(1024, 512),
    R1152(1152, 576),
    Wide(1280, 640),
    R1360(1360, 680),
    High(1440, 720),
    R1600(1600, 800),
    R1920(1920, 960);

    val label: String get() = "$width x $height"

    companion object {
        val DEFAULT = Balanced

        fun fromName(name: String?): DashResolution =
            values().firstOrNull { it.name == name } ?: DEFAULT
    }
}
