package app.pillion.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import app.pillion.nav.LatLng
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
    private val zoom: Int = 16,
    private val style: String = "explore.night",
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
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val out = ByteArrayOutputStream(32 * 1024)

    /** Render centered on [pos] with the route on top; returns JPEG bytes. */
    fun render(geometry: List<LatLng>, pos: LatLng, quality: Int = 75): ByteArray {
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
            val path = Path()
            var started = false
            for (p in geometry) {
                val x = (lonToWorld(p.lng) * worldPx - originX).toFloat()
                val y = (latToWorld(p.lat) * worldPx - originY).toFloat()
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            canvas.drawPath(path, routePaint)
        }
        canvas.drawCircle(width / 2f, height / 2f, 7f, posFill)
        canvas.drawCircle(width / 2f, height / 2f, 10f, posRing)

        out.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
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
