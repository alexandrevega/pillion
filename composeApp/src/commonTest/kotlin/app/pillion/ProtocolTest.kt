package app.pillion

import app.pillion.protocol.Auth
import app.pillion.protocol.NaviLiteCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun crc_matches_captured_sec_data_ack() {
        // header(frameType=6, svc=0x54, pdt=1, size=4) + payload b0a04756 -> CRC field 24 f0 73 5b
        val frame = NaviLiteCodec.build(6, 0x54, 1, hex("b0a04756"))
        assertEquals("24f0735b", frame.copyOfRange(12, 16).toHex())
    }

    @Test
    fun echo_auth_reproduces_all_captured_vectors() {
        val cases = listOf(
            "3a3a3c27483e3b3c3a273a3a" + "baaa4d5c" to "b0a04756",
            "3a3a3c27483e3b3c3a273a3a" + "c12b8a7b" to "cb218071",
            "3a3a3c27483e3b3c3a273a3a" + "43719e55" to "497b945f",
        )
        for ((seedHex, expected) in cases) {
            val seed = hex(seedHex)
            assertEquals("006-B4160-00", Auth.partNumber(seed))
            assertEquals(expected, Auth.secDataAckPayload(seed).toHex())
        }
    }
}
