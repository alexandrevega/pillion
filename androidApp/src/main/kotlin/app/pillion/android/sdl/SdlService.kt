package app.pillion.android.sdl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import app.pillion.core.Logger
import app.pillion.core.MirrorState
import com.smartdevicelink.managers.SdlManager
import com.smartdevicelink.managers.SdlManagerListener
import com.smartdevicelink.managers.lifecycle.LifecycleConfigurationUpdate
import com.smartdevicelink.managers.lifecycle.OnSystemCapabilityListener
import com.smartdevicelink.protocol.enums.FunctionID
import com.smartdevicelink.proxy.RPCNotification
import com.smartdevicelink.proxy.rpc.OnButtonPress
import com.smartdevicelink.proxy.rpc.OnHMIStatus
import com.smartdevicelink.proxy.rpc.OnTouchEvent
import com.smartdevicelink.proxy.rpc.VideoStreamingCapability
import com.smartdevicelink.proxy.rpc.enums.AppHMIType
import com.smartdevicelink.proxy.rpc.enums.HMILevel
import com.smartdevicelink.proxy.rpc.enums.Language
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener
import com.smartdevicelink.streaming.video.SdlRemoteDisplay
import com.smartdevicelink.streaming.video.VideoStreamingParameters
import com.smartdevicelink.transport.BaseTransportConfig
import com.smartdevicelink.transport.MultiplexTransportConfig
import com.smartdevicelink.transport.TCPTransportConfig
import com.smartdevicelink.transport.enums.TransportType
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import com.smartdevicelink.util.SystemInfo
import java.util.Vector

/**
 * The SDL "Path B" session: registers with the head unit as an SDL NAVIGATION app and, once the unit
 * activates us (HMI FULL), screen-mirrors the phone over H.264 to the dash. Proven on a real Tracer
 * (see streetcross/sdlprobe). State is published to [SdlSessionState] for the Compose UI.
 *
 * Started by [SdlReceiver] on USB connect (auto, as Garmin Motorize) or explicitly with extras.
 */
class SdlService : Service() {

