package app.pillion.server

import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Privileged helper for the dedicated-dash feature, run as the **shell uid** via `app_process`
 * (spawned through Pillion's in-app ADB bootstrap). Because it runs as shell it can create a
 * **trusted** virtual display — the one thing a normal app uid cannot do — launch a real app onto
 * it, capture it, and stream it back to the app, so the dash keeps rendering with the phone screen
 * off.
 *
 * Pipeline: trusted VirtualDisplay -> ImageReader -> JPEG -> [4-byte big-endian length][bytes]
 * frames over an **abstract** LocalServerSocket ([SOCKET_NAME]). The socket is in the abstract
 * namespace, so the app (a different uid) can connect by name, and it survives Wi-Fi dropping
 * because it never touches the network — the ADB connection is only used to *spawn* this process.
 *
 * It is NOT an Android component: no Application/Activity context, so it gets a system context via
 * [android.app.ActivityThread] reflection and talks to the framework directly (the scrcpy approach).
 *
 * Launch (via the ADB shell):
 *   CLASSPATH=<base.apk> app_process / app.pillion.server.DashServer <w> <h> <dpi> <quality> <component>
 */
object DashServer {

    const val SOCKET_NAME = "pillion_dash"

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

    private var width = 480
    private var height = 240
    private var quality = 40
    private const val MIN_INTERVAL_MS = 50L // cap capture/encode to ~20fps; the app paces sends

    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var latestSeq = 0L
    @Volatile private var lastEncodeMs = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.myLooper() == null) Looper.prepareMainLooper()

        width = args.getOrNull(0)?.toIntOrNull() ?: 480
        height = args.getOrNull(1)?.toIntOrNull() ?: 240
        val dpi = args.getOrNull(2)?.toIntOrNull() ?: 160
        quality = args.getOrNull(3)?.toIntOrNull() ?: 40
        val launchComponent = args.getOrNull(4) // e.g. com.waze/com.waze.FreeMapAppActivity

        try {
            val context = ShellContext(systemContext())
            val captureThread = HandlerThread("pillion-capture").apply { start() }
            val handler = Handler(captureThread.looper)

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ ir -> onImage(ir) }, handler)

            val display = createTrustedVirtualDisplay(context, "pillion-dash", width, height, dpi, reader.surface)
            out("PILLION_DISPLAY_ID=${display.display.displayId}")

            if (launchComponent != null) {
                val exit = exec(
                    "am", "start", "--display", display.display.displayId.toString(),
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                    "-n", launchComponent,
                )
                out("PILLION_LAUNCH_EXIT=$exit")
            }

            startSocketServer()
            out("PILLION_READY")
        } catch (t: Throwable) {
            out("PILLION_ERROR=${t.javaClass.simpleName}: ${t.message}")
            return
        }
        Looper.loop()
    }

    /** Encode the newest frame to JPEG, throttled to [MIN_INTERVAL_MS]. */
    private fun onImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastEncodeMs < MIN_INTERVAL_MS) return
            lastEncodeMs = now
            latestJpeg = toJpeg(image)
            latestSeq++
        } catch (_: Throwable) {
            // drop this frame
        } finally {
            image.close()
        }
    }

    private fun toJpeg(image: Image): ByteArray {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(
            width + if (pixelStride > 0) rowPadding / pixelStride else 0, height, Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val bitmap = if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Accept one client at a time and push each new JPEG frame as [4-byte length][bytes]. */
    private fun startSocketServer() {
        val server = LocalServerSocket(SOCKET_NAME)
        Thread {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                serveClient(client)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun serveClient(client: LocalSocket) {
        try {
            val out = DataOutputStream(client.outputStream)
            var sentSeq = -1L
            while (true) {
                val seq = latestSeq
                val frame = latestJpeg
                if (frame != null && seq != sentSeq) {
                    out.writeInt(frame.size)
                    out.write(frame)
                    out.flush()
                    sentSeq = seq
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (_: Throwable) {
            // client gone; loop back to accept()
        } finally {
            runCatching { client.close() }
        }
    }

    /** A system Context with no Application — the only way to get one in a bare app_process. */
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val systemMain = activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
    }

    /**
     * Create a trusted virtual display. The public [createVirtualDisplay] only accepts a flags int on
     * a [DisplayManager] instance built with a Context (its constructor is @hide), so we build one via
     * reflection — exactly what scrcpy does. The system grants the trusted/public flags because our
     * process runs as the shell uid (presented by [ShellContext]).
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
        process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()
        return process.waitFor()
    }

    private fun out(line: String) {
        println(line)
        System.out.flush()
    }

    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"

    /** Reports the shell identity so framework ownership checks pass. Mirrors scrcpy's FakeContext. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE

        @TargetApi(31)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    }
}
