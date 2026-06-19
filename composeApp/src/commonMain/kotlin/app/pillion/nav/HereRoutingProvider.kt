package app.pillion.nav

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * HERE Routing API v8 provider — traffic-aware routing with a rich maneuver model.
 *
 * Validated against the live API (see spikes/here-routing.sh):
 *  - `actions` REQUIRES `polyline` in the `return` list (error E605013 otherwise).
 *  - departureTime must be OMITTED — the literal "now" is rejected; omitting it yields live traffic.
 *  - There is NO motorcycle transport mode; the closest is `car` (or `scooter`).
 *
 * Traffic, hazards and speed cameras come from HERE; hazards/cameras live in separate HERE APIs
 * (Traffic Incidents, Safety Cameras) and are not wired into [route] yet — flagged in capabilities.
 */
class HereRoutingProvider(
    private val apiKey: String,
    private val http: HttpClient = NavHttp.client(),
    private val baseUrl: String = "https://router.hereapi.com/v8/routes",
) : RoutingProvider {

    override val id = "here"
    override val displayName = "HERE"
    override val capabilities = ProviderCapabilities(
        trafficAware = true,
        motorcycleProfile = false,
        hazards = true,       // HERE Traffic Incidents API — not yet wired into route()
        speedCameras = true,  // HERE Safety Cameras layer — not yet wired into route()
        offline = false,
        maxAlternatives = 6,
    )

    override suspend fun route(request: RouteRequest): RouteResult {
        val resp: HereRoutesResponse = try {
            http.get(baseUrl) {
                url.parameters.apply {
                    append("transportMode", request.profile.toHereMode())
                    append("origin", "${request.origin.lat},${request.origin.lng}")
                    append("destination", "${request.destination.lat},${request.destination.lng}")
                    request.waypoints.forEach { append("via", "${it.lat},${it.lng}") }
                    if (request.alternatives > 0) append("alternatives", request.alternatives.toString())
                    append("lang", request.language)
                    // 'actions' requires 'polyline'; omit departureTime entirely for live traffic.
                    append("return", "polyline,summary,actions,instructions")
                    append("apiKey", apiKey)
                }
            }.body()
        } catch (e: ClientRequestException) {
            val kind = if (e.response.status == HttpStatusCode.Unauthorized) {
                RouteResult.Failure.Kind.AUTH
            } else {
                RouteResult.Failure.Kind.BAD_REQUEST
            }
            return RouteResult.Failure(kind, e.message ?: "HERE request failed")
        } catch (e: Exception) {
            return RouteResult.Failure(RouteResult.Failure.Kind.NETWORK, e.message ?: "network error")
        }

        val routes = resp.routes.mapNotNull { it.toRoute() }
        return if (routes.isEmpty()) {
            RouteResult.Failure(RouteResult.Failure.Kind.NO_ROUTE, "no route found")
        } else {
            RouteResult.Success(routes)
        }
    }

    companion object {
        /** Map a HERE `action` + `direction` onto the canonical [Maneuver]. */
        internal fun maneuverOf(action: String, direction: String?): Maneuver = when (action) {
            "depart" -> Maneuver.DEPART
            "arrive" -> when (direction) {
                "left" -> Maneuver.ARRIVE_LEFT
                "right" -> Maneuver.ARRIVE_RIGHT
                else -> Maneuver.ARRIVE
            }
            "continue" -> Maneuver.CONTINUE
            "keep" -> if (direction == "right") Maneuver.KEEP_RIGHT else Maneuver.KEEP_LEFT
            "uTurn" -> if (direction == "right") Maneuver.UTURN_RIGHT else Maneuver.UTURN_LEFT
            "roundaboutEnter", "roundaboutExit", "roundaboutPass" -> Maneuver.ROUNDABOUT
            "ferry" -> Maneuver.FERRY
            "merge" -> Maneuver.MERGE
            "ramp", "exit" -> when (direction) {
                "right" -> Maneuver.EXIT_RIGHT
                "left" -> Maneuver.EXIT_LEFT
                else -> Maneuver.EXIT
            }
            "turn" -> when (direction) {
                "slightlyLeft" -> Maneuver.SLIGHT_LEFT
                "slightlyRight" -> Maneuver.SLIGHT_RIGHT
                "sharpLeft" -> Maneuver.SHARP_LEFT
                "sharpRight" -> Maneuver.SHARP_RIGHT
                "left" -> Maneuver.TURN_LEFT
                "right" -> Maneuver.TURN_RIGHT
                else -> Maneuver.CONTINUE
            }
            else -> Maneuver.UNKNOWN
        }
    }
}

private fun TravelProfile.toHereMode(): String = when (this) {
    // HERE v8 has no motorcycle profile; 'car' is the closest. Use Valhalla for true moto costing.
    TravelProfile.CAR, TravelProfile.MOTORCYCLE -> "car"
    TravelProfile.SCOOTER -> "scooter"
    TravelProfile.BICYCLE -> "bicycle"
    TravelProfile.PEDESTRIAN -> "pedestrian"
}

// --- HERE v8 response DTOs (only the fields we consume) ---

@Serializable
internal data class HereRoutesResponse(val routes: List<HereRoute> = emptyList())

@Serializable
internal data class HereRoute(val sections: List<HereSection> = emptyList()) {
    fun toRoute(): Route? {
        val section = sections.firstOrNull() ?: return null
        val summary = section.summary ?: return null
        val steps = section.actions.map { a ->
            RouteStep(
                maneuver = HereRoutingProvider.maneuverOf(a.action, a.direction),
                instruction = a.instruction.orEmpty(),
                roadName = a.instruction?.extractRoadName(),
                distanceMeters = a.length,
                durationSeconds = a.duration,
                geometryOffset = a.offset,
            )
        }
        return Route(
            distanceMeters = summary.length,
            durationSeconds = summary.duration,
            baseDurationSeconds = summary.baseDuration ?: summary.duration,
            steps = steps,
            encodedGeometry = section.polyline,
            geometryFormat = section.polyline?.let { GeometryFormat.HERE_FLEXIBLE_POLYLINE },
        )
    }
}

@Serializable
internal data class HereSection(
    val summary: HereSummary? = null,
    val actions: List<HereAction> = emptyList(),
    val polyline: String? = null,
)

@Serializable
internal data class HereSummary(
    val duration: Int,
    val length: Int,
    val baseDuration: Int? = null,
)

@Serializable
internal data class HereAction(
    val action: String,
    val direction: String? = null,
    val severity: String? = null,
    val instruction: String? = null,
    val offset: Int = 0,
    val length: Int = 0,
    val duration: Int = 0,
)

/**
 * HERE actions don't carry a clean road-name field; the name is embedded in the instruction
 * ("Turn left onto Krammerstraat. Go for 62 m."). Best-effort, English-centric extraction until a
 * dedicated source is wired — returns null when no marker is found. (Use `lang` for localized text;
 * a real road-name source should replace this.)
 */
private fun String.extractRoadName(): String? {
    for (marker in listOf(" onto ", " on ")) {
        val i = indexOf(marker)
        if (i >= 0) {
            return substring(i + marker.length).substringBefore(". ").trim().ifEmpty { null }
        }
    }
    return null
}
