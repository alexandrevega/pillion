package app.pillion.nav

/**
 * SKELETON — not implemented yet.
 *
 * Exists to make the extension seam concrete: a second provider with a DIFFERENT capability
 * profile than HERE (true motorcycle costing + offline/on-device, but no live traffic). The
 * registry's [RoutingProviderRegistry.pick] uses exactly these capability differences to route a
 * MOTORCYCLE request here and a traffic request to HERE.
 *
 * To implement: POST to a Valhalla `/route` endpoint (self-hosted or a hosted provider — NOT the
 * public demo server in production), `costing = "motorcycle"`, and map Valhalla's `maneuver.type`
 * onto [Maneuver] the same way [HereRoutingProvider.maneuverOf] does for HERE. Geometry comes back
 * as an encoded polyline (precision 6) -> [GeometryFormat.ENCODED_POLYLINE_5] variant.
 */
class ValhallaRoutingProvider(
    @Suppress("unused") private val baseUrl: String,
) : RoutingProvider {

    override val id = "valhalla"
    override val displayName = "Valhalla"
    override val capabilities = ProviderCapabilities(
        trafficAware = false,
        motorcycleProfile = true,
        hazards = false,
        speedCameras = false,
        offline = true,
        maxAlternatives = 2,
    )

    override suspend fun route(request: RouteRequest): RouteResult =
        RouteResult.Failure(RouteResult.Failure.Kind.PROVIDER_ERROR, "Valhalla provider not implemented yet")
}
