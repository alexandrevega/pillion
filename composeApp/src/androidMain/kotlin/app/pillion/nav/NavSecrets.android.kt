package app.pillion.nav

import app.pillion.BuildConfig

/** Android reads the HERE key from BuildConfig (fed by the gitignored local.properties). */
internal actual fun hereApiKeyOrEmpty(): String = BuildConfig.HERE_API_KEY
