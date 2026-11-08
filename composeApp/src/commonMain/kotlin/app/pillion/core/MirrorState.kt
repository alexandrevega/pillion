package app.pillion.core

/** The single source of truth the UI renders. */
sealed interface MirrorState {
    data object Idle : MirrorState
    data object Connecting : MirrorState
    data class Streaming(val fps: Double, val kbPerFrame: Int) : MirrorState

    /**
     * An out-of-process broadcaster (the iOS ReplayKit upload extension) is mirroring the whole
     * screen. The app knows it's active, not the live fps (that lives in the extension process).
     */
    data object Broadcasting : MirrorState
    data class Error(val message: String) : MirrorState
}

/** Owner of the Android NaviLite casting session. */
enum class CastMode { NONE, MIRROR, DASH }
