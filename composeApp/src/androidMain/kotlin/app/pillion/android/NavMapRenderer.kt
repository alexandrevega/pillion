package app.pillion.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import app.pillion.nav.LatLng
import app.pillion.nav.TrafficLevel
import app.pillion.nav.TrafficSpan
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * Renders the dash map to a JPEG: HERE raster basemap tiles composited behind the route line +
 * position, all on the CPU (Canvas + BitmapFactory) — so it works with the screen off, no GPU /
 * MediaProjection / Shizuku. Tiles are fetched once and LRU-cached; as the rider moves only newly
 * visible tiles are fetched. Web Mercator throughout so the route overlays the tiles exactly.
 */
class NavMapRenderer(
    private val apiKey: String,
    private val width: Int = 480,
    private val height: Int = 240,
    private val zoom: Int = 17, // tighter nav zoom: fewer features in view -> smaller JPEGs
    private val style: String = "lite.night", // lighter style = far smaller JPEGs for fluid streaming
) {
    private val tileCache = LruCache<String, Bitmap>(64)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 216, 200)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val posFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val posRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 216, 200); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val cardBg = Paint().apply { color = Color.argb(0xE6, 13, 17, 23) }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 216, 200); textSize = 40f; isFakeBoldText = true
    }
    private val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f; isFakeBoldText = true
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 182, 195); textSize = 17f
    }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val out = ByteArrayOutputStream(32 * 1024)

    /** Render centered on [pos] with the route + a maneuver card on top; returns JPEG bytes. */
    fun render(
        geometry: List<LatLng>,
        pos: LatLng,
        maneuverIcon: Int = -1,
        distanceMeters: Int = -1,
        roadName: String = "",
        trafficSpans: List<TrafficSpan> = emptyList(),
        quality: Int = 32,
    ): ByteArray {
        val worldPx = (1 shl zoom) * TILE.toDouble()
        val originX = lonToWorld(pos.lng) * worldPx - width / 2.0
        val originY = latToWorld(pos.lat) * worldPx - height / 2.0

        canvas.drawColor(Color.rgb(13, 17, 23)) // fallback if a tile is missing
        val n = 1 shl zoom
        val firstX = floor(originX / TILE).toInt()
        val lastX = floor((originX + width) / TILE).toInt()
        val firstY = floor(originY / TILE).toInt()
        val lastY = floor((originY + height) / TILE).toInt()
        for (tx in firstX..lastX) {
            for (ty in firstY..lastY) {
                if (tx < 0 || ty < 0 || tx >= n || ty >= n) continue
                val tile = tile(tx, ty) ?: continue
                canvas.drawBitmap(tile, (tx * TILE - originX).toFloat(), (ty * TILE - originY).toFloat(), tilePaint)
            }
        }

        if (geometry.size >= 2) {
            val levels = trafficLevels(trafficSpans, geometry.size)
            var x0 = (lonToWorld(geometry[0].lng) * worldPx - originX).toFloat()
            var y0 = (latToWorld(geometry[0].lat) * worldPx - originY).toFloat()
            for (i in 1 until geometry.size) {
                val x1 = (lonToWorld(geometry[i].lng) * worldPx - originX).toFloat()
                val y1 = (latToWorld(geometry[i].lat) * worldPx - originY).toFloat()
                routePaint.color = trafficColor(levels[i - 1])
                canvas.drawLine(x0, y0, x1, y1, routePaint)
                x0 = x1; y0 = y1
            }
        }
        canvas.drawCircle(width / 2f, height / 2f, 7f, posFill)
        canvas.drawCircle(width / 2f, height / 2f, 10f, posRing)

        if (maneuverIcon >= 0) drawCard(maneuverIcon, distanceMeters, roadName)

        out.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Per-geometry-index traffic level (0=free, 1=slow, 2=jam), expanded from [spans]. */
    private fun trafficLevels(spans: List<TrafficSpan>, n: Int): IntArray {
        val out = IntArray(n) // default 0 = free
        if (spans.isEmpty()) return out
        val sorted = spans.sortedBy { it.offset }
        for (k in sorted.indices) {
            val start = sorted[k].offset.coerceIn(0, n)
            val end = (if (k + 1 < sorted.size) sorted[k + 1].offset else n).coerceIn(start, n)
            val v = when (sorted[k].level) {
                TrafficLevel.SLOW -> 1
                TrafficLevel.JAM -> 2
                else -> 0
            }
            for (i in start until end) out[i] = v
        }
        return out
    }

    private fun trafficColor(v: Int): Int = when (v) {
        1 -> Color.rgb(255, 193, 7)     // slow — amber
        2 -> Color.rgb(229, 57, 53)     // jam — red
        else -> Color.rgb(52, 216, 200) // free — teal
    }

    /** Top maneuver card: turn arrow + distance + next road, like a real nav header. */
    private fun drawCard(icon: Int, distanceMeters: Int, road: String) {
        canvas.drawRect(0f, 0f, width.toFloat(), 52f, cardBg)
        canvas.drawText(arrowGlyph(icon), 12f, 38f, arrowPaint)
        val dist = when {
            distanceMeters < 0 -> ""
            distanceMeters >= 1000 -> "%.1f km".format(distanceMeters / 1000.0)
            else -> "$distanceMeters m"
        }
        canvas.drawText(dist, 62f, 26f, distPaint)
        if (road.isNotBlank()) canvas.drawText(road.take(34), 62f, 45f, roadPaint)
    }

    private fun arrowGlyph(icon: Int): String = when (icon) {
        34 -> "←"        // turn left  ←
        35 -> "→"        // turn right →
        6, 10 -> "↖"     // keep/exit left  ↖
        7, 11 -> "↗"     // keep/exit right ↗
        32 -> "↙"        // sharp left  ↙
        33 -> "↘"        // sharp right ↘
        36, 37 -> "↶"    // u-turn ↶
        14 -> "↻"        // roundabout ↻
        0, 1, 2 -> "◉"   // arrive ◉
        else -> "↑"      // continue / straight ↑
    }

    private fun tile(x: Int, y: Int): Bitmap? {
        val key = "$zoom/$x/$y"
        tileCache.get(key)?.let { return it }
        return runCatching {
            val url = URL("https://maps.hereapi.com/v3/base/mc/$zoom/$x/$y/png?style=$style&size=$TILE&apiKey=$apiKey")
            (url.openConnection() as HttpURLConnection).run {
                connectTimeout = 4000; readTimeout = 6000
                inputStream.use { BitmapFactory.decodeStream(it) }
            }?.also { tileCache.put(key, it) }
        }.onFailure { android.util.Log.w("PillionNav", "tile $key failed: ${it.message}") }.getOrNull()
    }

    // Web Mercator, normalized to [0,1).
    private fun lonToWorld(lon: Double): Double = (lon + 180.0) / 360.0
    private fun latToWorld(lat: Double): Double {
        val s = sin(lat * PI / 180.0)
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    private companion object {
        const val TILE = 256
    }
}
