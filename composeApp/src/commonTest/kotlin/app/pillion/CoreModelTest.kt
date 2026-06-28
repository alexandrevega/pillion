package app.pillion

import app.pillion.core.DashResolution
import app.pillion.core.MirrorSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreModelTest {

    @Test
    fun dash_resolution_from_name_is_robust() {
        assertEquals(DashResolution.Wide, DashResolution.fromName("Wide"))
        assertEquals(DashResolution.DEFAULT, DashResolution.fromName(null))
        assertEquals(DashResolution.DEFAULT, DashResolution.fromName("nonsense"))
        assertEquals(DashResolution.Balanced, DashResolution.DEFAULT)
    }

    @Test
    fun dash_resolution_label_matches_dimensions() {
        assertEquals("1280 x 640", DashResolution.Wide.label)
        assertEquals(1280, DashResolution.Wide.width)
        assertEquals(640, DashResolution.Wide.height)
    }

    @Test
    fun mirror_settings_defaults_are_sane() {
        val s = MirrorSettings()
        assertEquals(40, s.quality)
        assertEquals(25, s.maxFps)
        assertEquals(DashResolution.DEFAULT, s.dashResolution)
    }
}
