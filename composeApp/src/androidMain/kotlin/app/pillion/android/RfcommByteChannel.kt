package app.pillion.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import app.pillion.core.ByteChannel
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * A [ByteChannel] over a Bluetooth RFCOMM (SPP) link to the bonded dash.
 * Single responsibility: move bytes; it owns no protocol knowledge.
 */
class RfcommByteChannel : ByteChannel {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested by the Activity before start
    override fun open() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("no Bluetooth adapter")
        val dash = adapter.bondedDevices.firstOrNull { d ->
            val n = d.name
            n != null && (n.startsWith("YCCU") || n.contains("CCU"))
        } ?: error("dash (YCCU…) is not paired")
        Log.d("Pillion", "rfcomm: connecting to ${dash.name}")
        // cancelDiscovery() needs BLUETOOTH_SCAN on Android 12+; it's only a connect-speed
        // optimization (nothing is discovering here), so ignore it if the permission is absent.
        runCatching { adapter.cancelDiscovery() }
        val s = dash.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
        input = s.inputStream
        output = s.outputStream
        Log.d("Pillion", "rfcomm: connected")
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: error("channel not open")
        out.write(bytes)
        out.flush()
    }

    override fun read(buffer: ByteArray): Int =
        (input ?: error("channel not open")).read(buffer)

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    private companion object {
        // NaviLite SPP service UUID (0x7220).
        val SPP_UUID: UUID = UUID.fromString("00007220-0000-1000-8000-00805F9B34FB")
    }
}
