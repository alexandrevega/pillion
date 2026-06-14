package app.pillion.android

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import app.pillion.core.ScreenSource
import app.pillion.server.DashServer
import java.io.DataInputStream

/**
 * A [ScreenSource] that reads JPEG frames from the [DashServer] helper over an abstract local
 * socket ([DashServer.SOCKET_NAME]). The helper (shell uid) creates the trusted display, captures
 * it, and pushes `[4-byte length][bytes]` frames; this keeps the most recent one for the engine to
 * pull at its own Bluetooth send rate.
 *
 * Connection is retried in a loop so the app can start the source before — or across reconnects of —
 * the helper, and it never touches the network, so it survives Wi-Fi dropping mid-ride.
 */
class DashSocketScreenSource : ScreenSource {
    private val thread = Thread(::readLoop).apply { isDaemon = true }
    @Volatile private var running = false
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var latest: ByteArray? = null

    override fun start() {
        if (running) return
        running = true
        thread.start()
    }

    private fun readLoop() {
        while (running) {
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(DashServer.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                socket = s
                Log.d(TAG, "dash socket: connected")
                val input = DataInputStream(s.inputStream.buffered())
                while (running) {
                    val len = input.readInt()
                    if (len <= 0 || len > MAX_FRAME) throw IllegalStateException("bad frame length $len")
                    val frame = ByteArray(len)
                    input.readFully(frame)
                    latest = frame
                }
            } catch (t: Throwable) {
                if (running) Log.d(TAG, "dash socket: disconnected (${t.message}), retrying")
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
            if (running) Thread.sleep(RETRY_MS)
        }
    }

    override fun latestFrame(): ByteArray? = latest

    override fun stop() {
        running = false
        runCatching { socket?.close() }
        latest = null
    }

    private companion object {
        const val TAG = "Pillion"
        const val MAX_FRAME = 4 * 1024 * 1024
        const val RETRY_MS = 200L
    }
}
