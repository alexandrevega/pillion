package app.pillion.android

import app.pillion.core.SemVer
import app.pillion.core.UpdateChecker
import app.pillion.core.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer build via the public REST API (no auth). Note: it only sees
 * releases once the repository is public. Prereleases (alphas) count.
 *
 * It picks the newest release that is both newer than [currentVersion] **and** ships an `.apk` asset,
 * so iOS-only releases (IPA-only) never trigger an Android prompt, and returns the APK's direct
 * download URL so "Get" downloads the file rather than opening the release page. Network + parsing run
 * off the main thread; any failure is swallowed and treated as "no update".
 */
class GitHubUpdateChecker(private val repo: String) : UpdateChecker {
    override suspend fun newerThan(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://api.github.com/repos/$repo/releases?per_page=10")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val releases = JSONArray(body)
            // Releases come back newest-first; return the first applicable one.
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft")) continue
                val tag = release.optString("tag_name")
                if (tag.isBlank() || !SemVer.isNewer(tag, currentVersion)) continue
                val apkUrl = apkDownloadUrl(release.optJSONArray("assets")) ?: continue
                return@runCatching UpdateInfo(
                    version = tag,
                    notes = release.optString("body").ifBlank { release.optString("name") },
                    url = apkUrl,
                )
            }
            null
        }.getOrNull()
    }

    private fun apkDownloadUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val asset: JSONObject = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                val url = asset.optString("browser_download_url")
                if (url.isNotBlank()) return url
            }
        }
        return null
    }
}
