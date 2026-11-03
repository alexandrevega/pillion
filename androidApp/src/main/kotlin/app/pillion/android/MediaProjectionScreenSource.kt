package app.pillion.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import app.pillion.core.ScreenSource
import java.io.ByteArrayOutputStream

/**
 * A [ScreenSource] backed by MediaProjection. Mirrors the display into a 480x240 [ImageReader]
 * and, on demand, compresses the most recent frame to JPEG. Single responsibility: screen -> JPEG.
 */
class MediaProjectionScreenSource(
    private val context: Context,
    private val projection: MediaProjection,
    private val quality: Int = DEFAULT_QUALITY,
    private val onProjectionStopped: (() -> Unit)? = null,
) : ScreenSource {

    private val thread = HandlerThread("pillion-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var latest: Bitmap? = null
    @Volatile private var ended = false
    // Output size sent over NaviLite. Capture is fixed at WIDTHxHEIGHT (the token must be claimed
    // before the handshake reveals the dash size), so we scale the captured frame to this instead.
    @Volatile private var outWidth = WIDTH
    @Volatile private var outHeight = HEIGHT

    @Synchronized
    override fun start() {
        if (ended || display != null) return // no restart with a stopped, single-use projection
        // Android 14+ requires a registered callback before createVirtualDisplay.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "screen: projection stopped")
                releaseCapture("projection_stopped")
                onProjectionStopped?.invoke()
            }
        }, handler)
        val r = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir -> capture(ir) }, handler)
        reader = r
        val dpi = context.resources.displayMetrics.densityDpi
        display = projection.createVirtualDisplay(
            "pillion", WIDTH, HEIGHT, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        Log.d(TAG, "screen: virtual display created (${WIDTH}x$HEIGHT)")
    }

    @Synchronized
    private fun capture(ir: ImageReader) {
        if (ended) return
        val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return
        try {
            val first = latest == null
            latest = toBitmap(image)
            if (first) Log.d(TAG, "screen: first frame captured")
        } catch (t: Throwable) {
            Log.w(TAG, "screen: dropped a frame", t)
        } finally {
            image.close()
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * WIDTH
        val full = Bitmap.createBitmap(
            WIDTH + if (pixelStride > 0) rowPadding / pixelStride else 0,
            HEIGHT, Bitmap.Config.ARGB_8888,
        )
        full.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, WIDTH, HEIGHT)
    }

    override fun latestFrame(): ByteArray? {
        if (ended) return null
        val bitmap = latest ?: return null
        val w = outWidth
        val h = outHeight
        val scaled = if (bitmap.width != w || bitmap.height != h) {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        // An onStop callback may arrive during JPEG compression. Never hand out a dead frame.
        return if (ended) null else out.toByteArray()
    }

    override fun resizeOutput(width: Int, height: Int) {
        if (width == outWidth && height == outHeight) return
        outWidth = width
        outHeight = height
        Log.d(TAG, "screen: output size set to ${width}x$height")
    }

    override fun stop() {
        releaseCapture("stop")
        runCatching { projection.stop() }
    }

    /** A system revocation and the Stop button both converge on the same, idempotent cleanup. */
    @Synchronized
    private fun releaseCapture(reason: String) {
        if (ended) return
        ended = true
        latest = null
        val oldDisplay = display
        val oldReader = reader
        display = null
        reader = null
        runCatching { oldReader?.setOnImageAvailableListener(null, null) }
        runCatching { oldDisplay?.release() }
        runCatching { oldReader?.close() }
        thread.quitSafely()
        Log.d(TAG, "screen: capture released ($reason)")
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 240
        const val DEFAULT_QUALITY = 40
        const val TAG = "Pillion"
    }
}
