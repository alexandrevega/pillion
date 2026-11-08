package app.pillion.core
data class DashMargins(val left: Int = 50, val bottom: Int = 46) {
    companion object {
        const val MAX_LEFT = 70
        const val MAX_BOTTOM = 70
        fun clamped(left: Int, bottom: Int) = DashMargins(left.coerceIn(0, MAX_LEFT), bottom.coerceIn(0, MAX_BOTTOM))
    }
}
enum class DashMarginColor(val label: String, val argb: Int) {
    Black("Black", 0xFF000000.toInt()),
    White("White", 0xFFFFFFFF.toInt()),
    YamahaBlue("Yamaha Racing Blue", 0xFF0A2D82.toInt());
    companion object { fun fromName(name: String?) = values().firstOrNull { it.name == name } ?: Black }
}
