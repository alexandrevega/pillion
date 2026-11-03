package app.pillion

import app.pillion.core.ByteChannel
import app.pillion.core.MirrorEngine
import app.pillion.core.MirrorState
import app.pillion.core.ScreenSource
import app.pillion.core.nowMs
import app.pillion.core.sleepMs
import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.ServiceType
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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



/** Feeds the normal handshake, then holds the connection open without receiving any image. */
private class LiveButSilentChannel : ByteChannel {
    private val inbound =
        NaviLiteCodec.build(6, ServiceType.ESN_UPDATE, 0, byteArrayOf()) +
        NaviLiteCodec.build(6, ServiceType.SEC_DATA, 1, ByteArray(16) { (it + 1).toByte() })
    private var offset = 0

    @Volatile var opened = false
    @Volatile var closed = false
    @Volatile var openCount = 0

    override fun open() {
        opened = true
        openCount++
    }

    override fun write(bytes: ByteArray) {}

    override fun read(buffer: ByteArray): Int {
        if (closed) return -1
        if (offset < inbound.size) {
            val size = minOf(buffer.size, inbound.size - offset)
            inbound.copyInto(buffer, 0, offset, offset + size)
            offset += size
            return size
        }
        while (!closed) sleepMs(10)
        return -1
    }

    override fun close() {
        closed = true
    }
}

private class SilentScreen : ScreenSource {
    @Volatile var polls = 0
    @Volatile var released = false

    override fun start() {}
    override fun latestFrame(): ByteArray? {
        polls++
        return null
    }
    override fun stop() { released = true }
}

private class DelayedOpenChannel : ByteChannel {
    @Volatile var enteringOpen = false
    @Volatile var allowOpenToFinish = false
    @Volatile var closeCount = 0
    @Volatile var readCount = 0

    override fun open() {
        enteringOpen = true
        while (!allowOpenToFinish) sleepMs(10)
    }
    override fun write(bytes: ByteArray) {}
    override fun read(buffer: ByteArray): Int {
        readCount++
        return -1
    }
    override fun close() { closeCount++ }
}

private fun awaitMirrorCondition(maxMs: Long = 5000, condition: () -> Boolean): Boolean {
    val deadline = nowMs() + maxMs
    while (!condition() && nowMs() < deadline) sleepMs(10)
    return condition()
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

    @Test
    fun missing_frames_without_projection_revocation_does_not_end_transport() {
        val sessionJob = Job()
        val channel = LiveButSilentChannel()
        val screen = SilentScreen()
        val engine = MirrorEngine(channel, screen)
        try {
            engine.start(CoroutineScope(sessionJob))
            assertTrue(awaitMirrorCondition { screen.polls > 2 }, "handshake did not reach frame polling")
            val before = screen.polls
            sleepMs(120) // more empty frames, but no Android projection onStop
            assertTrue(screen.polls > before, "screen was no longer being polled")
            assertFalse(channel.closed, "frame silence must not close an otherwise live projection")
            assertFalse(screen.released)
            assertFalse(engine.state.value is MirrorState.Error)

            // Simulate the terminal Android MediaProjection callback: the service
            // calls engine.stop() immediately and then stops itself.
            engine.stop()
            assertTrue(awaitMirrorCondition { screen.released }, "capture source was not released")
            assertTrue(channel.closed, "RFCOMM was not closed")
            val opensAtStop = channel.openCount
            sleepMs(1700) // longer than the engine's reconnect delay
            assertEquals(opensAtStop, channel.openCount, "revoked projection must not reconnect")
        } finally {
            engine.stop()
            sessionJob.cancel()
        }
    }

    @Test
    fun stop_during_pending_connect_never_starts_handshake_after_connect_returns() {
        val sessionJob = Job()
        val channel = DelayedOpenChannel()
        val screen = SilentScreen()
        val engine = MirrorEngine(channel, screen)
        try {
            engine.start(CoroutineScope(sessionJob))
            assertTrue(awaitMirrorCondition { channel.enteringOpen }, "connect never started")
            engine.stop()
            channel.allowOpenToFinish = true
            assertTrue(awaitMirrorCondition { screen.released }, "source was not released")
            assertEquals(0, channel.readCount, "handshake ran after the projection was revoked")
            assertTrue(channel.closeCount > 0)
        } finally {
            channel.allowOpenToFinish = true
            engine.stop()
            sessionJob.cancel()
        }
    }
}
