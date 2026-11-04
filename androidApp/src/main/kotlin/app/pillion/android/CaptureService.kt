package app.pillion.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import app.pillion.core.DashMargins
import app.pillion.core.DashMarginColor
import app.pillion.core.DashDpi
import app.pillion.core.DashResolution
import app.pillion.core.MirrorEngine
import app.pillion.core.ScreenSource
import app.pillion.core.MirrorState
import app.pillion.core.CastMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that hosts a mirroring session. MediaProjection requires a running
 * foreground service of type mediaProjection, so the engine lives here for its lifetime.
 * Single responsibility: own the Android service lifecycle and surface the engine's state.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: MirrorEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dashEnabled = false
    private var dashSource: DashStreamScreenSource? = null
    private var dashModeSource: DashModeScreenSource? = null
    private var screenReceiver: BroadcastReceiver? = null
    @Volatile private var dashPromotedAtMs = 0L
    @Volatile private var dashSawLockedKeyguard = false
    @Volatile private var promotionGeneration = 0L
    private var dashTarget: String? = null
    private var ownsSession = false
    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine != null || _mode.value != CastMode.NONE) {
            // A Stop/Start race can create a second Service instance while the old one cleans up.
            if (!ownsSession) stopSelf(startId)
            return START_NOT_STICKY
        }
        ownsSession = true
        dashEnabled = intent?.getBooleanExtra(EXTRA_DASH_ENABLED, false) ?: false
        _mode.value = if (dashEnabled) CastMode.DASH else CastMode.MIRROR
        acquireWakeLock()
        _state.value = MirrorState.Connecting
        val quality = intent?.getIntExtra(EXTRA_QUALITY, 40) ?: 40
        val fps = intent?.getIntExtra(EXTRA_MAX_FPS, 15) ?: 15
        if (dashEnabled) startDashSession(quality, fps, dashResolutionFrom(intent), intent?.getStringExtra(EXTRA_DASH_COMPONENT), DashDpi.snap(intent?.getIntExtra(EXTRA_DASH_DPI, DashDpi.DEFAULT) ?: DashDpi.DEFAULT),
            DashMargins.clamped(intent?.getIntExtra(EXTRA_MARGIN_LEFT, 0) ?: 0, intent?.getIntExtra(EXTRA_MARGIN_BOTTOM, 0) ?: 0),
            DashMarginColor.fromName(intent?.getStringExtra(EXTRA_MARGIN_COLOR)))
        else startMirrorSession(quality, fps)
        return START_NOT_STICKY
    }

    private fun startMirrorSession(quality: Int, fps: Int) {
        val data = resultData
        if (resultCode == 0 || data == null) {
            fail("Screen capture not granted — tap Start again")
            return
        }
        try {
            startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: SecurityException) {
            resultCode = 0; resultData = null
            fail("Screen capture expired — tap Start again")
            return
        }
        val projection = try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "MediaProjection failed", t)
            null
        } finally {
            resultCode = 0; resultData = null
        }
        if (projection == null) {
            fail("Screen capture denied")
            return
        }
        runEngine(MediaProjectionScreenSource(this, projection, quality) {
            // The capture token is single-use: a system revocation must end the
            // mirror session, not leave RFCOMM connected awaiting frames forever.
            if (!stopping && _mode.value == CastMode.MIRROR) {
                // Do not wait for Service.onDestroy to release RFCOMM: interrupt an
                // in-flight handshake or IMAGE_ACK as soon as Android revokes capture.
                // A mere frame gap (with no onStop callback) does not reach this path.
                engine?.stop()
                stopSelf()
            }
        }, fps)
    }

    private fun startDashSession(quality: Int, fps: Int, resolution: DashResolution, component: String?, dpi: Int, margins: DashMargins, marginColor: DashMarginColor) {
        if (component.isNullOrBlank()) {
            fail("Select an app to display in Settings")
            return
        }
        try {
            startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (t: Throwable) {
            fail("VD foreground service unavailable")
            return
        }
        val source = DashStreamScreenSource(dpi, margins, marginColor)
        dashSource = source
        val modeSource = DashModeScreenSource(source)
        dashModeSource = modeSource
        dashTarget = component
        registerDashScreenReceiver()
        runEngine(modeSource, fps, retryInitialConnection = true)
        scope.launch(Dispatchers.IO) {
            runCatching { DashHelper.ensureRunning(this@CaptureService, quality, resolution, preferExisting = true) }
                .onSuccess {
                    if (!stopping && _mode.value == CastMode.DASH) {
                        DashHelper.startWatchdog(this@CaptureService, quality, resolution)
                        // If Start happens while already locked, no SCREEN_OFF edge will arrive.
                        val power = getSystemService(PowerManager::class.java)
                        if (power?.isInteractive == false && dashPromotedAtMs == 0L) promoteDash("already asleep at Start")
                    }
                }
                .onFailure {
                    if (!stopping) {
                        Log.e(TAG, "VD helper failed", it)
                        fail(it.message ?: "VD helper unavailable")
                    }
                }
        }
    }


    /**
     * v40's phone power/keyguard state machine.  A real POWER press during an
     * active promotion must DEMOTE and wake the system, not power down the VD.
     * The 2.5-second settle guard ignores synthetic screen edges from the helper.
     */
    private fun registerDashScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                scope.launch(Dispatchers.IO) {
                    if (stopping || _mode.value != CastMode.DASH) return@launch
                    when (action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            val promoted = dashPromotedAtMs
                            if (promoted == 0L) promoteDash("SCREEN_OFF")
                            else if (System.currentTimeMillis() - promoted >= PROMOTE_GRACE_MS) {
                                demoteDash("POWER during PROMOTE")
                            } else {
                                Log.d(TAG, "dash: ignoring synthetic SCREEN_OFF during settle")
                            }
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            val promoted = dashPromotedAtMs
                            if (promoted != 0L) {
                                val age = System.currentTimeMillis() - promoted
                                if (age >= PROMOTE_GRACE_MS) demoteDash("SCREEN_ON")
                                else {
                                    // Wake generated by the promotion itself. Wait for lock
                                    // state to settle before taking action.
                                    val generation = promotionGeneration
                                    delay(PROMOTE_GRACE_MS - age + 150L)
                                    if (generation == promotionGeneration && dashPromotedAtMs != 0L &&
                                        dashSawLockedKeyguard && !isKeyguardLockedNow()) demoteDash("unlock settled")
                                }
                            }
                        }
                        Intent.ACTION_USER_PRESENT -> demoteDash("USER_PRESENT", force = true)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    private fun isKeyguardLockedNow(): Boolean {
        val manager = getSystemService(KeyguardManager::class.java) ?: return false
        return manager.isKeyguardLocked || manager.isDeviceLocked
    }

    @Synchronized
    private fun promoteDash(reason: String) {
        if (stopping || dashPromotedAtMs != 0L || _mode.value != CastMode.DASH) return
        val component = dashTarget ?: return
        val at = System.currentTimeMillis()
        dashPromotedAtMs = at
        val generation = ++promotionGeneration
        dashSawLockedKeyguard = isKeyguardLockedNow()
        dashModeSource?.promote(component)
        scope.launch(Dispatchers.IO) {
            delay(250L)
            if (generation == promotionGeneration && isKeyguardLockedNow()) dashSawLockedKeyguard = true
        }
        scope.launch(Dispatchers.IO) {
            delay(PROMOTE_GRACE_MS + 150L)
            if (generation != promotionGeneration || stopping) return@launch
            val interactive = getSystemService(PowerManager::class.java)?.isInteractive
            if (interactive == false) demoteDash("post-promote sleep recovery")
        }
    }

    @Synchronized
    private fun demoteDash(reason: String, force: Boolean = false) {
        if (stopping || (!force && dashPromotedAtMs == 0L)) return
        dashPromotedAtMs = 0L
        promotionGeneration++
        dashSawLockedKeyguard = false
        dashModeSource?.demote()
    }


    private fun runEngine(
        screen: ScreenSource,
        maxFps: Int,
        retryInitialConnection: Boolean = false,
    ) {
        val mirror = MirrorEngine(RfcommByteChannel(), screen, maxFps, retryInitialConnection = retryInitialConnection)
        engine = mirror
        scope.launch {
            mirror.state.collect { state ->
                _state.value = state
                when (state) {
                    is MirrorState.Streaming -> updateNotification("Streaming — ${state.kbPerFrame} KB/frame")
                    is MirrorState.Error -> { updateNotification(state.message); stopSelf() }
                    else -> {}
                }
            }
        }
        mirror.start(scope)
    }

    private fun dashResolutionFrom(intent: Intent?): DashResolution {
        val width = intent?.getIntExtra(EXTRA_DASH_WIDTH, DashResolution.DEFAULT.width)
            ?: DashResolution.DEFAULT.width
        val height = intent?.getIntExtra(EXTRA_DASH_HEIGHT, DashResolution.DEFAULT.height)
            ?: DashResolution.DEFAULT.height
        return DashResolution.values().firstOrNull { it.width == width && it.height == height }
            ?: DashResolution.DEFAULT
    }

    private fun killHelper() {
        runCatching { PillionAdb.getInstance(this).runShell("pkill -f app.pillion.server.DashServer") }
    }

    private fun fail(message: String) {
        if (stopping) return
        _state.value = MirrorState.Error(message)
        stopSelf()
    }

    override fun onDestroy() {
        if (!ownsSession) {
            // ACTION_STOP or a rejected Start may create a short-lived Service instance.
            // It must not unlock the previous session's still-running cleanup.
            scope.cancel()
            super.onDestroy()
            return
        }
        stopping = true
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        dashPromotedAtMs = 0L
        promotionGeneration++
        engine?.stop()
        DashHelper.stopWatchdog()
        scope.cancel()
        releaseWakeLock()
        val source = dashSource
        Thread({
            try {
                // First stop the image source, then let the original connect/Helper tasks finish.
                runCatching { source?.quit() }
                runBlocking { scope.coroutineContext[Job]?.join() }
                if (source != null) {
                    // ensureRunning() uses this same monitor. Never kill a newly spawned helper
                    // before the old startup operation has completed.
                    synchronized(DashHelper) {
                        killHelper()
                        if (!DashHelper.waitUntilStopped()) {
                            Log.w(TAG, "dash: helper exit timeout; releasing session lock")
                        }
                    }
                }
            } finally {
                _state.value = MirrorState.Idle
                _mode.value = CastMode.NONE
            }
        }, "pillion-session-cleanup").start()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Keep the CPU running so the Bluetooth stream survives screen dimming / Doze during a ride. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pillion:mirror").apply {
            setReferenceCounted(false)
            acquire(MAX_SESSION_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Foreground with the right service type: mediaProjection for mirror, connectedDevice for dash. */
    private fun startForegroundTyped(type: Int) {
        val notification = buildNotification("Connecting to dash…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pillion", NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Pillion — mirroring to dash")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Pillion — mirroring to dash")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    companion object {
        private const val PROMOTE_GRACE_MS = 2500L
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "pillion"
        private const val TAG = "Pillion"
        private const val MAX_SESSION_MS = 3L * 60 * 60 * 1000 // 3h safety cap
        const val ACTION_STOP = "app.pillion.action.STOP"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_MAX_FPS = "maxFps"
        /** When true, the session switches to the dedicated dash display whenever the phone is locked. */
        const val EXTRA_DASH_ENABLED = "dashEnabled"
        const val EXTRA_DASH_COMPONENT = "dashComponent"
        const val EXTRA_DASH_DPI = "dashDpi"
        const val EXTRA_MARGIN_LEFT = "marginLeft"
        const val EXTRA_MARGIN_BOTTOM = "marginBottom"
        const val EXTRA_MARGIN_COLOR = "marginColor"
        const val EXTRA_DASH_WIDTH = "dashWidth"
        const val EXTRA_DASH_HEIGHT = "dashHeight"

        // Handed over by the Activity after the user grants screen capture.
        @Volatile var resultCode: Int = 0
        @Volatile var resultData: Intent? = null

        private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
        val state: StateFlow<MirrorState> = _state.asStateFlow()
        private val _mode = MutableStateFlow(CastMode.NONE)
        val mode: StateFlow<CastMode> = _mode.asStateFlow()
    }
}
