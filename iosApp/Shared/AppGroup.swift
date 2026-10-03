import Foundation

/// Resolves the App Group shared by the app and the broadcast extension. Sideloaders (AltStore /
/// SideStore) re-sign with a free Apple ID and rename the group (typically appending the team ID), and
/// record the new IDs in an `ALTAppGroups` array in the app's Info.plist. We try those first, then the
/// original ID, and only accept an ID the OS actually hands a container for.
enum AppGroup {
    static let original = "group.app.pillion"

    /// The app's own Info.plist: `Bundle.main` in the app, `.../X.app/PlugIns/Y.appex` → `.../X.app` in the extension.
    static let appInfo: [String: Any] = {
        let bundle = Bundle.main.bundleURL
        let app = bundle.pathExtension == "appex" ? bundle.deletingLastPathComponent().deletingLastPathComponent() : bundle
        return NSDictionary(contentsOf: app.appendingPathComponent("Info.plist")) as? [String: Any] ?? [:]
    }()

    /// Every ID worth trying, best first: this bundle's `ALTAppGroups`, the app's, then the original.
    static let candidates: [String] = {
        let own = Bundle.main.object(forInfoDictionaryKey: "ALTAppGroups") as? [String] ?? []
        let app = appInfo["ALTAppGroups"] as? [String] ?? []
        return own + app + [original]
    }()

    /// The usable group ID, or nil when none of the candidates has a container.
    static let id: String? = candidates.first {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) != nil
    }

    static var container: URL? {
        id.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }
    }
}
