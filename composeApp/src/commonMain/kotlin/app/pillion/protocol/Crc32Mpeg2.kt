package app.pillion.protocol

/**
 * CRC-32/MPEG-2: poly 0x04C11DB7, init 0xFFFFFFFF, no input/output reflection, xorout 0.
 * Single responsibility: compute the NaviLite frame checksum. Nothing else depends on framing here.
 */
internal object Crc32Mpeg2 {
    private val table: IntArray = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 24
            repeat(8) { c = if (c and (1 shl 31) != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            t[i] = c
        }
    }

    fun compute(data: ByteArray): Int {
        var c = -1 // 0xFFFFFFFF
        for (b in data) {
            c = (c shl 8) xor table[((c ushr 24) xor (b.toInt() and 0xff)) and 0xff]
        }
        return c
    }
}
