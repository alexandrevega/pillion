package app.pillion.android

import app.pillion.core.MirrorController
import app.pillion.core.MirrorSettings
import app.pillion.core.MirrorState
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin adapter exposing the running [CaptureService] to the Compose UI. The Activity owns the
 * Android framework plumbing (permissions, projection consent, service lifecycle) and injects it
 * here as callbacks, so the UI depends only on the [MirrorController] abstraction.
 */
class AndroidMirrorController(
    private val onStart: (MirrorSettings) -> Unit,
    private val onStop: () -> Unit,
) : MirrorController {
    override val state: StateFlow<MirrorState> = CaptureService.state
    override fun start(settings: MirrorSettings) = onStart(settings)
    override fun stop() = onStop()
}
