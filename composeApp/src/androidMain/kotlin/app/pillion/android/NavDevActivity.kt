package app.pillion.android

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.util.Log
import app.pillion.core.ByteChannel
import app.pillion.core.FrameReader
import app.pillion.core.Handshake
import app.pillion.nav.HereRoutingProvider
import app.pillion.nav.LatLng
import app.pillion.nav.NaviLiteTbt
import app.pillion.nav.RouteRequest
import app.pillion.nav.RouteResult
import app.pillion.nav.hereApiKeyOrEmpty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Dev-only: drive a real HERE route -> NaviLite turn-by-turn over the bonded dash (RFCOMM).
 *
 *   Launch:  adb shell am start -n app.pillion/app.pillion.android.NavDevActivity
 *   Watch:   adb logcat -s PillionNav
 *
 * Pairs with the dev dash by MAC if needed, runs the real handshake, fetches a HERE route via the
 * shared NavEngine, and streams the structured turn-by-turn frames (content-update 02 00 + per
 * maneuver). The dash (real Garmin or the navilite-receiver emulator) renders the cues natively.
 */
class NavDevActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch { runCatching { drive() }.onFailure { Log.e(TAG, "nav dev failed", it) } }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT granted via adb for this dev tool
    private suspend fun drive() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("no Bluetooth adapter")
        val dash = adapter.getRemoteDevice(DASH_MAC)

        if (dash.bondState != BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "pairing with $DASH_MAC …")
            dash.createBond()
            var tries = 0
            while (dash.bondState != BluetoothDevice.BOND_BONDED && tries++ < 40) delay(500)
            check(dash.bondState == BluetoothDevice.BOND_BONDED) { "pairing failed (state=${dash.bondState})" }
        }
        Log.i(TAG, "bonded with ${dash.name ?: dash.address}; opening RFCOMM (SPP 0x7220) …")
        runCatching { adapter.cancelDiscovery() }
        val socket = dash.createInsecureRfcommSocketToServiceRecord(SPP)
        socket.connect()
        val channel = socketChannel(socket)

        try {
            Handshake(channel, FrameReader(channel)).perform()
            Log.i(TAG, "handshake OK; fetching HERE route …")

            val key = hereApiKeyOrEmpty()
            check(key.isNotBlank()) { "no HERE key — set here.api.key in local.properties" }
            val result = HereRoutingProvider(apiKey = key).route(
                RouteRequest(origin = LatLng(52.3463, 4.8889), destination = LatLng(52.3791, 4.9003)),
            )
            val route = (result as? RouteResult.Success)?.routes?.firstOrNull()
                ?: error("routing failed: $result")
            Log.i(TAG, "HERE: ${route.distanceMeters} m, +${route.trafficDelaySeconds}s traffic, " +
                "${route.steps.size} maneuvers")

            channel.write(NaviLiteTbt.contentUpdate(tbtOnly = true))   // 02 00: dash renders TBT, no JPEG
            for (step in route.steps) {
                for (frame in NaviLiteTbt.framesFor(step, step.distanceMeters.toFloat())) {
                    channel.write(frame)
                }
                Log.i(TAG, "  sent icon ${NaviLiteTbt.iconOf(step.maneuver)} ${step.maneuver} " +
                    "${step.distanceMeters}m -> ${step.roadName ?: ""}")
                delay(150)
            }
            Log.i(TAG, "done — ${route.steps.size} maneuvers sent to the dash")
            delay(800)
        } finally {
            channel.close()
        }
    }

    private fun socketChannel(socket: BluetoothSocket): ByteChannel = object : ByteChannel {
        private val inp = socket.inputStream
        private val out = socket.outputStream
        override fun open() {}
        override fun write(bytes: ByteArray) { out.write(bytes); out.flush() }
        override fun read(buffer: ByteArray): Int = inp.read(buffer)
        override fun close() { runCatching { socket.close() } }
    }

    private companion object {
        const val TAG = "PillionNav"
        // Fedora dev-dash Bluetooth adapter (advertises as "YCCU-dev"). Change for another dash.
        const val DASH_MAC = "4C:03:4F:0A:DC:AE"
        val SPP: UUID = UUID.fromString("00007220-0000-1000-8000-00805F9B34FB")
    }
}
