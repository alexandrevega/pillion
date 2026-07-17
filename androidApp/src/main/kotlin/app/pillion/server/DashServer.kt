package app.pillion.server

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.ComponentName
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
    // so use their raw bit values (matched exactly to scrcpy's NewDisplayCapture on Android 14+).
    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_PRESENTATION = 1 shl 1
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val FLAG_OWN_FOCUS = 1 shl 14
    // The key flag (Android 14+): puts the display in a power group that survives the phone screen
    // turning off — without it the virtual display lands in display group 0 and dies with the panel.
    private const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

    private const val FLAGS = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or
        FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or
        FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
        FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED or
        FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP

    private const val MIN_INTERVAL_MS = 50L // cap capture/encode to ~20fps; the app paces sends

    private var virtualWidth = 480
    private var virtualHeight = 240
    private var outputWidth = 480
    private var outputHeight = 240
    private var quality = 40
    private var lastEncodeMs = 0L
    private var encodeWindowStartMs = 0L
    private var encodeFrames = 0
    private var encodeBytes = 0L
    private var encodeMsTotal = 0L
    private var encodeMsMax = 0L

    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var latestSeq = 0L

    // Battery: encode only while a foreground app is promoted (phone locked). Idle otherwise.
    @Volatile private var capturing = false
    @Volatile private var displayId = -1
    @Volatile private var lastComponent: String? = null
    @Volatile private var keepAlive: Thread? = null
    @Volatile private var panelOffRetry: Thread? = null

    private const val KEEP_ALIVE_MS = 3000L // poke the dash display group well under its ~10s idle timeout
    private val PANEL_OFF_RETRY_DELAYS_MS = longArrayOf(700L, 1500L)

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
            val encodeStart = System.currentTimeMillis()
            val jpeg = toJpeg(image)
            val encodeMs = System.currentTimeMillis() - encodeStart
            latestJpeg = jpeg
            latestSeq++
            logEncodeStats(jpeg.size, encodeMs)
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
        relocateApp(component, displayId)
        lastComponent = component
        capturing = true
        startHeartbeat()          // keep the device interactive so the dash group renders
        setMainDisplayPower(false) // turn the phone's own panel off (it stays awake → dash keeps rendering)
        startPanelOffRetries()
        Log.i(TAG, "promoted $component to display $displayId")
    }

    /**
     * Put [component] on [target], **moving its existing task** rather than re-launching it.
     * `am start --display` only relocates apps that allow a fresh instance; a singleTask/singleInstance
     * app already running on display 0 (e.g. Google Maps mid-navigation) just gets refocused on display
     * 0, so the dash display stays empty (black) while the app flashes on the phone on unlock — exactly
     * the reported bug. Moving the root task (the `am display move-stack` primitive) relocates it
     * regardless of launch mode. Fall back to a fresh launch only when the app isn't running yet.
     */
    private fun relocateApp(component: String, target: Int) {
        val pkg = component.substringBefore('/')
        val before = runCatching { taskAndDisplayForPackage(pkg) }
            .onFailure { Log.w(TAG, "relocate: task lookup failed for $pkg: ${reason(it)}") }
            .getOrNull()
        if (before != null) {
            val (taskId, fromDisplay) = before
            val rc = exec("am", "display", "move-stack", taskId.toString(), target.toString())
            // Re-query where the task actually ended up: move-stack returns rc=0 even when a
            // singleInstance task refuses to move, so the landed display is the source of truth.
            val landed = runCatching { taskAndDisplayForPackage(pkg)?.second }.getOrNull()
            Log.i(TAG, "relocate: task $taskId ($pkg) display $fromDisplay -> $target (rc=$rc, landed=$landed)")
        } else {
            exec(
                "am", "start", "--display", target.toString(),
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", component,
            )
            Log.i(TAG, "relocate: launched $component on display $target (was not running)")
        }
    }

    /** (rootTaskId, displayId) of [pkg]'s current task, or null if it isn't running. */
    private fun taskAndDisplayForPackage(pkg: String): Pair<Int, Int>? {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val infos = atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>
        for (info in infos) {
            info ?: continue
            val cls = info.javaClass
            val top = runCatching { cls.getField("topActivity").get(info) as? ComponentName }.getOrNull()
            val base = runCatching { cls.getField("baseActivity").get(info) as? ComponentName }.getOrNull()
            if (top?.packageName == pkg || base?.packageName == pkg) {
                return cls.getField("taskId").getInt(info) to cls.getField("displayId").getInt(info)
            }
        }
        return null
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
        wakeDisplay(displayId) // power-press may have dozed this display group; wake it so it can render
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

    /**
     * On Android 16, wakeUpWithDisplayId() may still be finishing its normal display-on transition
     * when the first panel-off request runs, so PowerManager can turn display 0 back on a few hundred
     * milliseconds later. Retry after the wake settles; the virtual display keeps rendering.
     */
    private fun startPanelOffRetries() {
        panelOffRetry?.interrupt()
        val retry = Thread {
            var elapsed = 0L
            for (delay in PANEL_OFF_RETRY_DELAYS_MS) {
                try {
                    val sleepMs = delay - elapsed
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                }
                elapsed = delay
                if (!capturing) break
                Log.i(TAG, "panel: retrying main display OFF after ${delay}ms")
                setMainDisplayPower(false)
            }
            if (panelOffRetry === Thread.currentThread()) panelOffRetry = null
        }.apply { isDaemon = true }
        panelOffRetry = retry
        retry.start()
    }

    private fun stopPanelOffRetries() {
        panelOffRetry?.interrupt()
        panelOffRetry = null
    }

    /** Wake the dash display group from sleep/doze. userActivity only resets the idle timer; it
     *  cannot wake a sleeping group, so Android 14+ needs wakeUpWithDisplayId() here. */
    private fun wakeDisplay(displayId: Int) {
        val pm = powerService ?: return
        val m = pm.javaClass.methods.firstOrNull {
            it.name == "wakeUpWithDisplayId" && it.parameterTypes.any { t -> t == Int::class.javaPrimitiveType }
        } ?: pm.javaClass.methods.firstOrNull { it.name == "wakeUp" } ?: run {
            Log.w(TAG, "wake: no wakeUp method"); return
        }
        runCatching {
            m.invoke(pm, *wakeArgs(m, displayId))
            Log.i(TAG, "wake: requested ${m.name} for display $displayId")
        }.onFailure { Log.w(TAG, "wake: wakeUp failed: ${reason(it)}") }
    }

    private fun wakeArgs(method: java.lang.reflect.Method, displayId: Int): Array<Any?> {
        val strings = mutableListOf("pillion-dash", SHELL_PACKAGE)
        val last = method.parameterTypes.lastIndex
        return method.parameterTypes.mapIndexed { i, t ->
            when {
                t == Long::class.javaPrimitiveType -> android.os.SystemClock.uptimeMillis()
                t == Int::class.javaPrimitiveType ->
                    if (method.name == "wakeUpWithDisplayId" && i == last) displayId else 0
                t == String::class.java -> strings.removeFirstOrNull() ?: SHELL_PACKAGE
                else -> null
            }
        }.toTypedArray()
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
    private const val DISPLAY_STATE_OFF = 1
    private const val DISPLAY_STATE_ON = 2

    /**
     * Blank (or restore) the PHONE's main panel without sleeping the device — scrcpy's `--turn-screen-off`.
     * The dash needs the device to stay interactive (so its display group keeps rendering, kept alive by
     * [startHeartbeat]); pressing POWER would truly sleep the device and kill the dash. So instead we
     * turn just display 0's panel off at the SurfaceFlinger level: the phone looks off, the system stays
     * awake, and the dash keeps rendering. Restored on demote/shutdown.
     */
    private fun setMainDisplayPower(on: Boolean) {
        // Try the real panel-off APIs only. Brightness-0 dimming was removed because it doesn't truly
        // turn the screen off. On some builds these hidden APIs are unavailable or rejected; when that
        // happens there is no app-only way to force a real panel-off state.
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
                val arg = if (req.parameterTypes[1] == Boolean::class.javaPrimitiveType) {
                    on
                } else if (on) {
                    DISPLAY_STATE_ON
                } else {
                    DISPLAY_STATE_OFF
                }
                req.invoke(dm, 0, arg) as? Boolean
            }.onSuccess { accepted ->
                if (accepted == false) {
                    Log.w(TAG, "panel: requestDisplayPower returned false for ${if (on) "ON" else "OFF"}")
                } else {
                    Log.i(TAG, "panel: main display ${if (on) "ON" else "OFF"} (requestDisplayPower)")
                }
            }
                .onFailure { Log.w(TAG, "panel: requestDisplayPower: ${reason(it)}") }
        } else {
            Log.w(TAG, "panel: no way to set main display power")
        }
    }

    /**
     * com.android.server.display.DisplayControl, loaded + linked so its **native** getPhysicalDisplay*
     * methods resolve from a bare app_process. Android 14+ moved those methods here from SurfaceControl.
     *
     * The naive approach — `Class.forName(...)` on the boot/app classloader + `System.loadLibrary` —
     * fails with UnsatisfiedLinkError: RegisterNatives binds the JNI methods to whichever classloader
     * loaded the *library*, which isn't the one that loaded DisplayControl. scrcpy's fix, replicated
     * here: load DisplayControl through a classloader built from $SYSTEMSERVERCLASSPATH, then load the
     * library via the private `Runtime.loadLibrary0(Class, String)` passing that same class — so the
     * natives register against it. Computed once, then reused for every panel-power call.
     */
    private val displayControlClass: Class<*>? by lazy {
        runCatching {
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val createClassLoader = factory.getDeclaredMethod(
                "createClassLoader",
                String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
            )
            val systemServerClasspath = System.getenv("SYSTEMSERVERCLASSPATH")
                ?: error("SYSTEMSERVERCLASSPATH unset")
            val loader = createClassLoader.invoke(
                null, systemServerClasspath, null, null, ClassLoader.getSystemClassLoader(), 0, true, null,
            ) as ClassLoader
            val dc = loader.loadClass("com.android.server.display.DisplayControl")
            linkAndroidServers(dc)
            Log.i(TAG, "panel: DisplayControl linked via SYSTEMSERVERCLASSPATH classloader")
            dc
        }.onFailure { Log.w(TAG, "panel: DisplayControl init failed: ${reason(it)}") }.getOrNull()
    }

    /**
     * Load libandroid_servers and register its natives against [dc]'s classloader. lint flags
     * `loadLibrary0` as a blocked non-SDK API for normal apps, but the helper runs as the **shell uid**
     * (app_process), which is exempt from the hidden-API blacklist — same as scrcpy — so the reflection
     * succeeds at runtime. Hence the suppression.
     */
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "PrivateApi")
    private fun linkAndroidServers(dc: Class<*>) {
        val rt = Runtime.getRuntime()
        // loadLibrary0(Class, String) on API 34+; fall back to (ClassLoader, String) on older shapes.
        val linked = runCatching {
            Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(rt, dc, "android_servers")
        }.recoverCatching {
            Runtime::class.java.getDeclaredMethod("loadLibrary0", ClassLoader::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(rt, dc.classLoader, "android_servers")
        }
        check(linked.isSuccess) { "loadLibrary0 failed: ${reason(linked.exceptionOrNull()!!)}" }
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
        // 3) Android 14+: methods moved to com.android.server.display.DisplayControl, whose native
        // getPhysicalDisplay* methods only resolve when the class + libandroid_servers are loaded
        // together via the SYSTEMSERVERCLASSPATH classloader (see [displayControlClass]).
        runCatching {
            val dc = displayControlClass ?: error("DisplayControl unavailable")
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
        stopPanelOffRetries()
        stopHeartbeat()
        wakeDisplay(0) // requestDisplayPower(ON) cannot restore a still-dozing power group by itself
        setMainDisplayPower(true) // restore the phone's panel
        latestJpeg = null
        lastComponent?.let { relocateApp(it, 0) } // move the task back to the phone's own display
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

    private fun logEncodeStats(bytes: Int, encodeMs: Long) {
        val now = System.currentTimeMillis()
        if (encodeWindowStartMs == 0L) encodeWindowStartMs = now
        encodeFrames++
        encodeBytes += bytes.toLong()
        encodeMsTotal += encodeMs
        if (encodeMs > encodeMsMax) encodeMsMax = encodeMs
        val elapsed = now - encodeWindowStartMs
        if (elapsed >= 1000L) {
            val avgKb = if (encodeFrames > 0) encodeBytes / encodeFrames / 1024 else 0L
            val avgEncodeMs = if (encodeFrames > 0) encodeMsTotal / encodeFrames else 0L
            Log.i(
                TAG,
                "encode: $encodeFrames frames, $avgKb KB/frame, " +
                    "$avgEncodeMs ms avg/${encodeMsMax} ms max",
            )
            encodeWindowStartMs = now
            encodeFrames = 0
            encodeBytes = 0L
            encodeMsTotal = 0L
            encodeMsMax = 0L
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
