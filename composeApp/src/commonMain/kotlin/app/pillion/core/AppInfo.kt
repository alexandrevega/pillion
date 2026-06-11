package app.pillion.core

/** Static facts about this build. */
object AppInfo {
    /** Keep in sync with `versionName` in composeApp/build.gradle.kts. */
    const val VERSION = "0.1.0-alpha"

    /** GitHub "owner/repo" used for the in-app update check and the source link. */
    const val REPO = "alexandrevega/pillion"
}
