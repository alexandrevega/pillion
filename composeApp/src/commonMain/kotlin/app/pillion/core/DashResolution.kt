package app.pillion.core
/** VD scale relative to available final JPEG image area; bootstrap width remains based on 480px. */
enum class DashResolution(val tenths: Int) {
    Scale1(10), Scale12(12), Scale14(14), Scale16(16), Scale18(18),
    Balanced(20), Scale22(22), Scale24(24), Scale26(26), Scale28(28), Scale3(30);
    val width: Int get() = 48 * tenths
    val height: Int get() = 24 * tenths
    val label: String get() = "${tenths / 10}.${tenths % 10}x"
    companion object {
        val DEFAULT = Balanced
        fun fromName(name: String?): DashResolution = values().firstOrNull { it.name == name } ?: when (name) {
            "Native" -> Scale1
            "R640" -> Scale14
            "R720", "R800" -> Scale16
            "R1024" -> Scale22
            "R1152" -> Scale24
            "Wide" -> Scale26
            "R1360" -> Scale28
            "High", "R1600", "R1920" -> Scale3
            else -> DEFAULT
        }
    }
}
