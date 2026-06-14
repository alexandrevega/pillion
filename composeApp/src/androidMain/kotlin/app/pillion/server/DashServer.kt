package app.pillion.server

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Looper
import android.view.Surface

/**
 * Privileged helper for the dedicated-dash feature, run as the **shell uid** via `app_process`
 * (spawned through Pillion's in-app ADB bootstrap). Because it runs as shell it can create a
 * **trusted** virtual display — the one thing a normal app uid cannot do — and launch a real app
 * onto it, so the dash keeps rendering with the phone screen off.
 *
 * It is NOT an Android component: it has no Application/Activity context, so it obtains a system
 * context via [ActivityThread] reflection and talks to the framework directly (the scrcpy approach).
 *
 * Milestone 2a (this version): create the trusted display + launch the app, print status, stay
 * alive. Capture (ImageReader -> JPEG) and the local-socket stream land in the next increment.
 *
 * Launch (via the ADB shell):
 *   CLASSPATH=<base.apk> app_process / app.pillion.server.DashServer <w> <h> <dpi> <launchComponent>
 */
object DashServer {

    // Public flags exist on DisplayManager; the trusted/own-group/always-unlocked ones are @hide,
    // so use their raw bit values (verified against scrcpy + dumpsys on Android 16).
    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_PRESENTATION = 1 shl 1
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13

    private const val FLAGS = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or
        FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
        FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED

    @JvmStatic
    fun main(args: Array<String>) {
        // Some framework calls post to the calling thread's Looper.
        if (Looper.myLooper() == null) Looper.prepareMainLooper()

        val width = args.getOrNull(0)?.toIntOrNull() ?: 480
        val height = args.getOrNull(1)?.toIntOrNull() ?: 240
        val dpi = args.getOrNull(2)?.toIntOrNull() ?: 160
        val launchComponent = args.getOrNull(3) // e.g. com.waze/com.waze.FreeMapAppActivity

        try {
            val context = systemContext()
            // ImageReader gives the display a real output surface (we'll read it in the next step).
            val reader = ImageReader.newInstance(width, height, /* RGBA_8888 */ 1, 2)
            val display = createTrustedVirtualDisplay(context, "pillion-dash", width, height, dpi, reader.surface)
            val displayId = display.display.displayId
            out("PILLION_DISPLAY_ID=$displayId")

            if (launchComponent != null) {
                val exit = exec(
                    "am", "start", "--display", displayId.toString(),
                    "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER",
                    "-n", launchComponent,
                )
                out("PILLION_LAUNCH_EXIT=$exit")
            }
            out("PILLION_READY")
        } catch (t: Throwable) {
            out("PILLION_ERROR=${t.javaClass.simpleName}: ${t.message}")
            return
        }

        // Stay alive so the display persists; the parent kills us by closing the ADB stream.
        Looper.loop()
    }

    /** A system Context with no Application — the only way to get one in a bare app_process. */
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val systemMain = activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
    }

    /**
     * Create a trusted virtual display. The public [createVirtualDisplay] only accepts a flags int
     * when called on a [DisplayManager] instance built with a Context (its constructor is @hide), so
     * we build one via reflection — exactly what scrcpy does. The system grants the trusted/public
     * flags because our process runs as the shell uid.
     */
    private fun createTrustedVirtualDisplay(
        context: Context, name: String, width: Int, height: Int, dpi: Int, surface: Surface,
    ): VirtualDisplay {
        val dmClass = android.hardware.display.DisplayManager::class.java
        val ctor = dmClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val dm = ctor.newInstance(context)
        return dm.createVirtualDisplay(name, width, height, dpi, surface, FLAGS)
    }

    private fun exec(vararg command: String): Int {
        val process = Runtime.getRuntime().exec(command)
        process.inputStream.bufferedReader().readText() // drain so the pipe doesn't block
        process.errorStream.bufferedReader().readText()
        return process.waitFor()
    }

    private fun out(line: String) {
        println(line)
        System.out.flush()
    }
}
