package app.pillion.android.sdl

import app.pillion.core.MirrorState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bridges the SDL session (which lives in [SdlService], driven partly by the USB auto-attach) to the
 * UI-facing [MirrorState]. The SDL service is started by the framework on USB connect, so its state
 * can't be returned synchronously from a controller call — it publishes here instead, and
 * `SdlMirrorController` exposes this flow to the Compose UI.
 */
object SdlSessionState {
    val state = MutableStateFlow<MirrorState>(MirrorState.Idle)
}
