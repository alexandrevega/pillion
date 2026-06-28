import Foundation

/// A NaviLite frame read off the wire.
struct NaviFrame { let svc: Int; let payload: [UInt8] }

/// A bidirectional NaviLite byte link the broadcast extension streams over — implemented by the bike
/// (`EAConn`, External Accessory) and the dev emulator (`TCPConn`, plain TCP). The extension is
/// transport-agnostic, exactly like the shared engine on the app side.
protocol DashConn: AnyObject {
    var logger: ((String) -> Void)? { get set }
    func connect() throws
    func write(_ bytes: [UInt8])
    func readFrame(timeout: TimeInterval) throws -> NaviFrame
    func close()
}

/// Where the extension streams. The bike is preferred when present; otherwise the dev emulator.
enum BroadcastConfig {
    static let dashProtocol = "com.garmin.navilite.data"
    /// Dev fallback: the NaviLite receiver's TCP dash. Used when no bike accessory is connected.
    /// Set this to your emulator host's IP when testing without a bike.
    static let emulatorHost = "127.0.0.1"
    static let emulatorPort: UInt16 = 7220
    /// Target frames per second. The sender paces to this and always ships the latest frame, so a
    /// fast link (WiFi emulator) doesn't flood and build latency. The bike (~15 fps) is the ceiling.
    static let maxFps: Int = 15
}
