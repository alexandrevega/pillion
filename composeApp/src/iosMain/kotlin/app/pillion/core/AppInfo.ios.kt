package app.pillion.core

import platform.Foundation.NSBundle

/** iOS reads its version from the app bundle's `CFBundleShortVersionString` (Info.plist). */
internal actual fun platformAppVersion(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0.0.0"
