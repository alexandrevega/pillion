package app.pillion.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuidanceTest {
    // Real HERE flexible polyline captured from the live v8 API (Amsterdam route, precision 6).
    private val polyline =
        "BG2k_6jD-1sqJOhGoBvbkDrxBriBnGvCozBTgF7BopBA0FjD8zBsiBsEjDg8BkIoBwHUoQwCwHoBgF4DsE8B" +
            "wMwC8V4DsTwC8BUwRkDkDAkDU0KoB4IoBkS8B4N8BoLoBkIoBkIoBwgBsE8L8BsJU0FA8G7B8a3I8G7BwMjD" +
            "0FT0P8B4IU8LT8GozB0FopBwCsTkDgZoBsJkIw5BUwHvHoBtcqE"

    @Test
    fun decodesFlexiblePolylineNearOrigin() {
        val pts = HerePolyline.decode(polyline)
        assertTrue(pts.size > 5, "expected several points, got ${pts.size}")
        val first = pts.first()
        assertTrue(first.lat in 52.30..52.40, "lat ${first.lat}")
        assertTrue(first.lng in 4.85..4.92, "lng ${first.lng}")
    }

    @Test
    fun snapDistanceIsZeroAtStartAndTotalAtEnd() {
        val pts = HerePolyline.decode(polyline)
        val cum = Guidance.cumulative(pts)
        assertEquals(0.0, Guidance.snapDistance(pts, cum, pts.first()), 5.0)
        assertEquals(cum.last(), Guidance.snapDistance(pts, cum, pts.last()), 5.0)
    }

    @Test
    fun progressPicksTheNextManeuverAhead() {
        // maneuvers at 0, 100, 250, 400 m
        val md = doubleArrayOf(0.0, 100.0, 250.0, 400.0)
        assertEquals(1, Guidance.progress(md, 0.0).activeIndex)
        assertEquals(100, Guidance.progress(md, 0.0).remainingMeters)
        assertEquals(2, Guidance.progress(md, 150.0).activeIndex)
        assertEquals(100, Guidance.progress(md, 150.0).remainingMeters)
        // past the last maneuver -> clamp to arrival, 0 remaining
        assertEquals(3, Guidance.progress(md, 450.0).activeIndex)
        assertEquals(0, Guidance.progress(md, 450.0).remainingMeters)
    }

    @Test
    fun pointAtWalksAlongTheRoute() {
        val pts = HerePolyline.decode(polyline)
        val cum = Guidance.cumulative(pts)
        val mid = Guidance.pointAt(pts, cum, cum.last() / 2)
        // the midpoint must itself snap to ~half the route length
        assertEquals(cum.last() / 2, Guidance.snapDistance(pts, cum, mid), 10.0)
    }
}
