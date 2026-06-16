package app.pillion.android

import android.util.Log
import app.pillion.core.ScreenSource
import app.pillion.server.DashServer
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A [ScreenSource] that reads JPEG frames from the [DashServer] helper over **loopback TCP**
 * (`127.0.0.1:[DashServer.PORT]`). Frames are `[4-byte length][bytes]`; the most recent one is kept
 * for the engine to pull at its own Bluetooth send rate.
 *
 * Loopback TCP, not a unix socket (SELinux blocks untrusted_app -> shell there) and not the ADB
 * exec stream (that needs Wi-Fi, which a moving bike doesn't have). Loopback is always up, so once
 * the helper is spawned (detached), frames flow with no network. Connection is retried so the app
 * can start before the helper and reconnect freely.
 */
class DashStreamScreenSource : ScreenSource {
    private val thread = Thread(::readLoop).apply { isDaemon = true }
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var latest: ByteArray? = null
    @Volatile private var desiredComponent: String? = null

    override fun start() {
        if (running) return
        running = true
        thread.start()
    }

    private fun readLoop() {
        while (running) {
            try {
                val s = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress("127.0.0.1", DashServer.PORT), CONNECT_TIMEOUT_MS)
                }
                socket = s
                Log.d(TAG, "dash stream: connected to 127.0.0.1:${DashServer.PORT}")
                syncDesiredState(s)
                val data = DataInputStream(s.getInputStream().buffered())
                while (running) {
                    val len = data.readInt()
                    if (len <= 0 || len > MAX_FRAME) throw IllegalStateException("bad frame length $len")
                    val frame = ByteArray(len)
                    data.readFully(frame)
                    latest = frame
                }
            } catch (t: Throwable) {
                if (running) Log.d(TAG, "dash stream: ${t.message}, retrying")
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
            if (running) Thread.sleep(RETRY_MS)
        }
    }

    override fun latestFrame(): ByteArray? = latest

    /** Tell the helper to move the foreground app onto the dash display and start encoding. */
    fun promote(component: String) {
        desiredComponent = component
        send("PROMOTE $component\n")
    }

    /** Tell the helper to move the app back to the phone and stop encoding. */
    fun demote() {
        desiredComponent = null
        latest = null
        send("DEMOTE\n")
    }

    private fun send(line: String) {
        val s = socket ?: run {
            Log.d(TAG, "dash stream: queued ${line.trim()} until helper connects")
            return
        }
        send(s, line)
    }

    private fun syncDesiredState(s: Socket) {
        val component = desiredComponent
        if (component == null) {
            send(s, "DEMOTE\n")
        } else {
            Log.d(TAG, "dash stream: syncing pending PROMOTE $component")
            send(s, "PROMOTE $component\n")
        }
    }

    private fun send(s: Socket, line: String) {
        synchronized(writeLock) {
            runCatching { s.getOutputStream().apply { write(line.toByteArray()); flush() } }
                .onFailure {
                    // The phone can silently tear down this loopback socket on screen-off, so a write
                    // at lock time (PROMOTE) fails. Close it to wake the read loop, which reconnects
                    // and replays desiredComponent via syncDesiredState — self-healing the command.
                    Log.d(TAG, "dash stream: send failed for ${line.trim()}: ${it.javaClass.simpleName}; reconnecting")
                    runCatching { s.close() }
                }
        }
    }

    private val writeLock = Any()

    override fun stop() {
        running = false
        runCatching { socket?.close() }
        desiredComponent = null
        latest = null
    }

    private companion object {
        const val TAG = "Pillion"
        const val MAX_FRAME = 4 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 2000
        const val RETRY_MS = 300L
    }
}
