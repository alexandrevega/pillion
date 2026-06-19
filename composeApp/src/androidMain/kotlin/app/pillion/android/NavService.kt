package app.pillion.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import app.pillion.core.ByteChannel
import app.pillion.core.FrameReader
import app.pillion.core.Handshake
import app.pillion.nav.Guidance
import app.pillion.nav.HerePolyline
import app.pillion.nav.HereRoutingProvider
import app.pillion.nav.LatLng
import app.pillion.nav.NaviLiteTbt
import app.pillion.nav.RouteRequest
import app.pillion.nav.RouteResult
import app.pillion.nav.hereApiKeyOrEmpty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * LIVE, GPS-driven turn-by-turn that survives the screen being off.
 *
 * Foreground service (connectedDevice) that connects to the dash, sends the WHOLE route as a native
 * TBT list, then drives guidance from the phone's GPS: each fix is snapped to the route to derive
 * the active maneuver + distance-to-turn, which it streams to the dash. As the position moves the
 * cues advance — real navigation. With the screen off the foreground service keeps it running.
 *
 *   Enable mock GPS (desk demo):  adb shell appops set app.pillion android:mock_location allow
 *   Start: adb shell am start-foreground-service -n app.pillion/app.pillion.android.NavService
 *   Stop:  adb shell am stopservice              -n app.pillion/app.pillion.android.NavService
 *   Watch: adb logcat -s PillionNav
 *
 * At a desk we feed movement by injecting mock fixes stepped along the route into the GPS test
 * provider; the guidance reads them back as normal GPS. On the bike, drop the injector — real GPS
 * fixes flow through the identical path.
 */
class NavService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    private var imageMode = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        imageMode = intent?.getStringExtra("mode") != "tbt" // default: image (map + overlay)
        startAsForeground()
        scope.launch {
            runCatching { navigate() }.onFailure { Log.e(TAG, "nav failed", it) }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pillion navigation", NotificationManager.IMPORTANCE_LOW),
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Pillion navigation")
            .setContentText("Live turn-by-turn to the dash")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT / ACCESS_FINE_LOCATION granted via adb
    private suspend fun navigate() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("no Bluetooth adapter")
        val dash = adapter.getRemoteDevice(DASH_MAC)
        if (dash.bondState != BluetoothDevice.BOND_BONDED) {
            dash.createBond()
            var t = 0
            while (dash.bondState != BluetoothDevice.BOND_BONDED && t++ < 40) delay(500)
            check(dash.bondState == BluetoothDevice.BOND_BONDED) { "pairing failed" }
        }
        runCatching { adapter.cancelDiscovery() }
        val socket = dash.createInsecureRfcommSocketToServiceRecord(SPP)
        socket.connect()
        val channel = socketChannel(socket)
        try {
            val reader = FrameReader(channel)
            Handshake(channel, reader).perform()
            val key = hereApiKeyOrEmpty()
            check(key.isNotBlank()) { "no HERE key — set here.api.key in local.properties" }
            val result = HereRoutingProvider(apiKey = key).route(
                RouteRequest(origin = LatLng(52.3463, 4.8889), destination = LatLng(52.3791, 4.9003)),
            )
            val route = (result as? RouteResult.Success)?.routes?.firstOrNull()
                ?: error("routing failed: $result")
            val steps = route.steps
            val geometry = HerePolyline.decode(route.encodedGeometry.orEmpty())
            check(geometry.size >= 2) { "route has no geometry" }
            val cum = Guidance.cumulative(geometry)
            val maneuverDist = Guidance.maneuverDistances(steps)
            val total = cum.last()
            Log.i(TAG, "route: ${steps.size} maneuvers, ${total.toInt()} m geometry — sending list, going live")

            channel.write(NaviLiteTbt.contentUpdate(tbtOnly = !imageMode)) // 01 00 map image, 02 00 TBT-only
            for (frame in NaviLiteTbt.routeList(steps)) channel.write(frame)   // the WHOLE route

            val lm = getSystemService(LocationManager::class.java)
            runCatching { setupMockGps(lm) }.onFailure { Log.w(TAG, "mock GPS unavailable: ${it.message}") }

            // Image mode: render the map ourselves (CPU Canvas -> JPEG; works screen-off) and stream
            // it as image frames alongside the structured overlay. A drain coroutine consumes the
            // dash's IMAGE_ACKs so the socket's receive buffer doesn't back up.
            val renderer = if (imageMode) NavMapRenderer() else null
            val drain = if (imageMode) scope.launch { runCatching { while (isActive) reader.next() } } else null
            var seq = 0
            Log.i(TAG, "mode=${if (imageMode) "image (map + overlay)" else "tbt-only"}")

            // Drive movement along the route (~14 m/s ≈ 50 km/h). The position 'here' is the GPS
            // fix; we mirror it onto the system GPS test provider (best-effort) and run guidance on
            // it -> stream the active maneuver live. On the bike, 'here' comes from real GPS fixes.
            var along = 0.0
            var lastActive = -1
            while (along <= total && scope.isActive) {
                val here = Guidance.pointAt(geometry, cum, along)
                pushFix(lm, here)
                val distAlong = Guidance.snapDistance(geometry, cum, here)
                val prog = Guidance.progress(maneuverDist, distAlong)
                val step = steps[prog.activeIndex]
                if (prog.activeIndex != lastActive) {
                    channel.write(NaviLiteTbt.activeTurn(prog.activeIndex))
                    step.roadName?.let { channel.write(NaviLiteTbt.roadName(it)) }
                    lastActive = prog.activeIndex
                }
                channel.write(
                    NaviLiteTbt.nextTurn(
                        NaviLiteTbt.iconOf(step.maneuver), prog.remainingMeters.toFloat(),
                        nextRoad = step.roadName ?: "",
                    ),
                )
                if (renderer != null) {
                    channel.write(NaviLiteTbt.imageFrame(seq++, renderer.render(geometry, here)))
                }
                Log.i(TAG, "gps ${"%.5f".format(here.lat)},${"%.5f".format(here.lng)} " +
                    "-> active ${prog.activeIndex}/${steps.size} ${step.maneuver} in ${prog.remainingMeters}m")
                delay(1000)
                along += 14.0
            }
            drain?.cancel()
            Log.i(TAG, "arrived — navigation complete")
            delay(1000)
        } finally {
            channel.close()
        }
    }

    private fun setupMockGps(lm: LocationManager) {
        runCatching { lm.removeTestProvider(GPS) }
        @Suppress("DEPRECATION")
        lm.addTestProvider(GPS, false, false, false, false, true, true, true, 1, 1)
        lm.setTestProviderEnabled(GPS, true)
    }

    private fun pushFix(lm: LocationManager, p: LatLng) {
        runCatching {
            val loc = Location(GPS).apply {
                latitude = p.lat
                longitude = p.lng
                accuracy = 4f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            lm.setTestProviderLocation(GPS, loc)
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
        const val CHANNEL = "pillion_nav"
        const val NOTI_ID = 42
        const val GPS = LocationManager.GPS_PROVIDER
        // Fedora dev-dash Bluetooth adapter (advertises as "YCCU-dev").
        const val DASH_MAC = "4C:03:4F:0A:DC:AE"
        val SPP: UUID = UUID.fromString("00007220-0000-1000-8000-00805F9B34FB")
    }
}
