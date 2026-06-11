package app.pillion.protocol

/** NaviLite frameType for messages the phone sends to the bike (observed on the wire). */
internal const val FRAME_TYPE_PHONE: Int = 6

/** NaviLite payloadDataType values. */
internal const val PDT_VALUE: Int = 0
internal const val PDT_POINTER: Int = 1

/** NaviLite service-type identifiers (the message catalogue we use). */
internal object ServiceType {
    // handshake
    const val ESN_UPDATE = 66
    const val ESN_ACK = 81
    const val AUTH_REQUEST = 33
    const val AUTH_ACK = 82
    const val SEC_DATA = 83
    const val SEC_DATA_ACK = 84
    // image channel
    const val IMAGE = 0
    const val IMAGE_ACK = 80
    // post-auth setup burst
    const val NAV_STATUS = 2
    const val DAY_NIGHT = 31
    const val HOME = 10
    const val OFFICE = 11
    const val GPS = 13
    const val APP_SETTING = 12
    const val ZOOM = 14
    const val ROAD = 3
    const val SPEED_LIMIT = 17
}
