package app.pillion.android.sdl

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import app.pillion.android.PillionAdb
import app.pillion.core.Logger

/**
 * Bike-button -> keyevent glue for the mirror path. SdlService's ON_BUTTON_PRESS notification calls
 * dispatchBikeButton(); the phone is the UI here, so keys are actuated *locally*: when Pillion's own
 * in-app ADB session to the phone's adbd ([PillionAdb], the same channel the dash bootstrap uses) is
 * alive, we run `input keyevent <code>` through it; otherwise we fall back to log-only. No GAL/AA
 * dependency — the AA relay path is a separate flavour and not part of this build.
 *
 * SDL notifications arrive on the main thread; the adb shell call is socket I/O, so everything is
 * hopped onto our own handler thread (which also serializes key order).
 */
object BikeInputBridge {
    @Volatile private var appContext: Context? = null

    private val thread = HandlerThread("BikeInput").also { it.start() }
    private val h = Handler(thread.looper)

    // KeyEvent codes for `input keyevent` (the mapping the GAL relay used, kept identical).
    private const val KEY_HOME = 3
    private const val KEY_BACK = 4
    private const val KEY_DPAD_UP = 19
    private const val KEY_DPAD_DOWN = 20
    private const val KEY_DPAD_LEFT = 21
    private const val KEY_DPAD_RIGHT = 22
    private const val KEY_DPAD_CENTER = 23

    /** SdlService.onCreate wires the app context used to reach the in-app ADB channel. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** One physical press -> one synthetic key tap. Returns false if the name isn't mapped. */
    fun dispatchBikeButton(name: String?, mode: String?): Boolean {
        val long = mode == "LONG"
        val code = when (name) {
            // joystick directions (bike names per our CCU firmware hmi_capabilities)
            "TUNEUP" -> KEY_DPAD_UP
            "TUNEDOWN" -> KEY_DPAD_DOWN
            "SEEKLEFT" -> KEY_DPAD_LEFT
            "SEEKRIGHT" -> KEY_DPAD_RIGHT
            // joystick press
            "OK" -> KEY_DPAD_CENTER
            // dedicated back/home key: short = back, long = home
            "CUSTOM_BUTTON", "SEARCH" -> if (long) KEY_HOME else KEY_BACK
            else -> return false
        }
        h.post { sendKey(code, name) }
        return true
    }

    private fun sendKey(code: Int, name: String) {
        val ctx = appContext ?: return
        val adb = PillionAdb.getInstance(ctx)
        if (!adb.isConnected()) {
            Logger.d("sdl BIKE BUTTON $name: no live ADB session — log-only (pair/connect in ADB bootstrap to actuate)")
            return
        }
        runCatching { adb.runShell("input keyevent $code") }
            .onSuccess { Logger.d("sdl BIKE BUTTON $name -> local input keyevent $code") }
            .onFailure { Logger.d("sdl BIKE BUTTON $name: keyevent $code failed: ${it.message}") }
    }
}
