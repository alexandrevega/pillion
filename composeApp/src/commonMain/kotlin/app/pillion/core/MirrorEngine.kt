package app.pillion.core

import app.pillion.protocol.FRAME_TYPE_PHONE
import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.PDT_POINTER
import app.pillion.protocol.ServiceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates a mirroring session: connect -> handshake -> stream screen frames -> report state.
 * Depends only on [ByteChannel] and [ScreenSource] (DIP) — knows nothing about RFCOMM, EASession,
 * MediaProjection or ReplayKit, so the same engine drives both platforms.
 */
class MirrorEngine(
    private val channel: ByteChannel,
    private val screen: ScreenSource,
    private val maxFps: Int = 15,
    private val imageType: Int = 3, // NAVIGATION_EXPANDED
) {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val minIntervalMs: Long = if (maxFps in 1..59) 1000L / maxFps else 0L
    private var job: Job? = null
    @Volatile private var running = false
    private var seq = 1

    fun start(scope: CoroutineScope) {
        if (job != null) return
        running = true
        _state.value = MirrorState.Connecting
        job = scope.launch(Dispatchers.Default) {
            try {
                // Start capture FIRST: a MediaProjection token goes stale if the virtual display
                // isn't created promptly, so we must not defer it behind the Bluetooth handshake.
                Logger.d("session: starting screen capture")
                screen.start()
                Logger.d("session: connecting transport")
                channel.open()
                val reader = FrameReader(channel)
                Logger.d("session: handshake")
                Handshake(channel, reader).perform()
                Logger.d("session: streaming")
                streamLoop(reader)
            } catch (t: Throwable) {
                Logger.e("session failed", t)
                if (running) _state.value = MirrorState.Error(t.message ?: "connection lost")
            } finally {
                running = false
                runCatching { screen.stop() }
                runCatching { channel.close() }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { channel.close() } // unblocks the blocking reader
        job = null
        _state.value = MirrorState.Idle
    }

    private fun streamLoop(reader: FrameReader) {
        var frames = 0
        var acks = 0
        var waitedForFrame = false
        var windowStart = nowMs()
        var lastSend = 0L
        while (running) {
            val jpeg = screen.latestFrame()
            if (jpeg == null) {
                if (!waitedForFrame) { Logger.d("session: waiting for first screen frame"); waitedForFrame = true }
                sleepMs(15)
                continue
            }
            if (minIntervalMs > 0L) {
                val wait = minIntervalMs - (nowMs() - lastSend)
                if (wait > 0L) sleepMs(wait)
            }
            lastSend = nowMs()
            sendImage(jpeg)
            if (seq == 2) Logger.d("session: first image sent (${jpeg.size} bytes)")
            if (awaitAck(reader)) acks++
            frames++
            val elapsed = nowMs() - windowStart
            if (elapsed >= 1000) {
                Logger.d("session: ${frames} frames, ${acks} acks, ${jpeg.size / 1024} KB/frame")
                _state.value = MirrorState.Streaming(frames * 1000.0 / elapsed, jpeg.size / 1024)
                frames = 0
                acks = 0
                windowStart = nowMs()
            }
        }
    }

    private fun sendImage(jpeg: ByteArray) {
        val payload = ByteArray(3 + jpeg.size)
        payload[0] = imageType.toByte()
        payload[1] = (seq and 0xff).toByte()
        payload[2] = ((seq ushr 8) and 0xff).toByte()
        jpeg.copyInto(payload, 3)
        seq++
        channel.write(NaviLiteCodec.build(FRAME_TYPE_PHONE, ServiceType.IMAGE, PDT_POINTER, payload))
    }

    private fun awaitAck(reader: FrameReader): Boolean {
        var guard = 0
        while (running && guard++ < ACK_FRAME_GUARD) {
            if (reader.next().serviceType == ServiceType.IMAGE_ACK) return true
        }
        return false
    }

    private companion object {
        const val ACK_FRAME_GUARD = 100
    }
}
