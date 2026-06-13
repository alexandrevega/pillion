package app.pillion

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import app.pillion.core.ByteChannel
import app.pillion.core.PreviewController
import app.pillion.core.ScreenSource
import app.pillion.ios.IosMirrorController
import app.pillion.ios.IosSettingsStore
import app.pillion.ui.App
import platform.UIKit.UIViewController

/**
 * Real iOS entry point. Swift supplies the platform glue (External Accessory transport + ReplayKit
 * screen source) by conforming to the [ByteChannel] / [ScreenSource] interfaces, then hands them in.
 */
fun MainViewController(channel: ByteChannel, screen: ScreenSource): UIViewController =
    ComposeUIViewController {
        val controller = remember { IosMirrorController(channel, screen) }
        App(controller = controller, updateChecker = null, settingsStore = IosSettingsStore())
    }

/** UI-only preview entry (no transport) — boots the shared UI to validate it on iOS. */
fun MainViewControllerPreview(): UIViewController =
    ComposeUIViewController {
        App(controller = PreviewController(), updateChecker = null, settingsStore = IosSettingsStore())
    }
