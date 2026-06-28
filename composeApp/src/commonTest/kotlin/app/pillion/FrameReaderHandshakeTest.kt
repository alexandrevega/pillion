package app.pillion

import app.pillion.core.ByteChannel
import app.pillion.core.FrameReader
import app.pillion.core.Handshake
import app.pillion.protocol.Auth
import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.ServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Serves a fixed inbound byte stream and records everything written — no real transport needed. */
private class ScriptedChannel(private val inbound: ByteArray) : ByteChannel {
    private var pos = 0
    val written = mutableListOf<ByteArray>()
    override fun open() {}
    override fun close() {}
    override fun write(bytes: ByteArray) { written += bytes }
    override fun read(buffer: ByteArray): Int {
        if (pos >= inbound.size) return -1
        val n = minOf(buffer.size, inbound.size - pos)
        inbound.copyInto(buffer, 0, pos, pos + n)
        pos += n
        return n
    }
}

private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

class FrameReaderHandshakeTest {

    @Test
    fun frame_reader_resyncs_past_garbage_and_parses_consecutive_frames() {
        val f1 = NaviLiteCodec.build(6, ServiceType.ESN_UPDATE, 0, byteArrayOf())
        val f2 = NaviLiteCodec.build(6, ServiceType.SEC_DATA, 1, byteArrayOf(1, 2, 3, 4))
        // Lead with a stray byte the reader must skip to find the magic.
        val reader = FrameReader(ScriptedChannel(byteArrayOf(0x00) + f1 + f2))

        val a = reader.next()
        assertEquals(ServiceType.ESN_UPDATE, a.serviceType)
        assertEquals(0, a.payload.size)

        val b = reader.next()
        assertEquals(ServiceType.SEC_DATA, b.serviceType)
        assertEquals("01020304", b.payload.toHex())
    }

    @Test
    fun handshake_drives_full_auth_sequence_with_correct_sec_data_ack() {
        val seed = hex("3a3a3c27483e3b3c3a273a3a" + "baaa4d5c") // captured vector (see ProtocolTest)
        val inbound = NaviLiteCodec.build(6, ServiceType.ESN_UPDATE, 0, byteArrayOf()) +
            NaviLiteCodec.build(6, ServiceType.SEC_DATA, 1, seed)
        val channel = ScriptedChannel(inbound)

        Handshake(channel, FrameReader(channel)).perform()

        // ESN_ACK, AUTH_REQUEST, SEC_DATA_ACK, then the 11-frame setup burst.
        assertEquals(14, channel.written.size)
        assertEquals(ServiceType.ESN_ACK, NaviLiteCodec.serviceTypeAt(channel.written[0], 0))
        assertEquals(ServiceType.AUTH_REQUEST, NaviLiteCodec.serviceTypeAt(channel.written[1], 0))

        val secDataAck = channel.written[2]
        assertEquals(ServiceType.SEC_DATA_ACK, NaviLiteCodec.serviceTypeAt(secDataAck, 0))
        assertEquals(Auth.secDataAckPayload(seed).toHex(), NaviLiteCodec.payloadAt(secDataAck, 0).toHex())

        // Every written frame must carry a valid magic header.
        assertTrue(channel.written.all { NaviLiteCodec.hasMagicAt(it, 0) })
    }
}
