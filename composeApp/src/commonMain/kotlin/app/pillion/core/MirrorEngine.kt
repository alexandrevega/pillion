package app.pillion.core

import app.pillion.protocol.FRAME_TYPE_PHONE
import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.PDT_POINTER
import app.pillion.protocol.ServiceType
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

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
    // Frames allowed in flight before the panel ACKs. >1 overlaps the BT ACK round-trip across
    // frames (sliding window) instead of idling the link; 1 reproduces classic stop-and-wait.
    private val maxInFlight: Int = 2,
) {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val minIntervalMs: Long = if (maxFps in 1..59) 1000L / maxFps else 0L
    private val window: Int = maxInFlight.coerceAtLeast(1)
    private var job: Job? = null
    @Volatile private var running = false
    @Volatile private var lastFrameKb = 0
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
                Logger.d("session: streaming (window=$window)")
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

    private suspend fun streamLoop(reader: FrameReader) {
        // CONFLATED: producer always overwrites with the latest frame; consumer never stalls
        // waiting for capture/encode while the ACK round-trip is in flight.
        val frameChannel = Channel<ByteArray>(Channel.CONFLATED)
        // Sliding window: up to [window] frames may be sent before their ACKs return, so the BT
        // round-trip of frame N overlaps the transmission of N+1. The semaphore meters outstanding
        // frames; [sentTimes] is a FIFO of send instants so the ACK reader (which sees ACKs in
        // order) can attribute each one back to its frame for latency stats.
        val inFlight = Semaphore(window)
        val sentTimes = Channel<Long>(Channel.UNLIMITED)
        coroutineScope {
            // Producer: captures and encodes frames independently of ACK latency.
            launch {
                try {
                    var lastCapture = 0L
                    var waitedForFrame = false
                    while (running) {
                        val jpeg = screen.latestFrame()
                        if (jpeg == null) {
                            if (!waitedForFrame) {
                                Logger.d("session: waiting for first screen frame")
                                waitedForFrame = true
                            }
                            sleepMs(15)
                            continue
                        }
                        if (minIntervalMs > 0L) {
                            val wait = minIntervalMs - (nowMs() - lastCapture)
                            if (wait > 0L) sleepMs(wait)
                        }
                        lastCapture = nowMs()
                        frameChannel.trySend(jpeg)
                    }
                } finally {
                    frameChannel.close()
                }
            }
            // Sender: pushes the freshest frame whenever the window has a free slot.
            launch {
                for (jpeg in frameChannel) {
                    // Block until a slot frees; self-heal if an ACK was lost so we never deadlock.
                    val acquired = withTimeoutOrNull(ACQUIRE_TIMEOUT_MS) { inFlight.acquire(); true } ?: false
                    if (!acquired) Logger.d("session: window stalled (lost ack?) — sending anyway")
                    sendImage(jpeg)
                    sentTimes.trySend(nowMs())
                    lastFrameKb = jpeg.size / 1024
                    if (seq == 2) Logger.d("session: first image sent (${jpeg.size} bytes)")
                }
            }
            // ACK reader: the single drain point. Each in-order IMAGE_ACK frees a window slot and
            // closes out the oldest outstanding frame, then we roll up throughput/latency stats.
            launch {
                var acks = 0
                var windowStart = nowMs()
                var ackMsTotal = 0L
                var ackMsMax = 0L
                while (running) {
                    if (reader.next().serviceType != ServiceType.IMAGE_ACK) continue
                    // Guard the release so a duplicate/late ACK can't push past the window size.
                    if (inFlight.availablePermits < window) inFlight.release()
                    sentTimes.tryReceive().getOrNull()?.let { t0 ->
                        val ackMs = nowMs() - t0
                        ackMsTotal += ackMs
                        if (ackMs > ackMsMax) ackMsMax = ackMs
                    }
                    acks++
                    val elapsed = nowMs() - windowStart
                    if (elapsed >= 1000) {
                        val avgAckMs = if (acks > 0) ackMsTotal / acks else 0L
                        Logger.d(
                            "session: $acks fps, window=$window, $lastFrameKb KB/frame, " +
                                "ack ${avgAckMs}ms avg/${ackMsMax}ms max",
                        )
                        _state.value = MirrorState.Streaming(acks * 1000.0 / elapsed, lastFrameKb)
                        acks = 0
                        ackMsTotal = 0L
                        ackMsMax = 0L
                        windowStart = nowMs()
                    }
                }
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

    private companion object {
        // If no ACK frees a slot within this long, assume one was dropped and send anyway so a lost
        // ACK can't permanently stall the window. Generous vs the ~90ms healthy round-trip.
        const val ACQUIRE_TIMEOUT_MS = 1_000L
    }
}
