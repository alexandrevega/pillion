package app.pillion.ios

import app.pillion.core.ByteChannel
import app.pillion.core.MirrorController
import app.pillion.core.MirrorEngine
import app.pillion.core.MirrorSettings
import app.pillion.core.MirrorState
import app.pillion.core.ScreenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Drives a mirroring session on iOS. The transport ([ByteChannel] over External Accessory) and the
 * [ScreenSource] (ReplayKit) are implemented in Swift and injected here — the shared [MirrorEngine]
 * is identical to Android's.
 */
class IosMirrorController(
    channel: ByteChannel,
    screen: ScreenSource,
) : MirrorController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = MirrorEngine(channel, screen)

    override val state: StateFlow<MirrorState> = engine.state
    override fun start(settings: MirrorSettings) = engine.start(scope)
    override fun stop() = engine.stop()
}
