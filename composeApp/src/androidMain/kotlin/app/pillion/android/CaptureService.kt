package app.pillion.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.pillion.core.MirrorEngine
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be foreground before acquiring the projection (Android 10+).
        startForeground(NOTIF_ID, buildNotification("Connecting to dash…"))
        acquireWakeLock()
        _state.value = MirrorState.Connecting

        val data = resultData
        if (resultCode == 0 || data == null) {
            _state.value = MirrorState.Error("screen capture not granted")
            stopSelf(); return START_NOT_STICKY
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data)
        if (projection == null) {
            _state.value = MirrorState.Error("screen capture denied")
            stopSelf(); return START_NOT_STICKY
        }

        val quality = intent?.getIntExtra(EXTRA_QUALITY, 40) ?: 40
        val maxFps = intent?.getIntExtra(EXTRA_MAX_FPS, 15) ?: 15
        // Create the virtual display NOW, while the projection token is fresh — deferring it behind
        // the Bluetooth handshake makes Android 14/15 invalidate the projection (blank dash).
        val screen = MediaProjectionScreenSource(this, projection, quality)
        runCatching { screen.start() }
        val mirror = MirrorEngine(RfcommByteChannel(), screen, maxFps)
        engine = mirror
        scope.launch {
            mirror.state.collect { state ->
                _state.value = state
                when (state) {
                    is MirrorState.Streaming -> updateNotification("Mirroring — ${state.kbPerFrame} KB/frame")
                    is MirrorState.Error -> { updateNotification(state.message); stopSelf() }
                    else -> {}
                }
            }
        }
        mirror.start(scope)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
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
        private const val MAX_SESSION_MS = 3L * 60 * 60 * 1000 // 3h safety cap
        const val ACTION_STOP = "app.pillion.action.STOP"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_MAX_FPS = "maxFps"

        // Handed over by the Activity after the user grants screen capture.
        @Volatile var resultCode: Int = 0
        @Volatile var resultData: Intent? = null

        private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
        val state: StateFlow<MirrorState> = _state.asStateFlow()
    }
}
