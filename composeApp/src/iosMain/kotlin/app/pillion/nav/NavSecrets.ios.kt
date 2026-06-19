package app.pillion.nav

// iOS dev testing uses the TCP transport; wiring a HERE key on iOS (Info.plist / build setting)
// is a TODO. Returning empty keeps the expect/actual contract satisfied for the iOS targets.
internal actual fun hereApiKeyOrEmpty(): String = ""
