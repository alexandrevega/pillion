package app.pillion

import app.pillion.core.ByteChannel
import app.pillion.core.MirrorEngine
import app.pillion.core.MirrorState
import app.pillion.core.ScreenSource
import app.pillion.core.nowMs
import app.pillion.core.sleepMs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

private class FailingChannel : ByteChannel {
    override fun open() = throw RuntimeException("bike off")
    override fun write(bytes: ByteArray) {}
    override fun read(buffer: ByteArray): Int = -1
    override fun close() {}
}

private class NoScreen : ScreenSource {
    override fun start() {}
    override fun latestFrame(): ByteArray? = null
    override fun stop() {}
}

class MirrorEngineTest {
    @Test
    fun initial_connect_failure_ends_in_error_instead_of_reconnecting_forever() {
        val job = Job()
        val engine = MirrorEngine(FailingChannel(), NoScreen())
        engine.start(CoroutineScope(job))
        val deadline = nowMs() + 5000
        while (engine.state.value !is MirrorState.Error && nowMs() < deadline) sleepMs(20)
        job.cancel()
        assertTrue(engine.state.value is MirrorState.Error, "state was ${engine.state.value}")
    }
}
