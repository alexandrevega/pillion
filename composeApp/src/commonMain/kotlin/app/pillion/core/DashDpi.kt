package app.pillion.core
object DashDpi {
    const val MIN = 160
    const val MAX = 480
    const val STEP = 10
    const val DEFAULT = 240
    fun snap(value: Int): Int = (((value.coerceIn(MIN, MAX) + STEP / 2) / STEP) * STEP).coerceIn(MIN, MAX)
}
