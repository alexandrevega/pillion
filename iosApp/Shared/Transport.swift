import Foundation

/// A NaviLite frame read off the wire.
struct NaviFrame { let svc: Int; let payload: [UInt8] }

/// A bidirectional NaviLite byte link the broadcast extension streams over — implemented by the bike
/// (`EAConn`, External Accessory) and the dev emulator (`TCPConn`, plain TCP). The extension is
/// transport-agnostic, exactly like the shared engine on the app side.
protocol DashConn: AnyObject {
    var logger: ((String) -> Void)? { get set }
    /// True once the link has died or `close()` was called; reads throw from then on.
    var isClosed: Bool { get }
    func connect() throws
    func write(_ bytes: [UInt8])
    func readFrame(timeout: TimeInterval) throws -> NaviFrame
    func close()
}

/// Where the extension streams: the bike (Debug builds may fall back to the dev emulator).
enum BroadcastConfig {
    static let dashProtocol = "com.garmin.navilite.data"
    #if DEBUG
    /// Dev fallback: the NaviLite receiver's TCP dash. Used when no bike accessory is connected (Debug
    /// builds only — it doesn't exist on users' phones). Set the host to your emulator's IP.
    static let emulatorHost = "127.0.0.1"
    static let emulatorPort: UInt16 = 7220
    #endif
    /// Fallback frame-rate cap when no live setting is available (see [liveMaxFps]).
    static let maxFps: Int = 15

    /// App Group shared with the container app so the extension can read the user's live Settings
    /// (the extension is a separate process and can't see the app's own UserDefaults).
    private static var shared: UserDefaults? { AppGroup.id.flatMap { UserDefaults(suiteName: $0) } }

    // Each reader falls back to a safe default if the group is unavailable (e.g. a re-signer that
    // didn't carry the entitlement) — so the stream still works, just not slider-driven.
    static func liveMaxFps() -> Int {
        let v = shared?.integer(forKey: "stream.maxFps") ?? 0
        return (5...30).contains(v) ? v : maxFps
    }
    /// App stores JPEG quality as 10…80; map to CoreImage's 0…1.
    static func liveJpegQuality() -> Double {
        let v = shared?.integer(forKey: "stream.quality") ?? 0
        return (10...80).contains(v) ? Double(v) / 100.0 : 0.4
    }
}
