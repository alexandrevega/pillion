package app.pillion
import app.pillion.core.DashDpi
import kotlin.test.Test
import kotlin.test.assertEquals
class DashDpiTest {
    @Test fun rangeAndSnap() {
        assertEquals(160, DashDpi.snap(100))
        assertEquals(480, DashDpi.snap(500))
        assertEquals(240, DashDpi.snap(239))
        assertEquals(250, DashDpi.snap(247))
        assertEquals(240, DashDpi.DEFAULT)
    }
}