    private var sdlManager: SdlManager? = null
    private var currentKey: String? = null
    private var autoDiscoveryPending = false
    private var videoStarted = false
    private var mirrorMode = false
    private var hmiFull = false // dash has activated us (FULL/LIMITED) — video may start once we have capture
    private var stopping = false // user-initiated stop in progress — resolve async callbacks to Idle, not reconnect
    // Session health (Tracer field hardening, 2026-10): distinguishes a live session from a dead-but
    // non-null sdlManager object, so re-attach/retry logic rebuilds instead of silently no-op'ing.
    private var regOk = false // listener.onStart fired — the head unit accepted this session
    private var sessionFailed = false // transport/registration error seen on the current session
    private var lastBuildAt = 0L
    private var retryCount = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private var lastAppId: String? = null
    private var lastTcpHost: String? = null
    private var lastTcpPort = 12345
    // v4: when the dash leaves HMI FULL/LIMITED (NONE), stream frames into a dead UI for a while —
    // tear the video down after a 5 s grace so a quick flicker doesn't cut the stream; re-arm on FULL.
    private val videoHandler = Handler(Looper.getMainLooper())
    private val hmiTeardown = Runnable {
        if (!hmiFull && videoStarted) {
            Logger.d("sdl: dash stayed off FULL for 5 s — tearing video down (will re-arm automatically on FULL)")
            try {
                val vsm = sdlManager?.videoStreamManager
                if (vsm != null && vsm.isStreaming) vsm.stopStreaming()
            } catch (t: Throwable) {
                Logger.e("sdl: stopStreaming on HMI NONE err", t)
            }
            videoStarted = false
            SdlSessionState.state.value = MirrorState.Connecting
        }
    }
    private var videoCapability: VideoStreamingCapability? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        BikeInputBridge.init(applicationContext)
        startInForeground(false)
        // Wi-Fi behaves like USB from here: join the bike hotspot -> auto-discover -> auto-connect.
        startWifiWatch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The router auto-fires this whenever a transport (re)connects. If a session is already
        // running (especially a user-chosen screen-mirror), don't let the auto-start tear it down.
        // User tapped Stop: dispose and remember it, so the receiver doesn't auto-revive us on the
        // still-connected USB transport (which made Stop bounce back to "connecting").
        if (intent?.action == ACTION_STOP) {
            Logger.d("sdl: user stopped — disabling auto-restart until next Start")
            stopping = true // so the async projection-stop callback resolves to Idle, not Connecting
            setUserStopped(this, true)
            sdlManager?.dispose()
            sdlManager = null
            releaseCapture() // user Stop must also give back the MediaProjection + shared VirtualDisplay
            SdlSessionState.state.value = MirrorState.Idle
            stopSelf()
            return START_NOT_STICKY
        }
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        // Honour the user's Stop: ignore auto-revival from the receiver while stopped.
        if (auto && isUserStopped(this)) {
            Logger.d("sdl: auto-start suppressed — user stopped")
            stopSelf()
            return START_NOT_STICKY
        }
        // Any explicit (non-auto) start = the user wants it on again; clear the stop latch.
        if (!auto) setUserStopped(this, false)
        stopping = false // a start is proceeding — no longer stopping
        if (auto && sdlManager != null) {
            val healthy = regOk && !sessionFailed
            val tcpLive = !lastTcpHost.isNullOrEmpty()
            val staleWaiting =
                !regOk && !sessionFailed &&
                SystemClock.elapsedRealtime() - lastBuildAt > 15_000
            // v4 USB-priority policy (Settings toggle "USB overrides Wi-Fi", default ON): a USB attach
            // on a LIVE TCP session tears the TCP session down and rebuilds on USB (Motorize-like).
            // Toggle OFF = the healthy TCP session ignores the plug-in (charging / flaky-cable safety).
            if (healthy && tcpLive && isUsbOverride(this)) {
                Logger.d("sdl: ==== USB attached while TCP live + override ON -> switching session to USB ====")
                intentionalDispose()
                retryCount = 0 // a plug-in is a user gesture, not a failure retry
            } else if (healthy || (!sessionFailed && !staleWaiting)) {
                // Healthy (or freshly pending) session; re-asserting foreground here would churn the
                // FGS type and (on Android 14+) can stop an active screen capture. Leave it untouched.
                Logger.d("sdl: auto-start ignored — session already active" +
                    (if (healthy && tcpLive) " (TCP live, USB override OFF)" else ""))
                return START_STICKY
            } else {
                // Field lesson (Tracer, 2026-10): after ONE failed registration every later plug-in was a
                // silent no-op, because this guard accepted a dead sdlManager object as "active". A plug-in
                // on a failed or hung session must dispose + rebuild — that is the Motorize-like
                // "plug in and it keeps retrying" behaviour. The rebuild below uses THIS intent's transport
                // (auto intent = no tcpHost = USB), so a dead TCP session becomes a USB one.
                Logger.d("sdl: ==== auto-start on dead session -> disposing for rebuild ====")
                intentionalDispose()
                retryCount = 0
            }
        }
        // Mirror intent is sticky: once the user grants capture, keep streaming even if the router later
        // re-fires an auto-start without the flag.
        if (intent?.getBooleanExtra(EXTRA_MIRROR, false) == true) mirrorMode = true
        startInForeground(mirrorMode)
        if (mirrorMode) ensureProjection()
        val appId = intent?.getStringExtra(EXTRA_APP_ID) ?: APP_ID_GENERIC
        var tcpHost = intent?.getStringExtra(EXTRA_TCP_HOST)
        val tcpPort = intent?.getIntExtra(EXTRA_TCP_PORT, 12345) ?: 12345
        // v4 transport policy on manual Start:
        //  - cable already in + override ON  -> USB wins even if an IP was typed (incl. "auto" scan)
        //  - blank IP, no cable             -> arm only: no TCP, no scan; the attach builds the session
        if (isUsbOverride(this) && accessoryConnected() && !tcpHost.isNullOrEmpty()) {
            Logger.d("sdl: USB accessory present + override ON — starting on USB, ignoring IP $tcpHost")
            tcpHost = null
        }
        if (!auto && sdlManager == null && tcpHost == null && !accessoryConnected()) {
            Logger.d("sdl: armed (mirror ready) — no IP and no cable; waiting for USB attach or a Wi-Fi network to build the session")
            SdlSessionState.state.value = MirrorState.Connecting
            return START_STICKY
        }
        // The screen-capture grant is deliberately NOT part of the key: granting capture must reuse the
        // live SDL registration and just add video — never tear it down and re-register.
        val key = appId + "|" + (tcpHost ?: "mux") + (if (tcpHost != null) ":$tcpPort" else "")
        if (sdlManager != null && key != currentKey) {
            Logger.d("sdl: transport/appId changed -> disposing previous session")
            intentionalDispose()
        }
        if (sdlManager == null) {
            currentKey = key
            buildAndStart(appId, tcpHost, tcpPort)
        } else if (mirrorMode) {
            // Capture granted on an already-registered session — stream now if the dash has activated us.
            maybeStartVideo()
        }
        return START_STICKY
    }

    /** Turn the screen-capture grant into a live MediaProjection (FGS mediaProjection must be up first). */
    private fun ensureProjection() {
        if (ProjectionHolder.projection != null) return
        val data = ProjectionHolder.resultData
        if (data == null) {
            Logger.d("sdl mirror: no screen-capture grant — grant screen capture first")
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(ProjectionHolder.resultCode, data)
            // Android 14+ requires a registered callback before createVirtualDisplay.
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = handleProjectionStopped()
            }, Handler(Looper.getMainLooper()))
            ProjectionHolder.projection = mp
            Logger.d("sdl mirror: MediaProjection acquired")
        } catch (t: Throwable) {
            Logger.e("sdl mirror: failed to acquire MediaProjection", t)
        }
    }

    /** Give the capture back on every stop path: stop the live MediaProjection (kills the system
     *  "screen being cast" indicator) and free the process-level shared VirtualDisplay. Idempotent —
     *  safe when already null or already revoked by the system (its onStop callback no-ops). */
    private fun releaseCapture() {
        ProjectionHolder.projection?.let { runCatching { it.stop() } }
        ProjectionHolder.projection = null
        ScreenMirrorDisplay.releaseShared()
    }

    /** True when a USB accessory (the CCU in accessory mode) is currently open on this device. */
    private fun accessoryConnected(): Boolean = try {
        (getSystemService(Context.USB_SERVICE) as UsbManager).accessoryList?.isNotEmpty() == true
    } catch (_: Throwable) {
        false
    }

    // ---- Wi-Fi auto-connect (v4.0.4) -------------------------------------------------------------
    // Joining the bike's Wi-Fi hotspot should behave like a USB attach — no Start re-tap — but only
    // for users who OPT IN via the persisted tcp_host tri-state: blank = USB-only (no scan at all),
    // a literal IP/hostname = connect DIRECT to that host, "auto" = gateway-first probe + one /24
    // sweep per network (the hotspot's gateway is the bike: DHCP server + SDL host on the same box).

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var scannedSubnet: String? = null // one bounded subnet sweep per joined network

    private fun startWifiWatch() {
        if (wifiCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = wifiArrived(network)
                override fun onLost(network: Network) = wifiLost()
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                cb,
            )
            wifiCallback = cb
            Logger.d("sdl: Wi-Fi watch armed — joining an SDL-capable hotspot auto-connects")
            // Already on Wi-Fi when the service (re)starts — probe that network right away.
            wifiArrived(cm.activeNetwork)
        } catch (t: Throwable) {
            Logger.e("sdl: Wi-Fi watch failed to register", t)
        }
    }

    private fun stopWifiWatch() {
        wifiCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            } catch (_: Throwable) {
            }
        }
        wifiCallback = null
    }

    private fun wifiArrived(network: Network?) {
        if (stopping || isUserStopped(this) || sdlManager != null) return
        // A live USB session outranks Wi-Fi (Motorize-like); the USB toggle policy already handled
        // attach-time switching, so here we simply don't let Wi-Fi race in behind a real cable.
        if (accessoryConnected() && isUsbOverride(this)) return
        val prefs = getSharedPreferences("pillion.settings", Context.MODE_PRIVATE)
        val port = try {
            prefs.getInt("tcp_port", 12345)
        } catch (_: Throwable) {
            12345
        }
        // Tri-state opt-in (the persisted store IS the Start-time choice — App writes it on Start):
        // blank = USB-only, literal host = direct dial, "auto" = explicit gateway+sweep scan.
        val configuredHost = try {
            prefs.getString("tcp_host", "") ?: ""
        } catch (_: Throwable) {
            ""
        }
        val autoScan = configuredHost.equals("auto", ignoreCase = true)
        if (!autoScan && configuredHost.isNotBlank()) {
            // Literal IP/hostname: probe ONLY that host — no gateway probe, no /24 sweep.
            Logger.d("sdl: Wi-Fi up — dialling configured host $configuredHost:$port directly")
            Thread {
                if (!probeTcp(configuredHost, port, 2500)) {
                    Logger.d("sdl: configured SDL host $configuredHost does not answer on port $port — staying armed")
                    return@Thread
                }
                Handler(Looper.getMainLooper()).post {
                    if (stopping || isUserStopped(this) || sdlManager != null) return@post
                    Logger.d("sdl: ==== configured SDL host $configuredHost:$port answers — building session (USB-style) ====")
                    retryCount = 0
                    buildAndStart(lastAppId ?: APP_ID_GARMIN, configuredHost, port)
                }
            }.start()
            return
        }
        if (!autoScan) {
            Logger.d("transport: USB-only (blank host) — join scan skipped")
            return
        }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = network?.let { cm.getLinkProperties(it) } ?: return
        // Hotspot truth: the DHCP *server* is the hotspot host = the bike (also serving SDL).
        // No public LinkProperties.getGateways() on this SDK; a default route's gateway is the fallback.
        val gateway: InetAddress = lp.dhcpServerAddress
            ?: lp.routes.firstOrNull { it.gateway != null }?.gateway
            ?: return
        val prefix = linkPrefix(lp) ?: return
        Logger.d("sdl: Wi-Fi up via $prefix.0 gateway $gateway — probing SDL port $port")
        Thread {
            val host = discoverSdlHost(gateway, prefix, port)
            if (host == null) {
                Logger.d("sdl: Wi-Fi hotspot has no answering SDL port — staying armed")
                return@Thread
            }
            // Back on the main thread: building sessions is main-thread work (as everywhere here).
            Handler(Looper.getMainLooper()).post {
                if (stopping || isUserStopped(this) || sdlManager != null) return@post
                Logger.d("sdl: ==== Wi-Fi SDL host found at $host:$port — building session (USB-style) ====")
                retryCount = 0
                buildWithTransport(lastAppId ?: APP_ID_GARMIN, host, port)
            }
        }.start()
    }

    private fun wifiLost() {
        // Network gone = cable unplugged in USB semantics: drop the TCP session and wait for the
        // next onAvailable (which rediscovers) instead of letting the suite reconnect into nothing.
        if (!lastTcpHost.isNullOrEmpty() && sdlManager != null) {
            Logger.d("sdl: Wi-Fi lost — disposing TCP session, waiting for the network to return")
            intentionalDispose()
            retryCount = 0
        }
    }

    /** IPv4 /24 the phone holds on this network, e.g. "192.168.0" — the sweep scope. */
    private fun linkPrefix(lp: android.net.LinkProperties): String? = try {
        val addr = lp.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.address ?: return null
        addr.hostAddress?.split('.')?.dropLast(1)?.joinToString(".")
    } catch (_: Throwable) {
        null
    }

    /** Gateway first (the hotspot IS the bike), then one bounded /24 sweep — returns first live SDL host. */
    private fun discoverSdlHost(gateway: InetAddress, prefix: String, port: Int): String? {
        if (probeTcp(gateway.hostAddress, port, 2500)) return gateway.hostAddress
        if (scannedSubnet == prefix) return null // already swept this network; don't spam the LAN
        scannedSubnet = prefix
        val pool = Executors.newFixedThreadPool(24)
        try {
            val probes: List<Future<Boolean>> =
                (1..254).map { host -> pool.submit<Boolean> { probeTcp("$prefix.$host", port, 1200) } }
            // First address that answers the SDL port is treated as the Core.
            for ((idx, ff) in probes.withIndex()) {
                try {
                    if (ff.get(30, TimeUnit.SECONDS)) {
                        val host = "$prefix.${idx + 1}"
                        Logger.d("sdl: subnet sweep hit $host:$port")
                        return host
                    }
                } catch (_: Throwable) {
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return null
    }

    private fun probeTcp(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: Throwable) {
        false
    }

    private fun startInForeground(mirror: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pillion SDL", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pillion — connecting to dash")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Pass the EXACT type — the no-arg startForeground validates ALL manifest types, and
            // mediaProjection without a projection grant throws (that was the crash).
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            // mirror mode ALWAYS requests the projection type — on FIRST grant the projection object
            // does not exist yet yet the type must already be registered for ensureProjection() to be
            // legal (Android 14+). If Android refuses (no grant held) the catch below falls back.
            val wantProjection = mirror
            if (wantProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            try {
                startForeground(1, n, type)
            } catch (t: Throwable) {
                // Never crash on FGS churn: fall back to the safe connectedDevice type.
                if (wantProjection) {
                    Logger.e("sdl: FGS mediaProjection type refused — falling back to connectedDevice", t)
                    try {
                        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } catch (_: Throwable) {
                    }
                } else {
                    throw t
                }
            }
        } else {
            startForeground(1, n)
        }
    }

    private fun buildAndStart(appId: String, tcpHost: String?, tcpPort: Int) {
        SdlSessionState.state.value = MirrorState.Connecting
        if (tcpHost == "auto") {
            // No manual IP — scan the current Wi-Fi subnet for an open SDL port.
            val main = Handler(Looper.getMainLooper())
            if (autoDiscoveryPending) {
                Logger.d("sdl: auto-discovery already running — ignoring re-fire")
                return
            }
            autoDiscoveryPending = true
            Thread {
                Logger.d("sdl: auto-discovering dash on :$tcpPort …")
                val host = TcpDiscovery.discover(tcpPort)
                main.post {
                    autoDiscoveryPending = false
                    if (host == null) {
                        Logger.d("sdl: auto-discovery found nothing — waiting for manual IP or USB")
                        SdlSessionState.state.value = MirrorState.Idle
                    } else if (sdlManager != null || stopping || isUserStopped(this)) {
                        // Bug 9 (v4): a late scan result must NEVER replace a live session — that
                        // orphaned an active USB/mux registration behind a fresh TCP one.
                        Logger.d("sdl: late discovery result skipped — session already exists")
                    } else {
                        buildWithTransport(appId, host, tcpPort)
                    }
                }
            }.start()
        } else {
            buildWithTransport(appId, tcpHost, tcpPort)
        }
    }

    /** Re-arm a fresh SDL session after a failure without waiting for a re-plug (Motorize-like retry).
     *  Escalating backoff: 8 s, 24 s, 72 s, then 3-minute steps, capped at [MAX_SESSION_RETRY] tries;
     *  a successful registration resets the series. Explicit delayMs overrides the first attempt. */
    private fun scheduleRebuild(delayMs: Long = -1L) {
        if (retryCount >= MAX_SESSION_RETRY) {
            Logger.d("sdl: session rebuild cap reached — waiting for manual Start or re-plug")
            return
        }
        val delay = if (delayMs >= 0) delayMs
        else minOf(8_000L * 3.0.pow(retryCount.toDouble()).toLong(), 180_000L)
        retryCount++
        retryHandler.removeCallbacksAndMessages(null)
        Logger.d("sdl: rebuilding SDL session in ${delay / 1000} s (attempt $retryCount/$MAX_SESSION_RETRY)")
        retryHandler.postDelayed({
            if (!stopping && !isUserStopped(this) && !regOk) {
                Logger.d("sdl: ==== REBUILDING SDL SESSION ====")
                sdlManager?.dispose()
                sdlManager = null
                buildWithTransport(lastAppId ?: APP_ID_GENERIC, lastTcpHost, lastTcpPort)
            }
        }, delay)
    }

    /** Policy-driven dispose (USB switch, dead session, key change, Wi-Fi loss): SdlManager.dispose()
     *  fires the listener's onDestroy SYNCHRONOUSLY, which self-arms a 20 s rebuild against the very
     *  session we are intentionally replacing. Dispose is synchronous, so scrubbing the retry queue
     *  right AFTER it is airtight (video/HMI handlers untouched). */
    private fun intentionalDispose() {
        sdlManager?.dispose()
        sdlManager = null
        retryHandler.removeCallbacksAndMessages(null)
    }

    private fun buildWithTransport(appId: String, tcpHost: String?, tcpPort: Int) {
        // A fresh build always wins: cancel any rebuild still pending from the previous session so it
        // can't stomp this one mid-registration (see intentionalDispose).
        retryHandler.removeCallbacksAndMessages(null)
        regOk = false
        sessionFailed = false
        // Fresh session = fresh video gate: the old session's VideoStreamManager died with it, so
        // maybeStartVideo() must be allowed to start a new one when the dash goes HMI FULL again.
        videoStarted = false
        hmiFull = false
        lastBuildAt = SystemClock.elapsedRealtime()
        lastAppId = appId
        lastTcpHost = tcpHost
        lastTcpPort = tcpPort
        val garmin = APP_ID_GARMIN == appId
        val appTypes = Vector<AppHMIType>().apply { add(AppHMIType.NAVIGATION) }

        val transport: BaseTransportConfig
        if (!tcpHost.isNullOrEmpty()) {
            Logger.d("sdl: building SdlManager — NAVIGATION, TCP $tcpHost:$tcpPort, appId=$appId")
            transport = TCPTransportConfig(tcpPort, tcpHost, true)
        } else {
            Logger.d("sdl: building SdlManager — NAVIGATION, USB, appId=$appId" + if (garmin) " (Motorize)" else " (generic)")
            val mux = MultiplexTransportConfig(this, appId, MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF)
            if (garmin) {
                // Match Garmin Motorize's exact transport profile (USB-only + high-bandwidth) — the
                // Tracer identifies the video app by this; a BT/low-bandwidth registration isn't
                // recognised as Motorize (→ "Connection Error" on HOME-hold).
                mux.setPrimaryTransports(listOf(TransportType.USB))
                mux.setRequiresHighBandwidth(true)
            } else {
                mux.setPrimaryTransports(listOf(TransportType.USB, TransportType.BLUETOOTH))
                mux.setRequiresHighBandwidth(false)
            }
            transport = mux
        }

        val listener = object : SdlManagerListener {
            override fun onStart() {
                regOk = true
                sessionFailed = false
                retryCount = 0
                Logger.d("sdl: ==== REGISTERED — head unit accepted us ====")
                Logger.d("sdl: now activate the app on the dash (HMI FULL) — video starts then")
            }

            override fun onDestroy() {
                Logger.d("sdl: session ended (onDestroy)")
                sessionFailed = true
                regOk = false
                sdlManager = null
                SdlSessionState.state.value = MirrorState.Idle
                // Unexpected end while the user still wants the link: the suite's own transport
                // auto-reconnect gets first refusal (it revives the live session in seconds); if it
                // hasn't re-registered within 20 s, force a full session rebuild ourselves.
                if (isUserStopped(this@SdlService) || stopping) {
                    stopSelf()
                } else {
                    scheduleRebuild(20_000)
                }
            }

            override fun onError(info: String?, e: Exception?) {
                Logger.d("sdl ERROR: $info ${e?.message ?: ""}")
                if (stopping || isUserStopped(this@SdlService)) {
                    SdlSessionState.state.value = MirrorState.Idle
                    return
                }
                sessionFailed = true
                regOk = false
                SdlSessionState.state.value = MirrorState.Error(info ?: "SDL error")
                scheduleRebuild()
            }

            override fun managerShouldUpdateLifecycle(language: Language?, hmiLanguage: Language?): LifecycleConfigurationUpdate? = null

            override fun onSystemInfoReceived(systemInfo: SystemInfo?): Boolean {
                if (systemInfo != null) {
                    Logger.d("sdl systemInfo: vehicleType=${systemInfo.vehicleType} sw=${systemInfo.systemSoftwareVersion}")
                }
                return true // accept the connection
            }
        }

        videoStarted = false
        // The Tracer has no app icons — HOME-hold matches the SDL app by NAME + ID, so with the Garmin
        // ID we must also send Garmin's exact label ("Motorize") or the bike won't recognise us.
        val appName = if (garmin) "Motorize" else APP_NAME
        val builder = SdlManager.Builder(this, appId, appName, transport, listener)
        builder.setAppTypes(appTypes)

        val notifs = HashMap<FunctionID, OnRPCNotificationListener>()
        notifs[FunctionID.ON_HMI_STATUS] = object : OnRPCNotificationListener() {
            override fun onNotified(notification: RPCNotification) {
                val lvl = (notification as OnHMIStatus).hmiLevel
                Logger.d("sdl HMI status: $lvl")
                hmiFull = lvl == HMILevel.HMI_FULL || lvl == HMILevel.HMI_LIMITED
                if (hmiFull) {
                    videoHandler.removeCallbacks(hmiTeardown)
                    maybeStartVideo()
                } else if (videoStarted) {
                    videoHandler.removeCallbacks(hmiTeardown)
                    videoHandler.postDelayed(hmiTeardown, 5_000)
                }
            }
        }
        // Log the bike's joystick/controls so we can see what comes through (zoom/scroll/buttons).
        notifs[FunctionID.ON_TOUCH_EVENT] = object : OnRPCNotificationListener() {
            override fun onNotified(notification: RPCNotification) {
                val te = notification as OnTouchEvent
                Logger.d("sdl BIKE TOUCH/JOYSTICK: type=${te.type} events=${te.event}")
            }
        }
        notifs[FunctionID.ON_BUTTON_PRESS] = object : OnRPCNotificationListener() {
            override fun onNotified(notification: RPCNotification) {
                val bp = notification as OnButtonPress
                Logger.d("sdl BIKE BUTTON: ${bp.buttonName} (${bp.buttonPressMode})")
                // Mirror path actuation: synthesize the key locally over the in-app ADB channel when
                // one is alive; log-only fallback otherwise (see BikeInputBridge).
                BikeInputBridge.dispatchBikeButton(bp.buttonName?.toString(), bp.buttonPressMode?.toString())
            }
        }
        builder.setRPCNotificationListeners(notifs)
        val mgr = builder.build()
        sdlManager = mgr
        mgr.start()
        Logger.d("sdl: SdlManager.start() called — waiting for the head unit…")
    }

    /**
     * Start video only when BOTH conditions hold, in whichever order they arrive: the dash has activated
     * us (HMI FULL/LIMITED) AND the user has granted screen capture. This lets the session register on
     * plug-in (no capture yet) and begin streaming the moment capture is granted in-app — no reconnect.
     */
    private fun maybeStartVideo() {
        if (videoStarted || !hmiFull) return
        if (ProjectionHolder.resultData == null) {
            Logger.d("sdl: dash activated us, but no screen capture yet — open Pillion and tap Start mirroring to grant it")
            return
        }
        videoStarted = true
        Logger.d("sdl: activated + capture granted -> querying video + starting stream")
        queryVideoCapability()
    }

    private fun queryVideoCapability() {
        try {
            sdlManager?.systemCapabilityManager?.getCapability(
                SystemCapabilityType.VIDEO_STREAMING,
                object : OnSystemCapabilityListener {
                    override fun onCapabilityRetrieved(capability: Any?) {
                        if (capability is VideoStreamingCapability) {
                            videoCapability = capability
                            Logger.d("sdl: ==== VIDEO STREAMING SUPPORTED ====")
                            Logger.d("sdl: preferredResolution=${capability.preferredResolution} maxBitrate=${capability.maxBitrate} formats=${capability.supportedFormats}")
                            attemptVideoStream()
                        } else {
                            Logger.d("sdl: VIDEO_STREAMING capability not as expected: $capability")
                        }
                    }

                    override fun onError(info: String?) {
                        Logger.d("sdl: ==== NO VIDEO STREAMING ($info) ==== — this dash has no SDL video")
                        SdlSessionState.state.value = MirrorState.Error("dash has no SDL video")
                    }
                },
                false,
            )
        } catch (t: Throwable) {
            Logger.e("sdl: queryVideoCapability failed", t)
        }
    }

    private fun attemptVideoStream() {
        try {
            val vsm = sdlManager?.videoStreamManager
            if (vsm == null) {
                Logger.d("sdl: videoStreamManager is null — cannot stream")
                return
            }
            if (ProjectionHolder.resultData == null) {
                Logger.d("sdl: no screen-capture grant — registered, not streaming (grant capture then reconnect)")
                return
            }
            mirrorMode = true
            startInForeground(true) // upgrade FGS to include mediaProjection
            ensureProjection()
            acquireWakeLock()
            val params = buildVideoParams()
            Logger.d("sdl: starting VideoStreamManager (screen-mirror${if (params != null) ", bitrate=${params.bitrate}" else ""})…")
            vsm.start { success ->
                if (success) {
                    val displayClass: Class<out SdlRemoteDisplay> = ScreenMirrorDisplay::class.java
                    vsm.startRemoteDisplayStream(applicationContext, displayClass, params, false)
                    SdlSessionState.state.value = MirrorState.Broadcasting
                    Logger.d("sdl: screen-mirror stream requested — look at the dash")
                } else {
                    Logger.d("sdl: VideoStreamManager.start() reported failure")
                    videoStarted = false
                    SdlSessionState.state.value = MirrorState.Error("video stream failed to start")
                }
            }
        } catch (t: Throwable) {
            Logger.e("sdl: attemptVideoStream failed", t)
        }
    }

    /** Stream at the dash's preferred resolution + full bitrate budget (sharper than the low default). */
    private fun buildVideoParams(): VideoStreamingParameters? {
        val cap = videoCapability ?: return null
        return try {
            VideoStreamingParameters().apply {
                cap.preferredResolution?.let { resolution = it }
                cap.maxBitrate?.let { bitrate = it }
            }
        } catch (t: Throwable) {
            Logger.e("sdl: buildVideoParams failed", t)
            null
        }
    }

    /**
     * Screen turned off (or the system pulled capture). Stop the video cleanly and drop the
     * mediaProjection FGS type so the OS doesn't kill the whole service — that kill is what was
     * hanging the bike. The SDL session itself stays registered.
     */
    private fun handleProjectionStopped() {
        Logger.d("sdl mirror: projection stopped (screen off?) — stopping video, keeping session")
        try {
            val vsm = sdlManager?.videoStreamManager
            if (vsm != null && vsm.isStreaming) vsm.stopStreaming()
        } catch (t: Throwable) {
            Logger.e("sdl: stopStreaming err", t)
        }
        ProjectionHolder.projection = null
        // The projection token is spent: a new capture needs a brand-new consent, so drop the stale
        // result too, and free the process-level mirror display that belonged to the dead grant.
        ProjectionHolder.resultData = null
        ScreenMirrorDisplay.releaseShared()
        mirrorMode = false
        videoStarted = false // allow video to restart if the user grants capture again
        releaseWakeLock()
        if (stopping) {
            // Deliberate Stop: don't bounce to Connecting — the service is going Idle.
            SdlSessionState.state.value = MirrorState.Idle
            return
        }
        SdlSessionState.state.value = MirrorState.Connecting
        try {
            startInForeground(false)
        } catch (t: Throwable) {
            Logger.e("sdl: FGS downgrade err", t)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // v4: timeout as a belt-and-braces — if a projection-stop path ever misses the release,
            // the phone still falls asleep eventually instead of draining overnight (8 h > any ride).
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pillion:sdl-cast")
                .also { it.acquire(8 * 60 * 60 * 1000L) }
        } catch (t: Throwable) {
            Logger.e("sdl: wakeLock acquire err", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        // v4 hardening: no zombie rebuild/post-HMI callbacks may fire after the service is gone.
        retryHandler.removeCallbacksAndMessages(null)
        videoHandler.removeCallbacksAndMessages(null)
        stopWifiWatch()
        releaseWakeLock()
        releaseCapture() // never leave the "screen being cast" indicator up after the service is gone
        intentionalDispose() // same self-armed-rebuild scrub as the internal disposes
        SdlSessionState.state.value = MirrorState.Idle
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_APP_ID = "appId"
        const val EXTRA_TCP_HOST = "tcpHost"
        const val EXTRA_TCP_PORT = "tcpPort"
        const val EXTRA_MIRROR = "mirror"

        /** Set by [SdlReceiver] auto-start; such intents must not clobber an already-running session. */
        const val EXTRA_AUTO = "auto"

        /** User tapped Stop — tear the session down AND suppress auto-revival until the next Start. */
        const val ACTION_STOP = "app.pillion.sdl.STOP"

        private const val PREFS = "sdl_state"
        private const val KEY_USER_STOPPED = "user_stopped"

        /**
         * Persisted "user stopped" flag. While set, [SdlReceiver]/auto-start must NOT re-register — the
         * USB transport stays connected, so without this Stop would instantly bounce back to connecting.
         */
        fun setUserStopped(ctx: Context, stopped: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USER_STOPPED, stopped).apply()
        }

        fun isUserStopped(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USER_STOPPED, false)

        /**
         * v4 transport policy toggle — read from the MAIN settings store (AndroidSettingsStore),
         * so the Settings checkbox applies immediately without a Start round-trip. Default ON:
         * USB attach overrides a live/possible TCP session. OFF: a healthy TCP session ignores
         * the plug-in; a dead one still rebuilds on USB.
         */
        fun isUsbOverride(ctx: Context): Boolean =
            ctx.getSharedPreferences("pillion.settings", Context.MODE_PRIVATE).getBoolean("usb_override", true)

        const val APP_ID_GENERIC = "5c9d0a3e-8b27-4f6a-9c11-7e2d3a4b5c6d"

        /** Garmin Motorize's real fullAppID — passes the head unit's policy gate. */
        const val APP_ID_GARMIN = "7a5f3f25-8b82-4e0f-a173-80aefee79897"

        private const val APP_NAME = "Pillion"
        private const val CHANNEL_ID = "pillion-sdl"
        private const val MAX_SESSION_RETRY = 10
    }
}
