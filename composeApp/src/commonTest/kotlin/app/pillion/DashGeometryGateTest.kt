package app.pillion

import app.pillion.core.DashCaptureGeometry
import app.pillion.core.DashFrameGate
import app.pillion.core.DashMargins
import app.pillion.core.DashMarginColor
import app.pillion.core.DashResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashGeometryGateTest {
    @Test
    fun display_size_preserves_ccu_aspect_ratio_exactly() {
        val xmax = DashCaptureGeometry.forOutput(960, 480, 234)
        assertEquals(960, xmax.width)
        assertEquals(468, xmax.height)
        assertEquals(xmax.width * 234, xmax.height * 480)
        val standard = DashCaptureGeometry.forOutput(960, 480, 240)
        assertEquals(960, standard.width)
        assertEquals(480, standard.height)
        val otherPreset = DashCaptureGeometry.forOutput(1024, 480, 234)
        assertEquals(1040, otherPreset.width)
        assertEquals(507, otherPreset.height)
        assertEquals(otherPreset.width * 234, otherPreset.height * 480)
    }

    @Test
    fun margins_and_fractional_scale_produce_usable_vd_geometry() {
        assertEquals(DashMargins(50, 46), DashMargins())
        assertEquals(70, DashMargins.MAX_LEFT)
        assertEquals(70, DashMargins.MAX_BOTTOM)
        val standard = DashCaptureGeometry.forMargins(DashResolution.Balanced.width, 480, 234, DashMargins(0, 0))
        assertEquals(960, standard.width)
        assertEquals(468, standard.height)
        val xmax = DashCaptureGeometry.forMargins(DashResolution.Balanced.width, 480, 234, DashMargins(50, 16))
        assertEquals(860, xmax.width)
        assertEquals(436, xmax.height)
        val fractional = DashCaptureGeometry.forMargins(DashResolution.Scale12.width, 480, 234, DashMargins(50, 16))
        assertEquals(516, fractional.width)
        assertEquals(262, fractional.height)
        val defaultGeometry = DashCaptureGeometry.forMargins(DashResolution.Balanced.width, 480, 234, DashMargins())
        assertEquals(860, defaultGeometry.width)
        assertEquals(376, defaultGeometry.height)
        val maximumGeometry = DashCaptureGeometry.forMargins(DashResolution.Scale3.width, 480, 234, DashMargins(70, 70))
        assertEquals(1230, maximumGeometry.width)
        assertEquals(492, maximumGeometry.height)
        assertEquals(DashMargins(70, 70), DashMargins.clamped(200, 100))
        assertEquals(DashMargins(0, 0), DashMargins.clamped(-9, -9))
        assertEquals(0xFF0A2D82.toInt(), DashMarginColor.YamahaBlue.argb)
    }

    @Test
    fun no_jpeg_before_ready_or_after_reconnect_with_old_ack() {
        val gate = DashFrameGate()
        assertFalse(gate.accepts(480, 240))
        val xmax = gate.request(480, 234)
        assertFalse(gate.acknowledge(xmax.revision, 480, 240))
        assertFalse(gate.accepts(480, 234))
        assertTrue(gate.acknowledge(xmax.revision, 480, 234))
        assertTrue(gate.accepts(480, 234))
        assertFalse(gate.accepts(480, 240))

        val reconnect = gate.reconnect()!!
        assertFalse(gate.acknowledge(xmax.revision, 480, 234))
        assertFalse(gate.accepts(480, 234))
        assertTrue(gate.acknowledge(reconnect.revision, 480, 234))
        assertTrue(gate.accepts(480, 234))

        val next = gate.request(480, 240)
        assertFalse(gate.accepts(480, 234))
        assertTrue(gate.acknowledge(next.revision, 480, 240))
        assertFalse(gate.accepts(480, 234))
        assertTrue(gate.accepts(480, 240))
        gate.pause()
        assertFalse(gate.accepts(480, 240))
    }
}
