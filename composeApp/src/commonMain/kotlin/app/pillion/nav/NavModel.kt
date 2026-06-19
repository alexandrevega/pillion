package app.pillion.nav

/**
 * Provider-agnostic navigation model.
 *
 * Every [RoutingProvider] (HERE today; Valhalla/TomTom/Mapbox later) maps its own wire format
 * onto these types, so the rest of the app — and the NaviLite turn-by-turn bridge — never depends
 * on which provider produced a route. Swapping or adding a provider touches only its own file.
 */

/** A WGS84 coordinate. */
data class LatLng(val lat: Double, val lng: Double)

/** Routing profile. Not every provider supports every profile — see [ProviderCapabilities]. */
enum class TravelProfile { CAR, MOTORCYCLE, SCOOTER, BICYCLE, PEDESTRIAN }

/**
 * The canonical maneuver vocabulary. Providers map onto this; [NaviLiteTbt] maps this onto the
 * dash's StreetCross turn-icon ordinals. Keep this list provider-neutral.
 */
enum class Maneuver {
    DEPART, CONTINUE,
    KEEP_LEFT, KEEP_RIGHT,
    SLIGHT_LEFT, SLIGHT_RIGHT,
    TURN_LEFT, TURN_RIGHT,
    SHARP_LEFT, SHARP_RIGHT,
    UTURN_LEFT, UTURN_RIGHT,
    ROUNDABOUT,
    EXIT_LEFT, EXIT_RIGHT, EXIT,
    MERGE, FORK,
    FERRY,
    ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT,
    UNKNOWN,
}

/** One step of a route; distance/duration are for traversing this step. */
data class RouteStep(
    val maneuver: Maneuver,
    val instruction: String,
    val roadName: String?,
    val distanceMeters: Int,
    val durationSeconds: Int,
    /** Index into [Route.encodedGeometry] where this step begins (provider offset). */
    val geometryOffset: Int,
)

/** Encoding of [Route.encodedGeometry] — providers differ, so the consumer knows how to decode. */
enum class GeometryFormat { HERE_FLEXIBLE_POLYLINE, ENCODED_POLYLINE_5, GEOJSON }

/** Live-traffic state of a route segment, for coloring the route line. */
enum class TrafficLevel { FREE, SLOW, JAM, UNKNOWN }

/** A traffic state starting at geometry index [offset], running until the next span. */
data class TrafficSpan(val offset: Int, val level: TrafficLevel)

/**
 * A computed route.
 *
 * [durationSeconds] is traffic-aware when the provider supports it; [baseDurationSeconds] is the
 * free-flow time. [trafficDelaySeconds] is the difference (0 when there is no traffic data).
 */
data class Route(
    val distanceMeters: Int,
    val durationSeconds: Int,
    val baseDurationSeconds: Int,
    val steps: List<RouteStep>,
    /** Encoded geometry exactly as the provider returned it. Decode per [geometryFormat] to render. */
    val encodedGeometry: String?,
    val geometryFormat: GeometryFormat?,
    /** Per-segment live traffic (empty if the provider didn't supply it). */
    val trafficSpans: List<TrafficSpan> = emptyList(),
) {
    val trafficDelaySeconds: Int get() = (durationSeconds - baseDurationSeconds).coerceAtLeast(0)
}

data class RouteRequest(
    val origin: LatLng,
    val destination: LatLng,
    val waypoints: List<LatLng> = emptyList(),
    val profile: TravelProfile = TravelProfile.CAR,
    val withTraffic: Boolean = true,
    /** Number of alternative routes to request (0 = primary only). */
    val alternatives: Int = 0,
    /** BCP-47 language tag for instruction text, e.g. "en-US", "nl-NL". */
    val language: String = "en-US",
)

/** Result of a routing call. Providers never throw out of [RoutingProvider.route]; they return this. */
sealed interface RouteResult {
    data class Success(val routes: List<Route>) : RouteResult
    data class Failure(val kind: Kind, val message: String) : RouteResult {
        enum class Kind { NETWORK, AUTH, NO_ROUTE, BAD_REQUEST, PROVIDER_ERROR }
    }
}
