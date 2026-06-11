package app.pillion.protocol

/**
 * Encodes/decodes NaviLite frames.
 *
 * Frame layout: magic "nAl@" | version(1) | frameType(1) | serviceType(1) |
 *               payloadSize(4 LE) | payloadDataType(1) | CRC(4 LE) | payload[payloadSize]
 *
 * Single responsibility: byte<->frame translation. It does not own a transport or any I/O.
 */
object NaviLiteCodec {
    const val HEADER_SIZE = 12
    const val CRC_SIZE = 4
    val MAGIC = byteArrayOf(0x6e, 0x41, 0x6c, 0x40) // "nAl@"

    /** Build a full on-wire frame. */
    fun build(frameType: Int, serviceType: Int, payloadDataType: Int, payload: ByteArray): ByteArray {
        val size = payload.size
        val header = byteArrayOf(
            MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3],
            1, frameType.toByte(), serviceType.toByte(),
            (size and 0xff).toByte(),
            ((size ushr 8) and 0xff).toByte(),
            ((size ushr 16) and 0xff).toByte(),
            ((size ushr 24) and 0xff).toByte(),
            payloadDataType.toByte()
        )
        val crc = Crc32Mpeg2.compute(header + payload)
        val out = ByteArray(HEADER_SIZE + CRC_SIZE + size)
        header.copyInto(out, 0)
        out[12] = (crc and 0xff).toByte()
        out[13] = ((crc ushr 8) and 0xff).toByte()
        out[14] = ((crc ushr 16) and 0xff).toByte()
        out[15] = ((crc ushr 24) and 0xff).toByte()
        payload.copyInto(out, 16)
        return out
    }

    /** The total wire length of a frame whose header begins at [data]\[off], or -1 if the header isn't complete yet. */
    fun frameLengthAt(data: ByteArray, off: Int): Int {
        if (off + HEADER_SIZE + CRC_SIZE > data.size) return -1
        val size = (data[off + 7].toInt() and 0xff) or
            ((data[off + 8].toInt() and 0xff) shl 8) or
            ((data[off + 9].toInt() and 0xff) shl 16) or
            ((data[off + 10].toInt() and 0xff) shl 24)
        return HEADER_SIZE + CRC_SIZE + size
    }

    fun serviceTypeAt(data: ByteArray, off: Int): Int = data[off + 6].toInt() and 0xff

    fun payloadAt(data: ByteArray, off: Int): ByteArray {
        val size = (data[off + 7].toInt() and 0xff) or
            ((data[off + 8].toInt() and 0xff) shl 8) or
            ((data[off + 9].toInt() and 0xff) shl 16) or
            ((data[off + 10].toInt() and 0xff) shl 24)
        return data.copyOfRange(off + 16, off + 16 + size)
    }

    fun hasMagicAt(data: ByteArray, off: Int): Boolean =
        off + 4 <= data.size &&
            data[off] == MAGIC[0] && data[off + 1] == MAGIC[1] &&
            data[off + 2] == MAGIC[2] && data[off + 3] == MAGIC[3]
}
