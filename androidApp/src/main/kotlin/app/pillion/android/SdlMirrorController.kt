package app.pillion.android

import app.pillion.android.sdl.SdlSessionState
import app.pillion.core.MirrorController
import app.pillion.core.MirrorSettings
import app.pillion.core.MirrorState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exposes the SDL "Path B" session to the Compose UI. The Activity owns the framework plumbing
 * (MediaProjection consent + starting the SDL foreground service); state comes from the service via
 * [SdlSessionState], so the UI depends only on the [MirrorController] abstraction (LSP — it drops in
 * wherever the NaviLite controller does).
 */
class SdlMirrorController(
    private val onStart: (MirrorSettings) -> Unit,
    private val onStop: () -> Unit,
) : MirrorController {
    override val state: StateFlow<MirrorState> = SdlSessionState.state.asStateFlow()
    override fun start(settings: MirrorSettings) = onStart(settings)
    override fun stop() = onStop()
}
