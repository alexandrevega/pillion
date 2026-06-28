package app.pillion.ios

import app.pillion.core.MirrorController
import app.pillion.core.MirrorSettings
import app.pillion.core.MirrorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS SDL (USB / iAP2) controller. Symmetric to [BroadcastMirrorController]: the Kotlin side is a thin
 * state holder; the Swift `SdlSession` owns the real `SDLManager` + CarWindow video streaming and pushes
 * lifecycle changes back here through the setters. [start]/[stop] just toggle the native session, so the
 * shared Compose UI drives SDL on iOS exactly the way it drives NaviLite — it never knows the difference.
 */
class SdlBroadcastController : MirrorController {
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    override val state: StateFlow<MirrorState> = _state.asStateFlow()

    override fun start(settings: MirrorSettings) { onStart?.invoke() }
    override fun stop() { onStop?.invoke() }

    // Called from Swift as the SDL lifecycle advances. (Broadcasting, not Streaming(fps,kb): SDL owns the
    // encoder/timing, so the app knows it's projecting but not the live frame stats.)
    fun setIdle() { _state.value = MirrorState.Idle }
    fun setConnecting() { _state.value = MirrorState.Connecting }
    fun setStreaming() { _state.value = MirrorState.Broadcasting }
    fun setError(message: String) { _state.value = MirrorState.Error(message) }
}
