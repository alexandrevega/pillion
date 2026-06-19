package app.pillion.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import app.pillion.nav.LatLng
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos

/**
 * Renders the dash map (route + current position) to a JPEG on the CPU (software [Canvas]) — so it
 * works with the screen off, no GPU / MediaProjection / Shizuku. This is the self-rendered "image
 * mode" map that replaces screen-capture: pixels we generate, not pixels we capture.
 *
 * North-up, centered on the rider. A real basemap (MapLibre/HERE tiles) would slot in behind the
 * route; for now it's a dark canvas with the route line + position marker — enough to prove the
 * image pipeline end-to-end.
 */
class NavMapRenderer(private val width: Int = 480, private val height: Int = 240) {
    private val bg = Paint().apply { color = Color.rgb(13, 17, 23) }
    private val route = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 216, 200)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val posFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val posRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 216, 200); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    private val canvas = Canvas(bitmap)
    private val out = ByteArrayOutputStream(16 * 1024)

    /** Render the view centered on [pos]; [metersPerPixel] lower = more zoomed in. */
    fun render(geometry: List<LatLng>, pos: LatLng, metersPerPixel: Double = 2.5, quality: Int = 70): ByteArray {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        if (geometry.size >= 2) {
            val path = Path()
            var started = false
            for (p in geometry) {
                val x = projX(p, pos, metersPerPixel)
                val y = projY(p, pos, metersPerPixel)
                if (!started) {
                    path.moveTo(x, y); started = true
                } else {
                    path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, route)
        }
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, 7f, posFill)
        canvas.drawCircle(cx, cy, 10f, posRing)

        out.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun projX(p: LatLng, center: LatLng, mpp: Double): Float {
        val mPerDegLng = 111_320.0 * cos(center.lat * PI / 180.0)
        val dxMeters = (p.lng - center.lng) * mPerDegLng
        return (width / 2.0 + dxMeters / mpp).toFloat()
    }

    private fun projY(p: LatLng, center: LatLng, mpp: Double): Float {
        val dyMeters = (p.lat - center.lat) * 111_320.0
        return (height / 2.0 - dyMeters / mpp).toFloat() // screen y is down; north is up
    }
}
