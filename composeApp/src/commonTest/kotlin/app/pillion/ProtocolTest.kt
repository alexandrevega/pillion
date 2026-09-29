package app.pillion

import app.pillion.core.DashSize
import app.pillion.core.NaviLiteDisplay
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

    @Test
    fun xmax_sec_data_identifies_the_scooter_ccu_and_its_480x234_dash() {
        // Real SEC_DATA captured from a Yamaha XMAX (GitHub issue #7): part "006-B3952-03" + nonce.
        val seed = hex("3a3a3c274839333f38273a39" + "2504211c")
        assertEquals("006-B3952-03", Auth.partNumber(seed))
        assertEquals("2f0e2b16", Auth.secDataAckPayload(seed).toHex())
        assertEquals(DashSize(480, 234), NaviLiteDisplay.forCcuPartNumber(Auth.partNumber(seed)))
    }

    @Test
    fun known_and_unknown_ccus_resolve_to_a_dash_size() {
        assertEquals(DashSize(480, 240), NaviLiteDisplay.forCcuPartNumber("006-B4160-00")) // MT-07
        assertEquals(DashSize(480, 234), NaviLiteDisplay.forCcuPartNumber("006-B3952-03")) // XMAX/NMAX
        assertEquals(NaviLiteDisplay.DEFAULT, NaviLiteDisplay.forCcuPartNumber("006-XXXXX-99")) // unknown
    }
}
