package app.pillion.core

import app.pillion.protocol.Auth
import app.pillion.protocol.FRAME_TYPE_PHONE
import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.PDT_POINTER
import app.pillion.protocol.PDT_VALUE
import app.pillion.protocol.ServiceType

/**
 * Runs the NaviLite connection handshake: ESN-ack -> AUTH_REQUEST -> echo-auth -> setup burst.
 * Single responsibility: the handshake sequence. Depends only on the channel/reader abstractions.
 */
class Handshake(
    private val channel: ByteChannel,
    private val reader: FrameReader,
) {
    fun perform() {
        Logger.d("handshake: waiting for ESN_UPDATE")
        awaitService(ServiceType.ESN_UPDATE)
        send(ServiceType.ESN_ACK, PDT_VALUE, byteArrayOf(1, 0))
        send(ServiceType.AUTH_REQUEST, PDT_POINTER, AUTH_REQUEST_PAYLOAD)
        Logger.d("handshake: waiting for SEC_DATA")
        val secData = awaitService(ServiceType.SEC_DATA)
        Logger.d("handshake: SEC_DATA part=${Auth.partNumber(secData.payload)}")
        send(ServiceType.SEC_DATA_ACK, PDT_POINTER, Auth.secDataAckPayload(secData.payload))
        for ((svc, pdt, payload) in SETUP_BURST) send(svc, pdt, payload)
        Logger.d("handshake: setup burst sent, authenticated")
    }

    private fun awaitService(serviceType: Int): NaviFrameView {
        var f = reader.next()
        while (f.serviceType != serviceType) f = reader.next()
        return f
    }

    private fun send(serviceType: Int, payloadDataType: Int, payload: ByteArray) {
        channel.write(NaviLiteCodec.build(FRAME_TYPE_PHONE, serviceType, payloadDataType, payload))
    }

    private companion object {
        // appVersion 1820 (0x071c) + extra
        val AUTH_REQUEST_PAYLOAD = byteArrayOf(0x1c, 0x07, 0, 1, 0, 0, 0, 0)

        // The post-auth setup the dash expects before it renders images.
        val SETUP_BURST: List<Triple<Int, Int, ByteArray>> = listOf(
            Triple(ServiceType.NAV_STATUS, PDT_VALUE, byteArrayOf(0, 0)),
            Triple(ServiceType.DAY_NIGHT, PDT_VALUE, byteArrayOf(1, 0)),
            Triple(ServiceType.HOME, PDT_VALUE, byteArrayOf(0, 0)),
            Triple(ServiceType.OFFICE, PDT_VALUE, byteArrayOf(0, 0)),
            Triple(ServiceType.GPS, PDT_VALUE, byteArrayOf(1, 0)),
            Triple(ServiceType.APP_SETTING, PDT_VALUE, byteArrayOf(0, 0)),
            Triple(ServiceType.ZOOM, PDT_POINTER, byteArrayOf(0x07, 0x19, 0x06, 0x00, 0x30, 0x2e, 0x32, 0x20, 0x6d, 0x69)),
            Triple(ServiceType.ROAD, PDT_POINTER, ByteArray(0)),
            Triple(ServiceType.SPEED_LIMIT, PDT_POINTER, byteArrayOf(0, 0, 0, 0, 0x03, 0x6d, 0x70, 0x68)),
            Triple(ServiceType.GPS, PDT_VALUE, byteArrayOf(1, 0)),
            Triple(ServiceType.APP_SETTING, PDT_VALUE, byteArrayOf(1, 0)),
        )
    }
}
