package app.pillion.nav

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turn-by-turn guidance: maps a GPS position onto the route to derive the active maneuver and the
 * distance remaining to it. Pure functions (no platform deps) so the logic is unit-tested.
 *
 * The same call drives real navigation on the bike (live GPS fixes) and the desk demo (mock fixes
 * stepped along the route).
 */
object Guidance {
    data class Progress(val activeIndex: Int, val remainingMeters: Int)

    /** Cumulative distance (m) from the route start to each step's maneuver point. */
    fun maneuverDistances(steps: List<RouteStep>): DoubleArray {
        val d = DoubleArray(steps.size)
        var acc = 0.0
        for (i in steps.indices) {
            d[i] = acc
            acc += steps[i].distanceMeters
        }
        return d
    }

    /** Cumulative along-route distance (m) at each geometry vertex. */
    fun cumulative(geometry: List<LatLng>): DoubleArray {
        val c = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) c[i] = c[i - 1] + haversine(geometry[i - 1], geometry[i])
        return c
    }

    /** Distance (m) from the route start to the point on [geometry] nearest to [loc]. */
    fun snapDistance(geometry: List<LatLng>, cumulative: DoubleArray, loc: LatLng): Double {
        if (geometry.size < 2) return 0.0
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until geometry.size - 1) {
            val a = geometry[i]
            val b = geometry[i + 1]
            val t = projParam(a, b, loc)
            val proj = LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            val dToRoute = haversine(loc, proj)
            if (dToRoute < best) {
                best = dToRoute
                bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            }
        }
        return bestAlong
    }

    /** The next maneuver ahead of [distAlong] and the distance left to reach it. */
    fun progress(maneuverDistances: DoubleArray, distAlong: Double): Progress {
        var idx = maneuverDistances.size - 1
        for (i in maneuverDistances.indices) {
            if (maneuverDistances[i] > distAlong + 1.0) {
                idx = i
                break
            }
        }
        val remaining = (maneuverDistances[idx] - distAlong).coerceAtLeast(0.0)
        return Progress(idx, remaining.roundToInt())
    }

    /** Interpolate the lat/lng at [along] meters from the route start (clamped to the ends). */
    fun pointAt(geometry: List<LatLng>, cumulative: DoubleArray, along: Double): LatLng {
        if (geometry.isEmpty()) return LatLng(0.0, 0.0)
        if (along <= 0.0) return geometry.first()
        val total = cumulative.last()
        if (along >= total) return geometry.last()
        var i = 1
        while (i < cumulative.size && cumulative[i] < along) i++
        val a = geometry[i - 1]
        val b = geometry[i]
        val segLen = cumulative[i] - cumulative[i - 1]
        val t = if (segLen <= 0.0) 0.0 else (along - cumulative[i - 1]) / segLen
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    }

    private fun projParam(a: LatLng, b: LatLng, p: LatLng): Double {
        // Project p onto segment a-b in lat/lng space (fine over short segments); clamp to [0,1].
        val dx = b.lng - a.lng
        val dy = b.lat - a.lat
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return 0.0
        val t = ((p.lng - a.lng) * dx + (p.lat - a.lat) * dy) / len2
        return t.coerceIn(0.0, 1.0)
    }

    private fun haversine(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = (b.lat - a.lat) * PI / 180.0
        val dLng = (b.lng - a.lng) * PI / 180.0
        val la1 = a.lat * PI / 180.0
        val la2 = b.lat * PI / 180.0
        val h = sin(dLat / 2).pow(2) + cos(la1) * cos(la2) * sin(dLng / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }
}
