package app.pillion.nav

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable

/**
 * HERE Geocoding & Search — turns an address or place query into coordinates so the rider can type
 * a destination instead of lat/lng. BYOK (same HERE key as routing).
 */
class HereGeocoder(
    private val apiKey: String,
    private val http: HttpClient = NavHttp.client(),
    private val baseUrl: String = "https://geocode.search.hereapi.com/v1/geocode",
) {
    /** Best match for [query], or null if nothing was found / the request failed. */
    suspend fun geocode(query: String): LatLng? {
        val resp: HereGeoResponse = try {
            http.get(baseUrl) {
                url.parameters.apply {
                    append("q", query)
                    append("limit", "1")
                    append("apiKey", apiKey)
                }
            }.body()
        } catch (e: Exception) {
            return null
        }
        val pos = resp.items.firstOrNull()?.position ?: return null
        return LatLng(pos.lat, pos.lng)
    }
}

@Serializable
internal data class HereGeoResponse(val items: List<HereGeoItem> = emptyList())

@Serializable
internal data class HereGeoItem(val position: HereGeoPos? = null)

@Serializable
internal data class HereGeoPos(val lat: Double, val lng: Double)
