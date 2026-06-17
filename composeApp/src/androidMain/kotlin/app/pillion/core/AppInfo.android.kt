package app.pillion.core

import app.pillion.BuildConfig

/** Android reads its version from the build's `versionName` (BuildConfig). */
internal actual fun platformAppVersion(): String = BuildConfig.VERSION_NAME
