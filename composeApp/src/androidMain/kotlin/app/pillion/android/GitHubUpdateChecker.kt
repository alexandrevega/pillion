package app.pillion.android

import app.pillion.core.SemVer
import app.pillion.core.UpdateChecker
import app.pillion.core.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer build via the public REST API (no auth). Note: it only sees
 * releases once the repository is public. The list endpoint is used so prereleases (alphas) count.
 * Network + parsing run off the main thread; any failure is swallowed and treated as "no update".
 */
class GitHubUpdateChecker(private val repo: String) : UpdateChecker {
    override suspend fun newerThan(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://api.github.com/repos/$repo/releases?per_page=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val releases = JSONArray(body)
            if (releases.length() == 0) return@runCatching null
            val release = releases.getJSONObject(0)
            val tag = release.optString("tag_name")
            if (tag.isBlank() || !SemVer.isNewer(tag, currentVersion)) return@runCatching null
            UpdateInfo(
                version = tag,
                notes = release.optString("body").ifBlank { release.optString("name") },
                url = release.optString("html_url", "https://github.com/$repo/releases"),
            )
        }.getOrNull()
    }
}
