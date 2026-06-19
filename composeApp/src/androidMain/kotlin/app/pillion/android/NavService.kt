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
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Live turn-by-turn navigation to the dash, screen-off-safe (foreground service).
 *
 * Routes origin → destination via HERE, sends the WHOLE route as a native TBT list, then drives
 * guidance from position fixes: each fix snaps to the route to yield the active maneuver + distance,
 * streamed to the dash (plus a self-rendered map JPEG in image mode). Two position sources:
 *  - LIVE GPS (the bike): real LocationManager fixes — origin defaults to the current fix.
 *  - SIM (the desk): mock fixes stepped along the route, so progress is visible while stationary.
 *
 * Extras: dlat/dlng (destination), olat/olng (origin; omit to use current GPS), live (bool),
 * mode ("image" default | "tbt"). Start via NavDevActivity or:
 *   adb shell am start-foreground-service -n app.pillion/app.pillion.android.NavService \
 *     --ed dlat 52.3791 --ed dlng 4.9003 --ez live false
 */
class NavService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fixes = Channel<Location>(Channel.CONFLATED)
    private var locListener: LocationListener? = null

    private var imageMode = true
    private var liveGps = false
    private var origin: LatLng? = null
    private var dest = LatLng(52.3791, 4.9003)
    // Dash target: a specific MAC (the desk emulator) or "" to find the bonded *CCU* by name (bike).
    private var dashMac = "4C:03:4F:0A:DC:AE"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        imageMode = intent?.getStringExtra("mode") != "tbt"
        liveGps = intent?.getBooleanExtra("live", false) == true
        intent?.getStringExtra("dash")?.let { dashMac = it }
        intent?.let {
            if (it.hasExtra("dlat")) dest = LatLng(it.getDoubleExtra("dlat", dest.lat), it.getDoubleExtra("dlng", dest.lng))
            origin = if (it.hasExtra("olat")) LatLng(it.getDoubleExtra("olat", 0.0), it.getDoubleExtra("olng", 0.0)) else null
        }
        startAsForeground()
        scope.launch {
            runCatching { navigate() }.onFailure { Log.e(TAG, "nav failed", it) }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        locListener?.let { l -> runCatching { getSystemService(LocationManager::class.java).removeUpdates(l) } }
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
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (liveGps) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTI_ID, n, type)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT / ACCESS_FINE_LOCATION granted by the app
    private suspend fun navigate() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("no Bluetooth adapter")
        val channel: ByteChannel = if (dashMac.isNotBlank()) {
            // Specific dash by MAC (desk emulator); pair if needed.
            val dev = adapter.getRemoteDevice(dashMac)
            if (dev.bondState != BluetoothDevice.BOND_BONDED) {
                dev.createBond()
                var t = 0
                while (dev.bondState != BluetoothDevice.BOND_BONDED && t++ < 40) delay(500)
                check(dev.bondState == BluetoothDevice.BOND_BONDED) { "pairing failed" }
            }
            runCatching { adapter.cancelDiscovery() }
            val s = dev.createInsecureRfcommSocketToServiceRecord(SPP)
            s.connect()
            socketChannel(s)
        } else {
            // No MAC → connect to the bonded dash by name (*CCU*): the real bike CCU.
            RfcommByteChannel().also { it.open() }
        }
        try {
            val reader = FrameReader(channel)
            Handshake(channel, reader).perform()

            val key = hereApiKeyOrEmpty()
            check(key.isNotBlank()) { "no HERE key — set here.api.key in local.properties" }

            val lm = getSystemService(LocationManager::class.java)
            if (liveGps) startLiveUpdates(lm)

            val from = origin ?: if (liveGps) {
                Log.i(TAG, "waiting for first GPS fix…")
                val f = withTimeoutOrNull(25_000) { fixes.receive() } ?: error("no GPS fix in 25s")
                LatLng(f.latitude, f.longitude)
            } else {
                LatLng(52.3463, 4.8889) // desk default origin
            }
            Log.i(TAG, "routing ${"%.5f".format(from.lat)},${"%.5f".format(from.lng)} -> " +
                "${"%.5f".format(dest.lat)},${"%.5f".format(dest.lng)} (live=$liveGps, image=$imageMode)")

            val result = HereRoutingProvider(apiKey = key).route(RouteRequest(origin = from, destination = dest))
            val route = (result as? RouteResult.Success)?.routes?.firstOrNull()
                ?: error("routing failed: $result")
            val steps = route.steps
            val geometry = HerePolyline.decode(route.encodedGeometry.orEmpty())
            check(geometry.size >= 2) { "route has no geometry" }
            val cum = Guidance.cumulative(geometry)
            val maneuverDist = Guidance.maneuverDistances(steps)
            val total = cum.last()
            Log.i(TAG, "route: ${steps.size} maneuvers, ${total.toInt()} m")

            channel.write(NaviLiteTbt.contentUpdate(tbtOnly = !imageMode))
            for (frame in NaviLiteTbt.routeList(steps)) channel.write(frame)

            val renderer = if (imageMode) NavMapRenderer(apiKey = key) else null
            val drain = if (imageMode) scope.launch { runCatching { while (isActive) reader.next() } } else null
            var seq = 0
            var lastActive = -1

            suspend fun emit(here: LatLng): Double {
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
                if (renderer != null) channel.write(NaviLiteTbt.imageFrame(seq++, renderer.render(geometry, here)))
                Log.i(TAG, "here ${"%.5f".format(here.lat)},${"%.5f".format(here.lng)} -> " +
                    "active ${prog.activeIndex}/${steps.size} ${step.maneuver} in ${prog.remainingMeters}m")
                return distAlong
            }

            if (liveGps) {
                Log.i(TAG, "LIVE GPS guidance")
                while (scope.isActive) {
                    val loc = fixes.receive()
                    val distAlong = emit(LatLng(loc.latitude, loc.longitude))
                    if (distAlong >= total - ARRIVE_THRESHOLD_M) { Log.i(TAG, "arrived"); break }
                }
            } else {
                Log.i(TAG, "SIM guidance (mock fixes along the route)")
                runCatching { setupMockGps(lm) }.onFailure { Log.w(TAG, "mock GPS off: ${it.message}") }
                var along = 0.0
                while (along <= total && scope.isActive) {
                    val here = Guidance.pointAt(geometry, cum, along)
                    pushFix(lm, here)
                    emit(here)
                    delay(1000)
                    along += 14.0
                }
            }
            drain?.cancel()
            Log.i(TAG, "navigation complete")
            delay(1000)
        } finally {
            channel.close()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startLiveUpdates(lm: LocationManager) = withContext(Dispatchers.Main) {
        val l = LocationListener { loc -> fixes.trySend(loc) }
        locListener = l
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
        }.onFailure { Log.w(TAG, "GPS updates unavailable: ${it.message}") }
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
        const val ARRIVE_THRESHOLD_M = 25.0
        val SPP: UUID = UUID.fromString("00007220-0000-1000-8000-00805F9B34FB")
    }
}
