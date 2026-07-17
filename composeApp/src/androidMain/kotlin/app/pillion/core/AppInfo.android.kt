package app.pillion.core

// ponytail: KMP library modules have no BuildConfig, so the version is Gradle-generated into
// BUILD_VERSION_NAME (see composeApp/build.gradle.kts) from the same catalog entry androidApp's
// versionName reads — one source of truth (libs.versions.toml `app-versionName`).
internal actual fun platformAppVersion(): String = BUILD_VERSION_NAME
