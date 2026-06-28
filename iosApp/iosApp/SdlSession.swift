import Foundation
import UIKit
import SmartDeviceLink

/// Owns the SDL lifecycle for the iOS USB / iAP2 path — the iOS counterpart of the Android `SdlService`.
/// Registers as the Garmin "Motorize" identity (NAVIGATION, high bandwidth over iAP2), and once the dash
/// brings the app to an active HMI level, SDL's CarWindow streams `SdlDashHostingController` over USB.
/// Lifecycle transitions are reported back through `onState` so the shared Compose UI can reflect them.
///
/// Connecting requires the bike (or an SDL Core) to be attached over USB and the SDL EAP protocols to be
/// declared in Info.plist (`UISupportedExternalAccessoryProtocols`) with `external-accessory` background
/// mode — see iosApp/Info.plist. Runtime connection is verified on a real head unit.
final class SdlSession: NSObject, SDLManagerDelegate {

    enum State {
        case idle
        case connecting
        case streaming
        case error(String)
    }

    // Garmin Motorize identity — the head unit only streams video to an app it recognises (matches the
    // Android SdlService registration). USB-only, high bandwidth is implied by the NAVIGATION app type.
    private static let appName = "Motorize"
    private static let fullAppId = "7a5f3f25-8b82-4e0f-a173-80aefee79897"

    private let onState: (State) -> Void
    private let dashVC = SdlDashHostingController()
    private var sdlManager: SDLManager?

    init(onState: @escaping (State) -> Void) {
        self.onState = onState
        super.init()
    }

    func start() {
        guard sdlManager == nil else { return }

        let lifecycle = SDLLifecycleConfiguration(appName: Self.appName, fullAppId: Self.fullAppId)
        lifecycle.appType = .navigation

        let streaming = SDLStreamingMediaConfiguration
            .autostreamingInsecureConfiguration(withInitialViewController: dashVC)

        let config = SDLConfiguration(lifecycle: lifecycle,
                                      lockScreen: .enabled(),
                                      logging: .default(),
                                      streamingMedia: streaming,
                                      fileManager: .default(),
                                      encryption: nil)

        let manager = SDLManager(configuration: config, delegate: self)
        sdlManager = manager
        onState(.connecting)
        manager.start(readyHandler: { [weak self] (success: Bool, error: Error?) in
            if !success {
                self?.onState(.error(error?.localizedDescription ?? "SDL failed to start"))
            }
        })
    }

    func stop() {
        sdlManager?.stop()
        sdlManager = nil
        onState(.idle)
    }

    // MARK: - SDLManagerDelegate

    func managerDidDisconnect() {
        onState(.idle)
    }

    func hmiLevel(_ oldLevel: SDLHMILevel, didChangeToLevel newLevel: SDLHMILevel) {
        switch newLevel {
        case .full, .limited:
            onState(.streaming)   // dash has activated us; CarWindow is projecting
        case .background:
            onState(.connecting)
        case .none:
            onState(.connecting)  // registered but not yet selected on the dash
        default:
            break
        }
    }
}
