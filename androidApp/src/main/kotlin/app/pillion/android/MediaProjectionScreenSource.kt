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

    private val thread = HandlerThread("pillion-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var latest: Bitmap? = null

    override fun start() {
        if (display != null) return // idempotent: capture may be pre-started by the service
        // Android 14+ requires a registered callback before createVirtualDisplay.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.w(TAG, "screen: projection stopped by the system") }
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
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    override fun stop() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection.stop() }
        thread.quitSafely()
        latest = null
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 240
        const val DEFAULT_QUALITY = 40
        const val TAG = "Pillion"
    }
}
