package app.pillion.android

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Mirror while unlocked, dash while locked: promotes the foreground app to the dedicated dash display
 * when the screen goes off and returns it to the phone on unlock. [target] consumes the dash frames.
 */
class DashLockSwitcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val target: DashSwitch,
    /** True while the app must stay on the dash after unlock (e.g. the phone's own capture has ended). */
    private val holdOnDash: () -> Boolean = { false },
    /** App to promote when UsageStats can't name the foreground one (e.g. the dash app from Settings). */
    private val fallbackComponent: () -> String? = { null },
) {
    private var screenReceiver: BroadcastReceiver? = null
    private var keyguardManager: KeyguardManager? = null
    private var keyguardListener: Any? = null
    @Volatile private var dashPromotedAtMs = 0L
    @Volatile private var dashSawLockedKeyguard = false

    fun start() {
        registerScreenReceiver()
        registerKeyguardUnlockListener()
    }

    fun stop() {
        screenReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenReceiver = null
        unregisterKeyguardUnlockListener()
    }

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
                            val component = foregroundComponent() ?: fallbackComponent()
                            Log.d(TAG, "dash: screen off; foreground=$component")
                            if (component == null) {
                                Log.w(TAG, "dash: no foreground app; usage access may be missing")
                            } else {
                                target.promote(component)
                                dashPromotedAtMs = System.currentTimeMillis()
                                dashSawLockedKeyguard = isKeyguardLockedNow()
                                scheduleLockStateSample(dashPromotedAtMs)
                            }
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            val promotedAt = dashPromotedAtMs
                            if (promotedAt != 0L) {
                                val ageMs = System.currentTimeMillis() - promotedAt
                                if (ageMs >= RETURN_TO_PHONE_GRACE_MS) {
                                    returnToPhone("screen on")
                                } else {
                                    // The helper briefly wakes the device during PROMOTE so the virtual display
                                    // can render. Ignore that synthetic wake; panel-off retries will blank display 0.
                                    Log.d(TAG, "dash: ignoring promotion wake (${ageMs}ms)")
                                    schedulePromotionWakeSettleCheck(promotedAt, ageMs)
                                }
                            }
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            returnToPhone("user present", force = true)
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
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    /**
     * Some devices do not send a useful SCREEN_ON transition after we blank display 0 directly.
     * Keyguard unlock is the higher-signal event, but the listener is API 33+ and permission-gated,
     * so it is registered defensively and the normal broadcasts remain as fallbacks.
     */
    private fun registerKeyguardUnlockListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (context.checkSelfPermission(PERMISSION_SUBSCRIBE_KEYGUARD) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "dash: keyguard unlock listener unavailable; permission not granted")
            return
        }
        runCatching {
            val manager = context.getSystemService(KeyguardManager::class.java)
                ?: error("KeyguardManager unavailable")
            val listenerClass = Class.forName("android.app.KeyguardManager\$KeyguardLockedStateListener")
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method.name == "onKeyguardLockedStateChanged") {
                    val locked = args?.firstOrNull() as? Boolean ?: return@newProxyInstance null
                    scope.launch(Dispatchers.IO) {
                        if (locked) {
                            if (dashPromotedAtMs != 0L) dashSawLockedKeyguard = true
                            Log.d(TAG, "dash: keyguard locked")
                        } else {
                            onKeyguardUnlocked()
                        }
                    }
                }
                null
            }
            val executor = Executor { runnable -> scope.launch(Dispatchers.IO) { runnable.run() } }
            manager.javaClass
                .getMethod("addKeyguardLockedStateListener", Executor::class.java, listenerClass)
                .invoke(manager, executor, listener)
            keyguardManager = manager
            keyguardListener = listener
            Log.d(TAG, "dash: keyguard unlock listener registered")
        }.onFailure {
            Log.d(TAG, "dash: keyguard unlock listener unavailable: ${it.javaClass.simpleName}")
            keyguardManager = null
            keyguardListener = null
        }
    }

    private fun unregisterKeyguardUnlockListener() {
        val manager = keyguardManager ?: return
        val listener = keyguardListener ?: return
        runCatching {
            val listenerClass = Class.forName("android.app.KeyguardManager\$KeyguardLockedStateListener")
            manager.javaClass
                .getMethod("removeKeyguardLockedStateListener", listenerClass)
                .invoke(manager, listener)
        }
        keyguardManager = null
        keyguardListener = null
    }

    private fun onKeyguardUnlocked() {
        val promotedAt = dashPromotedAtMs
        if (promotedAt == 0L) return
        val ageMs = System.currentTimeMillis() - promotedAt
        if (dashSawLockedKeyguard || ageMs >= RETURN_TO_PHONE_GRACE_MS) {
            returnToPhone("keyguard unlocked")
        } else {
            Log.d(TAG, "dash: ignoring keyguard-unlocked callback before lock settled (${ageMs}ms)")
        }
    }

    private fun scheduleLockStateSample(promotedAt: Long) {
        scope.launch(Dispatchers.IO) {
            Thread.sleep(LOCK_STATE_SAMPLE_DELAY_MS)
            if (dashPromotedAtMs == promotedAt && isKeyguardLockedNow()) {
                dashSawLockedKeyguard = true
                Log.d(TAG, "dash: keyguard locked after promotion")
            }
        }
    }

    private fun schedulePromotionWakeSettleCheck(promotedAt: Long, ageMs: Long) {
        val delayMs = (RETURN_TO_PHONE_GRACE_MS - ageMs).coerceAtLeast(0L) + 150L
        scope.launch(Dispatchers.IO) {
            Thread.sleep(delayMs)
            if (dashPromotedAtMs != promotedAt) return@launch
            if (dashSawLockedKeyguard && isKeyguardUnlockedNow()) {
                returnToPhone("unlock after promotion wake")
            } else {
                Log.d(TAG, "dash: promotion wake settled; keeping dash active")
            }
        }
    }

    private fun returnToPhone(reason: String, force: Boolean = false) {
        if (!force && dashPromotedAtMs == 0L) return
        if (!force && holdOnDash()) {
            Log.d(TAG, "dash: $reason; keeping the app on the dash (phone mirror unavailable)")
            return
        }
        Log.d(TAG, "dash: $reason; returning to phone")
        dashPromotedAtMs = 0L
        dashSawLockedKeyguard = false
        target.demote()
    }

    private fun isKeyguardLockedNow(): Boolean {
        val manager = context.getSystemService(KeyguardManager::class.java) ?: return false
        return manager.isDeviceLocked || manager.isKeyguardLocked
    }

    private fun isKeyguardUnlockedNow(): Boolean {
        val manager = context.getSystemService(KeyguardManager::class.java) ?: return false
        return !manager.isDeviceLocked && !manager.isKeyguardLocked
    }

    /** The foreground app's launcher component to promote at lock (excludes Pillion). */
    private fun foregroundComponent(): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 60_000, now) }
            .onFailure { Log.w(TAG, "dash: usage query failed", it) }
            .getOrNull() ?: return null
        val event = UsageEvents.Event()
        var component: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName != context.packageName) {
                val candidate = context.packageManager.getLaunchIntentForPackage(event.packageName)
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

    private companion object {
        const val TAG = "Pillion"
        const val RETURN_TO_PHONE_GRACE_MS = 3_000L
        const val LOCK_STATE_SAMPLE_DELAY_MS = 250L
        const val PERMISSION_SUBSCRIBE_KEYGUARD = "android.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE"
    }
}
