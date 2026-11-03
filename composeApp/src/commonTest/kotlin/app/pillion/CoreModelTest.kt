package app.pillion

import app.pillion.core.DashResolution
import app.pillion.core.MirrorSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreModelTest {

    @Test
    fun dash_resolution_from_name_is_robust() {
        assertEquals(DashResolution.Scale26, DashResolution.fromName("Wide"))
        assertEquals(DashResolution.DEFAULT, DashResolution.fromName(null))
        assertEquals(DashResolution.DEFAULT, DashResolution.fromName("nonsense"))
        assertEquals(DashResolution.Balanced, DashResolution.DEFAULT)
    }

    @Test
    fun dash_scale_label_and_legacy_conversion_are_consistent() {
        assertEquals("2.6x", DashResolution.Scale26.label)
        assertEquals(1248, DashResolution.Scale26.width)
        assertEquals(624, DashResolution.Scale26.height)
    }

    @Test
    fun mirror_settings_defaults_are_sane() {
        val s = MirrorSettings()
        assertEquals(40, s.quality)
        assertEquals(15, s.maxFps)
        assertEquals(DashResolution.DEFAULT, s.dashResolution)
    }
}
