package app.pillion.nav

/**
 * Decoder for HERE "flexible polyline" geometry ([GeometryFormat.HERE_FLEXIBLE_POLYLINE]).
 * Returns the route's lat/lng points; elevation/3rd-dimension values are skipped.
 */
object HerePolyline {
    private const val ENCODING = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val DECODING = IntArray(128) { -1 }.also { t -> for (i in ENCODING.indices) t[ENCODING[i].code] = i }

    fun decode(encoded: String): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()
        val it = Cursor(encoded)
        val version = it.nextUnsigned()
        require(version == 1L) { "unsupported flexible polyline version $version" }
        val header = it.nextUnsigned()
        val precision = (header and 15L).toInt()
        val thirdDim = ((header shr 4) and 7L).toInt()
        val factor = pow10(precision)
        val out = ArrayList<LatLng>()
        var lat = 0L
        var lng = 0L
        while (it.hasNext()) {
            lat += zigzag(it.nextUnsigned())
            lng += zigzag(it.nextUnsigned())
            if (thirdDim != 0) it.nextUnsigned() // skip elevation / 3rd dimension
            out.add(LatLng(lat / factor, lng / factor))
        }
        return out
    }

    private fun zigzag(v: Long): Long = (v ushr 1) xor -(v and 1L)

    private fun pow10(n: Int): Double {
        var r = 1.0
        repeat(n) { r *= 10.0 }
        return r
    }

    private class Cursor(val s: String) {
        var i = 0
        fun hasNext() = i < s.length
        fun nextUnsigned(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val code = s[i++].code
                val v = if (code in 0..127) DECODING[code] else -1
                require(v >= 0) { "invalid char in flexible polyline" }
                result = result or ((v.toLong() and 0x1f) shl shift)
                if (v and 0x20 == 0) break
                shift += 5
            }
            return result
        }
    }
}
