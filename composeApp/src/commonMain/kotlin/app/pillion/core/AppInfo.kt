package app.pillion.core

/** Static facts about this build. */
object AppInfo {
    /**
     * The user-facing version, sourced from **each platform's own build config** — Android's
     * `versionName` (via `BuildConfig`) and iOS's `CFBundleShortVersionString`. So the platforms
     * version independently (Android and iOS can be at different versions), each set in one place.
     */
    val VERSION: String get() = platformAppVersion()

    /** GitHub "owner/repo" used for the in-app update check and the source link. */
    const val REPO = "alexandrevega/pillion"
}

/** The platform build's user-facing version string. */
internal expect fun platformAppVersion(): String
