package app.pillion.core

/** A newer release than the one installed. */
data class UpdateInfo(val version: String, val notes: String, val url: String)

/**
 * Reports whether a newer release is available. The engine/UI depend on this abstraction (DIP);
 * platforms provide it (Android: GitHub Releases REST API). iOS can implement the same interface.
 */
interface UpdateChecker {
    /** Returns a newer release than [currentVersion], or null if up to date / unreachable. */
    suspend fun newerThan(currentVersion: String): UpdateInfo?
}
