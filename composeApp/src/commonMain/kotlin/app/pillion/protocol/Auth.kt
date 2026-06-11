package app.pillion.protocol

/**
 * NaviLite authentication — universal, no per-bike key.
 *
 * The CCU's AUTH_REQUEST_SEC_DATA payload is `("<part-number>" + nonce) XOR 0x0a`.
 * Authenticating means de-obfuscating it and echoing the 4-byte nonce back in AUTH_REQUEST_SEC_DATA_ACK.
 *
 * Single responsibility: the auth transform. It performs no I/O.
 */
object Auth {
    private const val OBFUSCATION = 0x0a
    private const val NONCE_LEN = 4

    /** The SEC_DATA_ACK payload = the de-obfuscated 4-byte nonce (last 4 bytes XOR 0x0a). */
    fun secDataAckPayload(secDataPayload: ByteArray): ByteArray =
        ByteArray(NONCE_LEN) { i ->
            (secDataPayload[secDataPayload.size - NONCE_LEN + i].toInt() xor OBFUSCATION).toByte()
        }

    /** The CCU part number the bike announces (de-obfuscated string before the nonce). */
    fun partNumber(secDataPayload: ByteArray): String {
        val n = secDataPayload.size - NONCE_LEN
        val chars = CharArray(n) { i -> (secDataPayload[i].toInt() xor OBFUSCATION).toChar() }
        return chars.concatToString()
    }
}
