package app.pillion.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [MirrorController] for a profile not available on the current platform yet (e.g. SDL on iOS while
 * the native side is being wired). Starting it surfaces a clear message instead of crashing.
 */
class UnsupportedController(private val reason: String) : MirrorController {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    override val state: StateFlow<MirrorState> = _state.asStateFlow()
    override fun start(settings: MirrorSettings) { _state.value = MirrorState.Error(reason) }
    override fun stop() { _state.value = MirrorState.Idle }
}
