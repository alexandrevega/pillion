package app.pillion

import app.pillion.core.MirrorSettings
import app.pillion.core.MirrorState
import app.pillion.core.UnsupportedController
import app.pillion.core.headunit.AppIdentity
import app.pillion.core.headunit.HeadUnitProfile
import app.pillion.core.headunit.HeadUnitRegistry
import app.pillion.core.headunit.LinkKind
import app.pillion.core.headunit.NaviLiteProfile
import app.pillion.core.headunit.SdlProfile
import app.pillion.core.headunit.VideoPreference
import app.pillion.core.headunit.registerBuiltInHeadUnits
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HeadUnitTest {

    // The registry is a process-wide singleton; isolate every test from the others.
    @BeforeTest fun reset() = HeadUnitRegistry.clear()
    @AfterTest fun cleanup() = HeadUnitRegistry.clear()

    @Test
    fun built_ins_register_both_profiles_in_order() {
        registerBuiltInHeadUnits()
        val all = HeadUnitRegistry.all()
        assertEquals(listOf("yamaha-navilite", "yamaha-sdl"), all.map { it.id })
        assertSame(NaviLiteProfile, HeadUnitRegistry.byId("yamaha-navilite"))
        assertSame(SdlProfile, HeadUnitRegistry.byId("yamaha-sdl"))
    }

    @Test
    fun unknown_id_resolves_to_null() {
        registerBuiltInHeadUnits()
        assertNull(HeadUnitRegistry.byId("does-not-exist"))
    }

    @Test
    fun registering_same_id_overwrites_not_duplicates() {
        val a = profile("dup", requiresUsb = false)
        val b = profile("dup", requiresUsb = true)
        HeadUnitRegistry.register(a)
        HeadUnitRegistry.register(b)
        assertEquals(1, HeadUnitRegistry.all().size)
        assertSame(b, HeadUnitRegistry.byId("dup"))
    }

    @Test
    fun navilite_is_bluetooth_jpeg_and_needs_no_usb_or_identity() {
        assertTrue(!NaviLiteProfile.requiresUsb)
        assertEquals(LinkKind.BluetoothRfcomm, NaviLiteProfile.linkKind)
        assertTrue(NaviLiteProfile.videoPreference is VideoPreference.JpegSlideshow)
        assertNull(NaviLiteProfile.identity)
    }

    @Test
    fun sdl_is_usb_h264_with_motorize_identity_and_full_eap_set() {
        assertTrue(SdlProfile.requiresUsb)
        assertEquals(LinkKind.UsbAoa, SdlProfile.linkKind)
        assertTrue(SdlProfile.videoPreference is VideoPreference.H264)
        val id = SdlProfile.identity!!
        assertEquals("Motorize", id.appName)
        assertEquals("7a5f3f25-8b82-4e0f-a173-80aefee79897", id.fullAppId)
        // prot0..prot29 (30) + multisession (1) = 31, and the multisession entry must be present.
        assertEquals(31, id.iosEapProtocols.size)
        assertTrue(id.iosEapProtocols.contains("com.smartdevicelink.prot0"))
        assertTrue(id.iosEapProtocols.contains("com.smartdevicelink.prot29"))
        assertTrue(id.iosEapProtocols.contains("com.smartdevicelink.multisession"))
    }

    @Test
    fun link_kinds_are_value_equal_and_open_for_new_ids() {
        assertEquals(LinkKind("usb-aoa"), LinkKind.UsbAoa)
        assertTrue(LinkKind.UsbAoa != LinkKind.UsbIap2)
        // OCP: a plugin can mint a brand-new kind without editing the enum-free type.
        assertEquals("ble-l2cap", LinkKind("ble-l2cap").id)
    }

    private fun profile(id: String, requiresUsb: Boolean) = object : HeadUnitProfile {
        override val id = id
        override val displayName = id
        override val vendor = "test"
        override val linkKind = LinkKind.TcpDebug
        override val requiresUsb = requiresUsb
        override val videoPreference = VideoPreference.JpegSlideshow()
        override val identity: AppIdentity? = null
    }

    @Test
    fun unsupported_controller_errors_on_start_and_clears_on_stop() {
        val c = UnsupportedController("nope")
        assertEquals(MirrorState.Idle, c.state.value)
        c.start(MirrorSettings())
        assertEquals(MirrorState.Error("nope"), c.state.value)
        c.stop()
        assertEquals(MirrorState.Idle, c.state.value)
    }
}
