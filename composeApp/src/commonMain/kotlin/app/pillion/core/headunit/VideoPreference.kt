package app.pillion.core.headunit

/**
 * What video a profile would like. The ACTUAL dimensions are negotiated with the head unit at connect
 * time (SDL advertises a VideoStreamingCapability; NaviLite carries params in its handshake), so
 * resolution is deliberately NOT hardcoded here — the session resolves it into [NegotiatedVideo].
 */
sealed interface VideoPreference {
    data class JpegSlideshow(val quality: Int = 40) : VideoPreference
    data class H264(val maxBitrateBps: Int? = null) : VideoPreference
}

/** The resolution/bitrate/codec the head unit actually agreed to after negotiation. */
data class NegotiatedVideo(
    val width: Int,
    val height: Int,
    val bitrateBps: Int,
    val codec: String,
)
