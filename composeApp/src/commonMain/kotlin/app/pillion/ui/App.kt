package app.pillion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import app.pillion.core.AppInfo
import app.pillion.core.DashSetup
import app.pillion.core.MirrorController
import app.pillion.core.MirrorSettings
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode
import app.pillion.core.UpdateChecker
import app.pillion.core.UpdateInfo

/** The public GitHub repository — shown in-app so anyone can read the source. */
const val REPO_URL = "https://github.com/alexandrevega/pillion"

/**
 * App entry point: owns the top-level UI state and routes between the Home and Settings screens,
 * gating the experimental and update dialogs. Screen content lives in the [HomeScreen] /
 * [SettingsScreen] composables; this stays a thin orchestrator.
 */
@Composable
fun App(
    controller: MirrorController,
    updateChecker: UpdateChecker? = null,
    settingsStore: SettingsStore? = null,
    dashSetup: DashSetup? = null,
) {
    var themeMode by remember { mutableStateOf(settingsStore?.themeMode() ?: ThemeMode.SYSTEM) }
    PillionTheme(themeMode) {
        val state by controller.state.collectAsState()
        var quality by rememberSaveable { mutableStateOf(40) }
        var maxFps by rememberSaveable { mutableStateOf(15) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showDashOnboarding by rememberSaveable { mutableStateOf(false) }
        var dashEnabled by remember { mutableStateOf(settingsStore?.dashEnabled() ?: false) }
        var showDisclaimer by rememberSaveable { mutableStateOf(true) }
        var update by remember { mutableStateOf<UpdateInfo?>(null) }
        var updateDismissed by rememberSaveable { mutableStateOf(false) }
        val uriHandler = LocalUriHandler.current

        LaunchedEffect(updateChecker) { update = updateChecker?.newerThan(AppInfo.VERSION) }
        BackHandler(enabled = showSettings || showDashOnboarding) {
            if (showDashOnboarding) showDashOnboarding = false else showSettings = false
        }

        if (showDashOnboarding && dashSetup != null) {
            DashOnboarding(
                dash = dashSetup,
                settings = MirrorSettings(quality, maxFps),
                onOptOut = {
                    dashEnabled = false; settingsStore?.setDashEnabled(false); showDashOnboarding = false
                },
                onFinish = {
                    dashEnabled = true; settingsStore?.setDashEnabled(true); showDashOnboarding = false
                },
            )
        } else if (showSettings) {
            SettingsScreen(
                quality = quality,
                onQuality = { quality = it },
                maxFps = maxFps,
                onMaxFps = { maxFps = it },
                themeMode = themeMode,
                onThemeMode = { themeMode = it; settingsStore?.setThemeMode(it) },
                dashSupported = dashSetup != null,
                dashEnabled = dashEnabled,
                onSetUpDash = { showDashOnboarding = true },
                onDisableDash = { dashEnabled = false; settingsStore?.setDashEnabled(false) },
                update = update,
                onBack = { showSettings = false },
            )
        } else {
            HomeScreen(
                state = state,
                update = update,
                onOpenSettings = { showSettings = true },
                onStart = { controller.start(MirrorSettings(quality, maxFps)) },
                onStop = controller::stop,
            )
        }

        if (showDisclaimer) {
            ExperimentalDialog(onDismiss = { showDisclaimer = false })
        } else if (update != null && !updateDismissed) {
            UpdateDialog(
                update = update!!,
                onUpdate = { uriHandler.openUri(update!!.url); updateDismissed = true },
                onDismiss = { updateDismissed = true },
            )
        }
    }
}
