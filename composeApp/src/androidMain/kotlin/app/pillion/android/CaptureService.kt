package app.pillion.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import app.pillion.core.DashResolution
import app.pillion.core.MirrorEngine
import app.pillion.core.ScreenSource
import app.pillion.core.MirrorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private var dashSwitch: SwitchableScreenSource? = null
    private var screenReceiver: BroadcastReceiver? = null
    @Volatile private var dashPromotedAtMs = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        _state.value = MirrorState.Connecting

        val quality = intent?.getIntExtra(EXTRA_QUALITY, 40) ?: 40
        val maxFps = intent?.getIntExtra(EXTRA_MAX_FPS, 15) ?: 15
        val dashResolution = dashResolutionFrom(intent)
        dashEnabled = intent?.getBooleanExtra(EXTRA_DASH_ENABLED, false) ?: false
        startSession(quality, maxFps, dashResolution)
        return START_NOT_STICKY
    }

    /**
     * One Bluetooth/NaviLite session. It always mirrors the phone via MediaProjection; when dash mode
     * is enabled it also spawns the privileged helper and switches the engine's source to the dash
     * (foreground app on a trusted display) whenever the phone is locked — **mirror while unlocked,
     * dash while locked**. The helper only encodes while locked, so it costs no extra battery idle.
     */
    private fun startSession(quality: Int, maxFps: Int, dashResolution: DashResolution) {
        // Must be foreground (mediaProjection) before acquiring the projection (Android 10+).
        startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val data = resultData
        if (resultCode == 0 || data == null) {
            fail("screen capture not granted"); return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: run {
            fail("screen capture denied"); return
        }
        // Create the mirror display NOW, while the projection token is fresh.
        val mirror = MediaProjectionScreenSource(this, projection, quality)
        runCatching { mirror.start() }

        val source: ScreenSource = if (dashEnabled) {
            val switch = SwitchableScreenSource(mirror, DashStreamScreenSource())
            dashSwitch = switch
            // Spawn the helper once (needs the ADB connection); it serves over loopback afterwards.
            scope.launch(Dispatchers.IO) {
                runCatching { spawnHelper(quality, dashResolution) }
                    .onFailure { Log.e(TAG, "dash: helper spawn failed", it) }
            }
            registerScreenReceiver()
            switch
        } else {
            mirror
        }
        runEngine(source, maxFps)
    }

    /** Mirror while unlocked, dash while locked: promote the foreground app on lock, demote on unlock. */
    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val action = intent.action
                // onReceive runs on the main thread; promote/demote write to the helper socket, which
                // is network I/O (NetworkOnMainThreadException otherwise), so do it off the main thread.
                scope.launch(Dispatchers.IO) {
                    when (action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            if (dashPromotedAtMs != 0L) {
                                Log.d(TAG, "dash: screen off while already promoted; keeping dash active")
                                return@launch
                            }
                            val component = foregroundComponent()
                            Log.d(TAG, "dash: screen off; foreground=$component")
                            if (component == null) {
                                Log.w(TAG, "dash: no foreground app; usage access may be missing")
                            } else {
                                dashSwitch?.promote(component)
                                dashPromotedAtMs = System.currentTimeMillis()
                            }
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            val promotedAt = dashPromotedAtMs
                            if (promotedAt != 0L) {
                                val ageMs = System.currentTimeMillis() - promotedAt
                                if (ageMs >= RETURN_TO_PHONE_GRACE_MS) {
                                    Log.d(TAG, "dash: screen on; returning to phone")
                                    dashPromotedAtMs = 0L
                                    dashSwitch?.demote()
                                } else {
                                    // The helper briefly wakes the device during PROMOTE so the virtual display
                                    // can render. Ignore that synthetic wake; panel-off retries will blank display 0.
                                    Log.d(TAG, "dash: ignoring promotion wake (${ageMs}ms)")
                                }
                            }
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            Log.d(TAG, "dash: user present; demoting")
                            dashPromotedAtMs = 0L
                            dashSwitch?.demote()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    /** The foreground app's launcher component to promote at lock (excludes Pillion). */
    private fun foregroundComponent(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 60_000, now) }
            .onFailure { Log.w(TAG, "dash: usage query failed", it) }
            .getOrNull() ?: return null
        val event = UsageEvents.Event()
        var component: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName != packageName) {
                val candidate = packageManager.getLaunchIntentForPackage(event.packageName)
                    ?.component
                    ?.flattenToString()
                if (candidate == null) {
                    Log.d(TAG, "dash: ignoring foreground package without launcher: ${event.packageName}")
                } else {
                    component = candidate
                    Log.d(TAG, "dash: foreground candidate=$component")
                }
            }
        }
        if (component == null) Log.w(TAG, "dash: UsageStats returned no launchable foreground app")
        return component
    }

    private fun runEngine(screen: ScreenSource, maxFps: Int) {
        val mirror = MirrorEngine(RfcommByteChannel(), screen, maxFps)
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

    private fun spawnHelper(quality: Int, dashResolution: DashResolution) {
        // No app argument: the helper creates the trusted display idle and waits for PROMOTE on lock.
        // Keep dpi at 160 so larger presets expose more dp to nav apps, then scale to the TFT.
        val adb = PillionAdb.getInstance(this)
        val connected = runCatching { adb.autoConnectDevice(this, timeoutMs = 15_000) }
            .onSuccess { Log.d(TAG, "dash: adb auto-connect=$it") }
            .onFailure { Log.w(TAG, "dash: adb auto-connect failed", it) }
            .getOrDefault(false)
        check(connected) { "Wireless debugging is not connected" }
        runCatching { adb.prepareDashPrivileges(this) }
            .onSuccess { Log.d(TAG, "dash: granted usage-stats appop through shell") }
            .onFailure { Log.w(TAG, "dash: could not grant usage-stats appop", it) }
        // Clear any stale helper from a previous session while ADB is available.
        runCatching { adb.runShell("pkill -f app.pillion.server.DashServer") }
        Log.d(
            TAG,
            "dash: spawning helper virtual=${dashResolution.width}x${dashResolution.height} " +
                "output=${DASH_PROTOCOL_WIDTH}x$DASH_PROTOCOL_HEIGHT",
        )
        // Detach the helper so it survives ADB disconnect AND wifi loss — the same outcome as
        // Shizuku's native starter, achieved here with two cooperating tricks (both required,
        // verified on Pixel 9a / GrapheneOS):
        //   1. `setsid` puts the helper in a NEW session/process-group, so adbd's process-group
        //      SIGKILL on stream close (the `exec:`/`shell:` teardown) can't reach it.
        //   2. the wrapping shell backgrounds app_process (`&`) and exits, so the helper reparents
        //      to init (pid 1) — adbd only reaps its own direct children.
        // Without setsid the backgrounded helper stays in the doomed group and dies; without the
        // orphan it stays a direct child of adbd and dies. After spawn the helper serves frames over
        // loopback (127.0.0.1:28115) and the app drives PROMOTE/DEMOTE/QUIT over that same socket, so
        // no network is needed for the rest of the session — this is what makes the no-wifi ride work.
        // Include SYSTEMSERVERCLASSPATH so the helper can load com.android.server.display.DisplayControl
        // on its main classloader (needed to turn the phone's panel off via SurfaceControl while keeping
        // the device awake — see DashServer.mainDisplayToken).
        val inner = "CLASSPATH=\$(pm path $packageName | grep base.apk | cut -d: -f2):\$SYSTEMSERVERCLASSPATH " +
            "app_process / app.pillion.server.DashServer " +
            "${dashResolution.width} ${dashResolution.height} 160 $quality " +
            "$DASH_PROTOCOL_WIDTH $DASH_PROTOCOL_HEIGHT " +
            "</dev/null >/dev/null 2>&1 &"
        val stream = adb.openShellStream("setsid sh -c '$inner'")
        // The wrapper shell exits as soon as it backgrounds the helper, so this returns quickly; the
        // helper is already detached by then. Fire-and-forget — we don't hold the stream open.
        runCatching { stream.openInputStream().readBytes() }
            .onFailure { t ->
                if (t.message?.contains("Stream closed", ignoreCase = true) != true) throw t
            }
        runCatching { stream.close() }
        Log.d(TAG, "dash: helper spawned (detached to init)")
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
        _state.value = MirrorState.Error(message)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.stop()
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        // The helper is detached (orphaned to init), so it won't die on its own. Tell it to QUIT over
        // loopback — works with no network — to release the trusted display; pkill over ADB is a
        // best-effort backup (only reachable while wifi is up). Detached thread: must outlive cancel().
        val switch = dashSwitch
        if (dashEnabled) Thread {
            runCatching { switch?.quit() }
            killHelper()
        }.start()
        scope.cancel()
        releaseWakeLock()
        _state.value = MirrorState.Idle
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
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "pillion"
        private const val TAG = "Pillion"
        private const val MAX_SESSION_MS = 3L * 60 * 60 * 1000 // 3h safety cap
        private const val RETURN_TO_PHONE_GRACE_MS = 3_000L
        private const val DASH_PROTOCOL_WIDTH = 480
        private const val DASH_PROTOCOL_HEIGHT = 240
        const val ACTION_STOP = "app.pillion.action.STOP"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_MAX_FPS = "maxFps"
        /** When true, the session switches to the dedicated dash display whenever the phone is locked. */
        const val EXTRA_DASH_ENABLED = "dashEnabled"
        const val EXTRA_DASH_WIDTH = "dashWidth"
        const val EXTRA_DASH_HEIGHT = "dashHeight"

        // Handed over by the Activity after the user grants screen capture.
        @Volatile var resultCode: Int = 0
        @Volatile var resultData: Intent? = null

        private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
        val state: StateFlow<MirrorState> = _state.asStateFlow()
    }
}
