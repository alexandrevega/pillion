package app.pillion.core

/** Full-frame VD size with exactly the CCU aspect ratio, before overlay margins. */
data class DashCaptureSize(val width: Int, val height: Int)

object DashCaptureGeometry {
    fun forOutput(preferredWidth: Int, outputWidth: Int, outputHeight: Int): DashCaptureSize {
        require(preferredWidth > 0 && outputWidth > 0 && outputHeight > 0)
        val divisor = gcd(outputWidth, outputHeight)
        val stepW = outputWidth / divisor
        val stepH = outputHeight / divisor
        val factor = maxOf(1, (preferredWidth + stepW / 2) / stepW)
        return DashCaptureSize(stepW * factor, stepH * factor)
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val next = x % y
            x = y
            y = next
        }
        return x
    }
}
