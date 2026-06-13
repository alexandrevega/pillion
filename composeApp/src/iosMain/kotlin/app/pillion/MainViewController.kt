package app.pillion

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import app.pillion.core.ByteChannel
import app.pillion.core.PreviewController
import app.pillion.core.ScreenSource
import app.pillion.ios.ExternalAccessoryByteChannel
import app.pillion.ios.IosMirrorController
import app.pillion.ios.IosSettingsStore
import app.pillion.ios.NetworkByteChannel
import app.pillion.ios.ReplayKitScreenSource
import app.pillion.ui.App
import platform.UIKit.UIViewController

/**
 * Real iOS entry point. Swift supplies the platform glue (transport + screen source) by handing in
 * a [ByteChannel] / [ScreenSource]. The [MainViewControllerForBike] / [MainViewControllerForEmulator]
 * helpers below wire up the stock iOS implementations so Swift can call a single function.
 *
 * Transport availability by build (the Swift shell enforces it with `#if DEBUG`):
 * - **Release:** bike only ([MainViewControllerForBike]).
 * - **Debug/dev:** *both* — pick the bike to test on the real dash, or the emulator loopback to
 *   iterate without it. So a dev build still talks to the bike; it just *also* offers the emulator.
 */
fun MainViewController(channel: ByteChannel, screen: ScreenSource): UIViewController =
    ComposeUIViewController {
        val controller = remember { IosMirrorController(channel, screen) }
        App(controller = controller, updateChecker = null, settingsStore = IosSettingsStore())
    }

/**
 * Stream to the real dash as an MFi External Accessory. Available in **every** build — a dev/debug
 * build uses this to test on the bike without making a release build. (See [ExternalAccessoryByteChannel];
 * only runs against the actual bike, since MFi auth is hardware-enforced by iOS.)
 */
fun MainViewControllerForBike(protocolString: String): UIViewController =
    MainViewController(ExternalAccessoryByteChannel(protocolString), ReplayKitScreenSource())

/**
 * **Dev/DEBUG builds only.** Stream to the NaviLite **emulator** (`receiver.py`, TCP dash) over WiFi —
 * e.g. `MainViewControllerForEmulator(host: "192.168.1.183")` from Swift. Lets the Simulator or a
 * device exercise the full iOS stack (capture → JPEG → handshake → stream) without the bike. This is
 * a dev loopback, not the dash link — keep it behind `#if DEBUG` so it never ships in a release build.
 */
fun MainViewControllerForEmulator(host: String, port: Int = 7220): UIViewController =
    MainViewController(NetworkByteChannel(host, port), ReplayKitScreenSource())

/** UI-only preview entry (no transport) — boots the shared UI to validate it on iOS. */
fun MainViewControllerPreview(): UIViewController =
    ComposeUIViewController {
        App(controller = PreviewController(), updateChecker = null, settingsStore = IosSettingsStore())
    }
