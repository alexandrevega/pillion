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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Privileged helper for the dedicated-dash feature, run as the **shell uid** via `app_process`
 * (spawned through Pillion's in-app ADB bootstrap). Because it runs as shell it can create a
 * **trusted** virtual display — the one thing a normal app uid cannot do — launch a real app onto
 * it, capture it, and stream it back to the app, so the dash keeps rendering with the phone screen
 * off.
 *
 * Pipeline: trusted VirtualDisplay -> ImageReader -> JPEG -> `[4-byte big-endian length][bytes]`
 * frames served over a **loopback TCP socket** ([PORT]). The app connects to `127.0.0.1:PORT` and
 * reads frames locally. We use loopback TCP rather than:
 *   - a unix LocalSocket: SELinux forbids untrusted_app -> shell (`avc: denied { connectto } ...
 *     tclass=unix_stream_socket`); TCP isn't subject to that domain rule.
 *   - the ADB exec stdout stream: that dies with the ADB/Wi-Fi connection, but there is no Wi-Fi on
 *     a moving bike. Loopback is always up, so once spawned (detached, via `nohup`) the helper keeps
 *     serving frames with no network at all.
 *
 * Status/diagnostics go to **logcat** (tag [TAG]).
 *
 * Launch detached so it outlives the spawning ADB connection:
 *   CLASSPATH=<base.apk> nohup app_process / app.pillion.server.DashServer \
 *     <virtual-w> <virtual-h> <dpi> <quality> <output-w> <output-h> <component> &
 */
object DashServer {

    private const val TAG = "PillionDash"
    const val PORT = 28115 // loopback frame port (app connects to 127.0.0.1:PORT)

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

    private const val MIN_INTERVAL_MS = 50L // cap capture/encode to ~20fps; the app paces sends

    private var virtualWidth = 480
    private var virtualHeight = 240
    private var outputWidth = 480
    private var outputHeight = 240
    private var quality = 40
    private var lastEncodeMs = 0L

    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var latestSeq = 0L

    // Battery: encode only while a foreground app is promoted (phone locked). Idle otherwise.
    @Volatile private var capturing = false
    @Volatile private var displayId = -1
    @Volatile private var lastComponent: String? = null
    @Volatile private var keepAlive: Thread? = null

    private const val KEEP_ALIVE_MS = 3000L // poke the dash display group well under its ~10s idle timeout

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.myLooper() == null) Looper.prepareMainLooper()

        virtualWidth = args.getOrNull(0)?.toIntOrNull() ?: 480
        virtualHeight = args.getOrNull(1)?.toIntOrNull() ?: 240
        val dpi = args.getOrNull(2)?.toIntOrNull() ?: 160
        quality = args.getOrNull(3)?.toIntOrNull() ?: 40
        val outputArg = args.getOrNull(4)?.toIntOrNull()
        outputWidth = outputArg ?: 480
        outputHeight = args.getOrNull(5)?.toIntOrNull()?.takeIf { outputArg != null } ?: 240
        val launchComponent = args.getOrNull(if (outputArg == null) 4 else 6)
        // launchComponent example: com.waze/com.waze.FreeMapAppActivity

        try {
            val context = ShellContext(systemContext())
            val captureThread = HandlerThread("pillion-capture").apply { start() }
            val handler = Handler(captureThread.looper)

            val reader = ImageReader.newInstance(virtualWidth, virtualHeight, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ ir -> onImage(ir) }, handler)

            val display = createTrustedVirtualDisplay(
                context,
                "pillion-dash",
                virtualWidth,
                virtualHeight,
                dpi,
                reader.surface,
            )
            displayId = display.display.displayId
            Log.i(
                TAG,
                "trusted display created id=$displayId virtual=${virtualWidth}x$virtualHeight " +
                    "output=${outputWidth}x$outputHeight dpi=$dpi",
            )

            // The display starts empty (idle, no encoding). The app sends PROMOTE on screen-off and
            // DEMOTE on unlock over the socket. An optional arg promotes immediately (dev/testing).
            if (launchComponent != null) promoteApp(launchComponent)
            startTcpServer()
            Log.i(TAG, "ready, serving frames on 127.0.0.1:$PORT")
        } catch (t: Throwable) {
            Log.e(TAG, "fatal", t)
            return
        }
        Looper.loop()
    }

    /**
     * Encode the newest frame to JPEG (throttled) while promoted. When idle ([capturing]=false) we
     * drain frames without encoding, so an unlocked phone (mirroring instead) costs no extra battery.
     */
    private fun onImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (!capturing) return
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

    // Held in a field so the listening socket is never GC-finalized while the helper lives.
    @Volatile private var serverSocket: ServerSocket? = null

    /** Serve `[4-byte length][JPEG]` frames over loopback TCP — works with no network (no Wi-Fi). */
    private fun startTcpServer() {
        val server = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        Thread {
            // Keep accepting forever, one thread per client. The phone (esp. MIUI) can tear down the
            // app's loopback socket on screen-off; the app then reconnects, so we must stay ready to
            // accept the new connection rather than blocking inside a single client's serve loop.
            while (true) {
                val client = runCatching { server.accept() }.getOrNull()
                if (client == null) { Thread.sleep(50); continue }
                Thread { serveClient(client) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = false; start() }
    }

    private fun serveClient(client: Socket) {
        // Reverse channel: the app sends "PROMOTE <component>" on screen-off and "DEMOTE" on unlock.
        Thread { readCommands(client) }.apply { isDaemon = true; start() }
        try {
            client.tcpNoDelay = true
            val out = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
            var sentSeq = -1L
            while (!client.isClosed) {
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
        } catch (e: Throwable) {
            Log.i(TAG, "client disconnected: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readCommands(client: Socket) {
        try {
            val input = client.getInputStream().bufferedReader()
            while (true) {
                val line = input.readLine()?.trim() ?: break
                when {
                    line.startsWith("PROMOTE ") -> promoteApp(line.removePrefix("PROMOTE ").trim())
                    line == "DEMOTE" -> demoteApp()
                    line == "QUIT" -> shutdown()
                }
            }
        } catch (_: Throwable) {
            // fall through to close
        } finally {
            // Closing wakes the writer loop (it checks isClosed) so a broken client's thread ends
            // instead of sleeping forever.
            runCatching { client.close() }
        }
    }

    /** Move the foreground app onto the dash display and start encoding (phone just locked). */
    private fun promoteApp(component: String) {
        if (displayId < 0 || component.isEmpty()) return
        exec(
            "am", "start", "--display", displayId.toString(),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", component,
        )
        lastComponent = component
        capturing = true
        startHeartbeat()          // keep the device interactive so the dash group renders
        setMainDisplayPower(false) // turn the phone's own panel off (it stays awake → dash keeps rendering)
        Log.i(TAG, "promoted $component to display $displayId")
    }

    /**
     * Keep the dash display's own display group from going idle while promoted. An
     * OWN_DISPLAY_GROUP/ALWAYS_UNLOCKED virtual display still powers OFF ~10s after the phone screen
     * turns off unless something keeps poking it — exactly scrcpy's `--keep-active`. We call
     * IPowerManager.userActivity(displayId, …) every few seconds, which resets *that group's* sleep
     * timer without waking the main screen, so the dash keeps rendering with the phone locked.
     */
    private fun startHeartbeat() {
        if (keepAlive != null) return
        val pm = powerService
        val m = userActivity
        if (pm == null || m == null) {
            Log.w(TAG, "keep-alive: userActivity unavailable — dash will black out ~10s after lock")
            return
        }
        wakeDevice() // power-press may have dozed the device; wake it so the dash group can render
        keepAlive = Thread {
            Log.i(TAG, "keep-alive: started for display $displayId")
            while (capturing && displayId >= 0) {
                runCatching { m.invoke(pm, displayId, android.os.SystemClock.uptimeMillis(), 0, 0) }
                    .onFailure { Log.w(TAG, "keep-alive: userActivity failed: ${it.message}") }
                try { Thread.sleep(KEEP_ALIVE_MS) } catch (_: InterruptedException) { break }
            }
            Log.i(TAG, "keep-alive: stopped")
        }.apply { isDaemon = true; start() }
    }

    private fun stopHeartbeat() {
        keepAlive?.interrupt()
        keepAlive = null
    }

    /** Wake the device from sleep/doze (the user just pressed POWER) so the dash display can render.
     *  userActivity only resets the idle timer — it can't wake a dozing device — so we need wakeUp(). */
    private fun wakeDevice() {
        val pm = powerService ?: return
        val m = pm.javaClass.methods.firstOrNull { it.name == "wakeUp" } ?: run {
            Log.w(TAG, "wake: no wakeUp method"); return
        }
        runCatching {
            val now = android.os.SystemClock.uptimeMillis()
            val args = m.parameterTypes.mapIndexed { i, t ->
                when {
                    t == Long::class.javaPrimitiveType -> now
                    t == Int::class.javaPrimitiveType -> 0           // wake reason: UNKNOWN/APPLICATION
                    t == String::class.java -> "pillion-dash"        // details / opPackageName
                    else -> null
                }
            }.toTypedArray()
            m.invoke(pm, *args)
            Log.i(TAG, "wake: requested device wake")
        }.onFailure { Log.w(TAG, "wake: wakeUp failed: ${reason(it)}") }
    }

    /** IPowerManager binder, for the per-display userActivity heartbeat (shell uid holds DEVICE_POWER). */
    private val powerService: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "power")
            val stub = Class.forName("android.os.IPowerManager\$Stub")
            stub.methods.first { it.name == "asInterface" }.invoke(null, binder)
        }.onFailure { Log.w(TAG, "keep-alive: no IPowerManager: ${it.message}") }.getOrNull()
    }

    /** API 31+ overload: userActivity(int displayId, long time, int event, int flags). */
    private val userActivity: java.lang.reflect.Method? by lazy {
        powerService?.javaClass?.methods?.firstOrNull {
            it.name == "userActivity" && it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Long::class.javaPrimitiveType
        }
    }

    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2

    /**
     * Blank (or restore) the PHONE's main panel without sleeping the device — scrcpy's `--turn-screen-off`.
     * The dash needs the device to stay interactive (so its display group keeps rendering, kept alive by
     * [startHeartbeat]); pressing POWER would truly sleep the device and kill the dash. So instead we
     * turn just display 0's panel off at the SurfaceFlinger level: the phone looks off, the system stays
     * awake, and the dash keeps rendering. Restored on demote/shutdown.
     */
    private fun setMainDisplayPower(on: Boolean) {
        // Reliable visible effect on GrapheneOS shell-uid: drop the phone's panel brightness to ~0.
        // (True panel-off via setDisplayPowerMode needs libandroid_servers natives that won't load in
        // app_process; requestDisplayPower is a no-op here — so brightness is the dependable lever.)
        if (on) {
            exec("settings", "put", "system", "screen_brightness_mode", "1") // restore auto-brightness
        } else {
            exec("settings", "put", "system", "screen_brightness_mode", "0") // manual
            exec("settings", "put", "system", "screen_brightness", "0")
        }
        // Primary: SurfaceControl.setDisplayPowerMode(token, mode) — actually blanks the panel at the
        // SurfaceFlinger level while the system stays awake (scrcpy's --turn-screen-off).
        val token = mainDisplayToken()
        if (token != null) {
            val ok = runCatching {
                val sc = Class.forName("android.view.SurfaceControl")
                sc.getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(null, token, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF)
            }.onFailure { Log.w(TAG, "panel: setDisplayPowerMode: ${reason(it)}") }.isSuccess
            if (ok) { Log.i(TAG, "panel: main display ${if (on) "ON" else "OFF"} (setDisplayPowerMode)"); return }
        }
        // Fallback: IDisplayManager.requestDisplayPower (Android 15+) — may be a no-op on some builds.
        val dm = displayManagerService
        val req = dm?.javaClass?.methods?.firstOrNull {
            it.name == "requestDisplayPower" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (dm != null && req != null) {
            runCatching {
                val arg = if (req.parameterTypes[1] == Boolean::class.javaPrimitiveType) on else if (on) 2 else 0
                req.invoke(dm, 0, arg)
            }.onSuccess { Log.i(TAG, "panel: main display ${if (on) "ON" else "OFF"} (requestDisplayPower)") }
                .onFailure { Log.w(TAG, "panel: requestDisplayPower: ${reason(it)}") }
        } else {
            Log.w(TAG, "panel: no way to set main display power")
        }
    }

    /** IDisplayManager binder, for requestDisplayPower (Android 15+). */
    private val displayManagerService: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "display")
            val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            stub.methods.first { it.name == "asInterface" }.invoke(null, binder)
        }.onFailure { Log.w(TAG, "panel: no IDisplayManager: ${reason(it)}") }.getOrNull()
    }

    /**
     * The main physical display's SurfaceControl token. On Android 14+ the getPhysicalDisplay* methods
     * moved off SurfaceControl into com.android.server.display.DisplayControl (in SYSTEMSERVERCLASSPATH,
     * not our boot classpath), so load that with a dedicated classloader; fall back to SurfaceControl on
     * older builds.
     */
    private fun mainDisplayToken(): android.os.IBinder? {
        val sc = Class.forName("android.view.SurfaceControl")
        // 1) SurfaceControl.getInternalDisplayToken() — simplest, API 29+.
        runCatching {
            return sc.getMethod("getInternalDisplayToken").invoke(null) as android.os.IBinder
        }.onFailure { Log.w(TAG, "panel: getInternalDisplayToken: ${reason(it)}") }
        // 2) SurfaceControl.getPhysicalDisplayIds/Token — pre-14 and some 14+.
        runCatching {
            val ids = sc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val getToken = sc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            return getToken.invoke(null, ids.first()) as android.os.IBinder
        }.onFailure { Log.w(TAG, "panel: SurfaceControl physical token: ${reason(it)}") }
        // 3) Android 14+: methods moved to com.android.server.display.DisplayControl. Its getPhysicalDisplay*
        // methods are native (nativeGetPhysicalDisplayIds) implemented in libandroid_servers. The natives
        // register against whichever DisplayControl class the *boot/app* classloader resolves, so we must
        // load DisplayControl via the app classloader (the helper is spawned with services.jar on its
        // CLASSPATH) — NOT a child PathClassLoader, or RegisterNatives won't match (UnsatisfiedLinkError).
        runCatching {
            runCatching { System.loadLibrary("android_servers") }
            val dc = Class.forName("com.android.server.display.DisplayControl")
            val ids = dc.getMethod("getPhysicalDisplayIds").apply { isAccessible = true }
                .invoke(null) as LongArray
            val getToken = dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            return getToken.invoke(null, ids.first()) as android.os.IBinder
        }.onFailure { Log.w(TAG, "panel: DisplayControl token: ${reason(it)}") }
        return null
    }

    /** Unwrap reflection wrappers so the real cause shows in logcat. */
    private fun reason(t: Throwable): String {
        val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t.cause ?: t
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    /** Release the trusted display and exit. Process death drops the display token, so System.exit
     *  is enough; the app sends QUIT over loopback when the session ends (the helper is detached and
     *  would otherwise outlive the app). */
    private fun shutdown() {
        Log.i(TAG, "shutdown requested; releasing display and exiting")
        runCatching { demoteApp() }
        System.exit(0)
    }

    /** Move the app back to the phone and stop encoding (phone unlocked). */
    private fun demoteApp() {
        capturing = false
        stopHeartbeat()
        setMainDisplayPower(true) // restore the phone's panel
        latestJpeg = null
        lastComponent?.let {
            exec(
                "am", "start", "--display", "0",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", it,
            )
        }
        Log.i(TAG, "demoted to phone")
    }

    private fun toJpeg(image: Image): ByteArray {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * virtualWidth
        val padded = Bitmap.createBitmap(
            virtualWidth + if (pixelStride > 0) rowPadding / pixelStride else 0,
            virtualHeight,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val bitmap = if (rowPadding == 0) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, virtualWidth, virtualHeight)
        }
        val output = if (bitmap.width == outputWidth && bitmap.height == outputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, outputWidth, outputHeight, true)
        }
        val out = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (output !== bitmap) output.recycle()
        if (bitmap !== padded) bitmap.recycle()
        padded.recycle()
        return out.toByteArray()
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
