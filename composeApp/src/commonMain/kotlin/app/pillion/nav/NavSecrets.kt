package app.pillion.nav

/**
 * The HERE API key for dev/testing, sourced from each platform's build config.
 *
 * Android reads `BuildConfig.HERE_API_KEY`, fed from the gitignored `local.properties`
 * (`here.api.key=...`) — so the key never enters git, only your local debug build. iOS returns
 * empty for now (Classic-Bluetooth testing is Android-only; the iOS path is the TCP dev transport
 * or MFi on the bike). Empty string means "not configured".
 */
internal expect fun hereApiKeyOrEmpty(): String
