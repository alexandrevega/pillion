package app.pillion.android.sdl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    private var videoStarted = false
    private var mirrorMode = false
    private var hmiFull = false // dash has activated us (FULL/LIMITED) — video may start once we have capture
    private var stopping = false // user-initiated stop in progress — resolve async callbacks to Idle, not reconnect
    private var videoCapability: VideoStreamingCapability? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground(false)
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
            // Already registered; re-asserting foreground here would churn the FGS type and (on
            // Android 14+) can stop an active screen capture. Leave the live session untouched.
            Logger.d("sdl: auto-start ignored — session already active")
            return START_STICKY
        }
        // Mirror intent is sticky: once the user grants capture, keep streaming even if the router later
        // re-fires an auto-start without the flag.
        if (intent?.getBooleanExtra(EXTRA_MIRROR, false) == true) mirrorMode = true
        startInForeground(mirrorMode)
        if (mirrorMode) ensureProjection()
        val appId = intent?.getStringExtra(EXTRA_APP_ID) ?: APP_ID_GENERIC
        val tcpHost = intent?.getStringExtra(EXTRA_TCP_HOST)
        val tcpPort = intent?.getIntExtra(EXTRA_TCP_PORT, 12345) ?: 12345
        // The screen-capture grant is deliberately NOT part of the key: granting capture must reuse the
        // live SDL registration and just add video — never tear it down and re-register.
        val key = appId + "|" + (tcpHost ?: "mux") + (if (tcpHost != null) ":$tcpPort" else "")
        if (sdlManager != null && key != currentKey) {
            Logger.d("sdl: transport/appId changed -> disposing previous session")
            sdlManager?.dispose()
            sdlManager = null
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
            if (mirror) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(1, n, type)
        } else {
            startForeground(1, n)
        }
    }

    private fun buildAndStart(appId: String, tcpHost: String?, tcpPort: Int) {
        SdlSessionState.state.value = MirrorState.Connecting
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
                Logger.d("sdl: ==== REGISTERED — head unit accepted us ====")
                Logger.d("sdl: now activate the app on the dash (HMI FULL) — video starts then")
            }

            override fun onDestroy() {
                Logger.d("sdl: session ended (onDestroy)")
                SdlSessionState.state.value = MirrorState.Idle
                stopSelf()
            }

            override fun onError(info: String?, e: Exception?) {
                Logger.d("sdl ERROR: $info ${e?.message ?: ""}")
                SdlSessionState.state.value = MirrorState.Error(info ?: "SDL error")
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
                if (hmiFull) maybeStartVideo()
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pillion:sdl-cast").also { it.acquire() }
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
        releaseWakeLock()
        sdlManager?.dispose()
        sdlManager = null
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

        const val APP_ID_GENERIC = "5c9d0a3e-8b27-4f6a-9c11-7e2d3a4b5c6d"

        /** Garmin Motorize's real fullAppID — passes the head unit's policy gate. */
        const val APP_ID_GARMIN = "7a5f3f25-8b82-4e0f-a173-80aefee79897"

        private const val APP_NAME = "Pillion"
        private const val CHANNEL_ID = "pillion-sdl"
    }
}
