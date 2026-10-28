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
) : ScreenSource {

    // Recreated per start(): the looper dies with stop(), so reusing a dead one would silently
    // drop every frame posted to it.
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var latest: Bitmap? = null
    // Output size sent over NaviLite. Capture is fixed at WIDTHxHEIGHT (the token must be claimed
    // before the handshake reveals the dash size), so we scale the captured frame to this instead.
    @Volatile private var outWidth = WIDTH
    @Volatile private var outHeight = HEIGHT

    override fun start() {
        if (display != null) return // idempotent: capture may be pre-started by the service
        val t = HandlerThread("pillion-capture").apply { start() }
        val h = Handler(t.looper)
        thread = t; handler = h
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.w(TAG, "screen: projection stopped by the system") }
        }, h)
        val r = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir -> capture(ir) }, h)
        reader = r
        val dpi = context.resources.displayMetrics.densityDpi
        display = projection.createVirtualDisplay(
            "pillion", WIDTH, HEIGHT, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h,
        )
        Log.d(TAG, "screen: virtual display created (${WIDTH}x$HEIGHT)")
    }

    private fun capture(ir: ImageReader) {
        val image = ir.acquireLatestImage() ?: return
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
        return out.toByteArray()
    }

    override fun resizeOutput(width: Int, height: Int) {
        if (width == outWidth && height == outHeight) return
        outWidth = width
        outHeight = height
        Log.d(TAG, "screen: output size set to ${width}x$height")
    }

    override fun stop() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection.stop() }
        thread?.quitSafely()
        thread = null; handler = null
        latest = null
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 240
        const val DEFAULT_QUALITY = 40
        const val TAG = "Pillion"
    }
}
