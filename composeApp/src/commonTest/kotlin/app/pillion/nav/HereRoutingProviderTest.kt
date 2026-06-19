package app.pillion.nav

import app.pillion.protocol.NaviLiteCodec
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parses a REAL HERE Routing v8 response captured from the live API (short Amsterdam route,
 * Churchilllaan area) and checks the maneuver + traffic mapping. No network — pure decode/map.
 */
class HereRoutingProviderTest {

    private val sample = """
    {"routes":[{"id":"r1","sections":[{"id":"s1","type":"vehicle","actions":[
      {"action":"depart","duration":24,"length":91,"instruction":"Head west on Deurloostraat. Go for 91 m.","offset":0},
      {"action":"turn","duration":20,"length":62,"instruction":"Turn left onto Krammerstraat. Go for 62 m.","offset":3,"direction":"left","severity":"quite"},
      {"action":"turn","duration":37,"length":170,"instruction":"Turn left onto Grevelingenstraat. Go for 170 m.","offset":4,"direction":"left","severity":"quite"},
      {"action":"turn","duration":13,"length":61,"instruction":"Turn left onto Volkerakstraat. Go for 61 m.","offset":9,"direction":"left","severity":"quite"},
      {"action":"turn","duration":30,"length":66,"instruction":"Turn right onto Deurloostraat. Go for 66 m.","offset":10,"direction":"right","severity":"quite"},
      {"action":"turn","duration":43,"length":230,"instruction":"Turn left onto Scheldestraat. Go for 230 m.","offset":11,"direction":"left","severity":"quite"},
      {"action":"continue","duration":81,"length":418,"instruction":"Continue on Ferdinand Bolstraat. Go for 418 m.","offset":24},
      {"action":"turn","duration":39,"length":235,"instruction":"Turn right onto Van Ostadestraat. Go for 235 m.","offset":43,"direction":"right","severity":"quite"},
      {"action":"turn","duration":11,"length":64,"instruction":"Turn right onto Tweede van der Helststraat. Go for 64 m.","offset":50,"direction":"right","severity":"quite"},
      {"action":"arrive","duration":0,"length":0,"instruction":"Arrive at Tweede van der Helststraat. Your destination is on the left.","offset":52}
    ],"summary":{"duration":298,"length":1397,"baseDuration":256},"polyline":"BG2k_6jD-1sqJ","language":"en-us","transport":{"mode":"car"}}]}]}
    """.trimIndent()

    @Test
    fun parsesRouteSummaryAndTraffic() {
        val resp = NavHttp.json.decodeFromString<HereRoutesResponse>(sample)
        val route = resp.routes.first().toRoute()!!
        assertEquals(1397, route.distanceMeters)
        assertEquals(298, route.durationSeconds)
        assertEquals(256, route.baseDurationSeconds)
        assertEquals(42, route.trafficDelaySeconds, "live-traffic delay should be duration - base")
        assertEquals(GeometryFormat.HERE_FLEXIBLE_POLYLINE, route.geometryFormat)
    }

    @Test
    fun mapsHereActionsToCanonicalManeuvers() {
        val steps = NavHttp.json.decodeFromString<HereRoutesResponse>(sample).routes.first().toRoute()!!.steps
        assertEquals(10, steps.size)
        assertEquals(Maneuver.DEPART, steps.first().maneuver)
        assertEquals(Maneuver.TURN_LEFT, steps[1].maneuver)
        assertEquals(Maneuver.TURN_RIGHT, steps[4].maneuver)
        assertEquals(Maneuver.CONTINUE, steps[6].maneuver)
        assertEquals(Maneuver.ARRIVE, steps.last().maneuver)
        // road name is extracted from the instruction text
        assertEquals("Krammerstraat", steps[1].roadName)
    }

    @Test
    fun directionVariantsMapToSharpAndSlight() {
        assertEquals(Maneuver.SHARP_LEFT, HereRoutingProvider.maneuverOf("turn", "sharpLeft"))
        assertEquals(Maneuver.SLIGHT_RIGHT, HereRoutingProvider.maneuverOf("turn", "slightlyRight"))
        assertEquals(Maneuver.ROUNDABOUT, HereRoutingProvider.maneuverOf("roundaboutEnter", null))
        assertEquals(Maneuver.UTURN_LEFT, HereRoutingProvider.maneuverOf("uTurn", "left"))
    }

    @Test
    fun maneuversMapToStreetCrossIconOrdinals() {
        assertEquals(34, NaviLiteTbt.iconOf(Maneuver.TURN_LEFT))
        assertEquals(35, NaviLiteTbt.iconOf(Maneuver.TURN_RIGHT))
        assertEquals(33, NaviLiteTbt.iconOf(Maneuver.SHARP_RIGHT))
        assertEquals(14, NaviLiteTbt.iconOf(Maneuver.ROUNDABOUT))
        assertEquals(1, NaviLiteTbt.iconOf(Maneuver.ARRIVE_LEFT))
        assertEquals(8, NaviLiteTbt.iconOf(Maneuver.CONTINUE))
    }

    @Test
    fun contentUpdateSelectsTbtOnlyVsImageMode() {
        // service 55: 02 00 = TBT-only (screen-off path), 01 00 = nav image
        assertEquals(0x02.toByte(), NaviLiteCodec.payloadAt(NaviLiteTbt.contentUpdate(tbtOnly = true), 0)[0])
        assertEquals(0x01.toByte(), NaviLiteCodec.payloadAt(NaviLiteTbt.contentUpdate(tbtOnly = false), 0)[0])
    }

    @Test
    fun nextTurnFrameCarriesTheIconByte() {
        val frame = NaviLiteTbt.nextTurn(icon = 34, distanceMeters = 1400f)
        assertEquals(34.toByte(), NaviLiteCodec.payloadAt(frame, 0)[0])
    }

    @Test
    fun registryPicksTrafficProviderAndFallsBack() {
        val registry = RoutingProviderRegistry()
            .register(ValhallaRoutingProvider("http://localhost:8002"))
            .register(HereRoutingProvider(apiKey = "test"))
        // a traffic request should pick HERE (Valhalla has no traffic)
        assertEquals("here", registry.pick(RouteRequest(LatLng(0.0, 0.0), LatLng(1.0, 1.0), withTraffic = true))?.id)
        // a motorcycle request should pick Valhalla (HERE has no moto profile)
        val moto = RouteRequest(LatLng(0.0, 0.0), LatLng(1.0, 1.0), profile = TravelProfile.MOTORCYCLE, withTraffic = false)
        assertEquals("valhalla", registry.pick(moto)?.id)
        assertTrue(registry.all().size == 2)
    }
}
