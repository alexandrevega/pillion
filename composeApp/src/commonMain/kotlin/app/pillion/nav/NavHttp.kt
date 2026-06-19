package app.pillion.nav

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Shared HTTP client for routing providers. The engine is supplied per platform by the build
 * (OkHttp on Android, Darwin on iOS) and auto-selected from the classpath, so this stays in
 * commonMain. Providers accept an injected [HttpClient] for testing; this is the default.
 */
object NavHttp {
    val json: Json = Json { ignoreUnknownKeys = true }

    fun client(): HttpClient = HttpClient {
        // Throw ClientRequestException on 4xx so providers can map AUTH / BAD_REQUEST cleanly.
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
    }
}
