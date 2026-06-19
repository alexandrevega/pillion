package app.pillion.nav

/**
 * What a provider can do. Lets the app pick the right provider per request and surface feature
 * support in the UI (e.g. "traffic" only when the active provider has it).
 */
data class ProviderCapabilities(
    val trafficAware: Boolean,
    val motorcycleProfile: Boolean,
    val hazards: Boolean,
    val speedCameras: Boolean,
    val offline: Boolean,
    val maxAlternatives: Int,
)

/**
 * A pluggable source of routes.
 *
 * Concrete impls today: [HereRoutingProvider] (traffic + hazards, no moto profile). Planned:
 * Valhalla (moto costing + offline, no live traffic), TomTom/Mapbox (traffic). Adding one means
 * writing a single file and registering it — nothing else in the app needs to change.
 *
 * Implementations must be coroutine-safe (a single instance is shared) and must never throw out
 * of [route] — they return [RouteResult.Failure] instead.
 */
interface RoutingProvider {
    /** Stable id, e.g. "here", "valhalla". Used by the registry and persisted in settings. */
    val id: String
    val displayName: String
    val capabilities: ProviderCapabilities

    suspend fun route(request: RouteRequest): RouteResult
}

/**
 * Registry of available providers. Register at startup; select by id or by required capability.
 * Centralizing selection here (rather than hardcoding a provider at call sites) is what keeps
 * "integrate another provider" a one-liner.
 */
class RoutingProviderRegistry {
    private val byId = LinkedHashMap<String, RoutingProvider>()

    fun register(provider: RoutingProvider): RoutingProviderRegistry {
        byId[provider.id] = provider
        return this
    }

    fun get(id: String): RoutingProvider? = byId[id]
    fun all(): List<RoutingProvider> = byId.values.toList()

    /** First registered provider whose capabilities satisfy [predicate], or null. */
    fun firstWith(predicate: (ProviderCapabilities) -> Boolean): RoutingProvider? =
        byId.values.firstOrNull { predicate(it.capabilities) }

    /**
     * Best provider for [request]: honors a motorcycle profile and a traffic requirement when set,
     * falling back to the first registered provider if nothing matches exactly.
     */
    fun pick(request: RouteRequest): RoutingProvider? {
        val needsMoto = request.profile == TravelProfile.MOTORCYCLE
        return byId.values.firstOrNull { p ->
            (!needsMoto || p.capabilities.motorcycleProfile) &&
                (!request.withTraffic || p.capabilities.trafficAware)
        } ?: byId.values.firstOrNull()
    }
}

/**
 * The basemap/tile source is a SEPARATE choice from the routing provider — they compose freely
 * (e.g. HERE routing data drawn over MapLibre+OSM tiles, or HERE routing over HERE tiles).
 * Recorded here so the decision stays explicit and swappable; rendering itself is platform code.
 */
enum class BasemapSource {
    /** HERE raster/vector tiles — single-vendor with routing, online-only, ToS-clean within HERE. */
    HERE_TILES,

    /** MapLibre GL + OSM/Protomaps tiles — self-rendered, offline-capable (verify HERE display terms). */
    MAPLIBRE_OSM,

    /** Apple Maps (MapKit) — iOS only; what the contributor's prototype currently uses. */
    APPLE_MAPS,
}
