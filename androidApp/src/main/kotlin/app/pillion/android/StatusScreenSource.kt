package app.pillion.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import app.pillion.core.ScreenSource
import java.io.ByteArrayOutputStream

/**
 * v40-style STATUS JPEG.  The message stays centred as the three trailing dots
 * animate; the CCU's negotiated dimensions are used instead of a fixed XMAX size.
 */
class StatusScreenSource(
    private val message: String = PLEASE_LOCK_PHONE,
) : ScreenSource {
    @Volatile private var startedAtMs = SystemClock.elapsedRealtime()
    private var width = 480
    private var height = 240
    private var cachedDots = -1
    private var cachedFrame: ByteArray? = null

    override fun start() = Unit

    fun reset() {
        startedAtMs = SystemClock.elapsedRealtime()
        synchronized(this) {
            cachedDots = -1
            cachedFrame = null
        }
    }

    override fun resizeOutput(width: Int, height: Int) {
        synchronized(this) {
            if (width == this.width && height == this.height) return
            this.width = width
            this.height = height
            cachedDots = -1
            cachedFrame = null
        }
    }

    override fun latestFrame(): ByteArray {
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs
        val dots = ((elapsed / DOT_INTERVAL_MS) % (MAX_DOTS + 1)).toInt()
        synchronized(this) {
            if (cachedFrame == null || cachedDots != dots) {
                cachedDots = dots
                cachedFrame = render(dots)
            }
            return cachedFrame!!
        }
    }

    private fun render(dots: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TEXT_SIZE_PX
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val cx = width / 2f
        val cy = height / 2f
        val baseline = cy - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(message, cx, baseline, paint)
        if (dots > 0) {
            val textWidth = paint.measureText(message)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(DOT.repeat(dots), cx + textWidth / 2f + DOT_GAP_PX, baseline, paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    override fun stop() {
        synchronized(this) {
            cachedDots = -1
            cachedFrame = null
        }
    }

    companion object {
        const val PLEASE_LOCK_PHONE = "Please lock the phone"
        private const val DOT = "・"
        private const val MAX_DOTS = 3
        private const val DOT_INTERVAL_MS = 1_000L
        private const val TEXT_SIZE_PX = 20f
        private const val DOT_GAP_PX = 4f
        private const val JPEG_QUALITY = 60
    }
}
