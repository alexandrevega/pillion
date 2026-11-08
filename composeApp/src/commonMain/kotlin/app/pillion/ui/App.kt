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
import app.pillion.core.DashDpi
import app.pillion.core.DashMargins
import app.pillion.core.DashMarginColor
import app.pillion.core.DashAppCatalog
import app.pillion.core.DashResolution
import app.pillion.core.MirrorController
import app.pillion.core.CastMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import app.pillion.core.MirrorSettings
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode
import app.pillion.core.UpdateChecker
import app.pillion.core.UpdateInfo
import app.pillion.core.WifiLink
import app.pillion.core.headunit.HeadUnitProfile
import app.pillion.core.headunit.HeadUnitRegistry

/** The public GitHub repository — shown in-app so anyone can read the source. */
const val REPO_URL = "https://github.com/alexandrevega/pillion"

/**
 * App entry point: owns the top-level UI state and routes between bike-selection (first run), Home and
 * Settings. The controller is resolved per selected [HeadUnitProfile] via [controllerFor] (DIP), so the
 * shared UI never knows whether it's driving NaviLite (Bluetooth) or SDL (USB).
 */
@Composable
fun App(
    controllerFor: (HeadUnitProfile) -> MirrorController,
    updateChecker: UpdateChecker? = null,
    settingsStore: SettingsStore? = null,
    dashSetup: DashSetup? = null,
    dashAppCatalog: DashAppCatalog? = null,
    onStartDash: ((MirrorSettings) -> Unit)? = null,
    onStopDash: (() -> Unit)? = null,
    castMode: StateFlow<CastMode> = MutableStateFlow(CastMode.NONE),
    onShareLogs: (() -> Unit)? = null,
    wifiLink: WifiLink? = null,
) {
    var themeMode by remember { mutableStateOf(settingsStore?.themeMode() ?: ThemeMode.SYSTEM) }
    PillionTheme(themeMode) {
        var selectedBikeId by remember { mutableStateOf(settingsStore?.selectedBikeId()) }
        var changingBike by remember { mutableStateOf(false) }
        val profile = selectedBikeId?.let { HeadUnitRegistry.byId(it) }

        // First run shows the full onboarding; "Change bike" (from Settings) jumps to the picker.
        if (profile == null) {
            OnboardingScreen(
                profiles = HeadUnitRegistry.all(),
                onSelect = { p -> settingsStore?.setSelectedBikeId(p.id); selectedBikeId = p.id; changingBike = false },
                skipIntro = changingBike,
            )
            return@PillionTheme
        }

        val controller = remember(profile.id) { controllerFor(profile) }
        val state by controller.state.collectAsState()
        val activeMode by castMode.collectAsState()
        var quality by rememberSaveable { mutableStateOf(40) }
        var maxFps by rememberSaveable { mutableStateOf(15) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showDashOnboarding by rememberSaveable { mutableStateOf(false) }
        var dashEnabled by remember { mutableStateOf(settingsStore?.dashEnabled() ?: false) }
        var dashResolution by remember { mutableStateOf(settingsStore?.dashResolution() ?: DashResolution.DEFAULT) }
        var dashDpi by remember { mutableStateOf(settingsStore?.dashDpi() ?: DashDpi.DEFAULT) }
        var dashMargins by remember { mutableStateOf(settingsStore?.dashMargins() ?: DashMargins()) }
        var dashMarginColor by remember { mutableStateOf(settingsStore?.dashMarginColor() ?: DashMarginColor.Black) }
        var dashAppPackage by remember { mutableStateOf(settingsStore?.dashAppPackage()) }
        val dashApps = remember(dashAppCatalog) { dashAppCatalog?.availableApps().orEmpty() }
        var dashStartError by remember { mutableStateOf<String?>(null) }
        var showDisclaimer by rememberSaveable { mutableStateOf(false) }
        var acknowledged by rememberSaveable { mutableStateOf(false) }
        var update by remember { mutableStateOf<UpdateInfo?>(null) }
        var updateDismissed by rememberSaveable { mutableStateOf(false) }
        val uriHandler = LocalUriHandler.current
        val wifiEnabled = wifiLink?.enabled?.collectAsState()?.value ?: false
        val wifiStatus = wifiLink?.status?.collectAsState()?.value

        LaunchedEffect(updateChecker) { update = updateChecker?.newerThan(AppInfo.VERSION) }
        BackHandler(enabled = showSettings || showDashOnboarding) {
            if (showDashOnboarding) showDashOnboarding = false else showSettings = false
        }

        if (showDashOnboarding && dashSetup != null) {
            DashOnboarding(
                dash = dashSetup,
                onOptOut = {
                    dashEnabled = false; settingsStore?.setDashEnabled(false); showDashOnboarding = false
                },
                onFinish = {
                    dashEnabled = true; settingsStore?.setDashEnabled(true); showDashOnboarding = false
                },
                onClose = { showDashOnboarding = false },
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
                dashResolution = dashResolution,
                onDashResolution = {
                    dashResolution = it
                    settingsStore?.setDashResolution(it)
                },
                dashDpi = dashDpi,
                onDashDpi = { dashDpi = DashDpi.snap(it); settingsStore?.setDashDpi(dashDpi) },
                dashMargins = dashMargins,
                onDashMargins = { dashMargins = DashMargins.clamped(it.left, it.bottom); settingsStore?.setDashMargins(dashMargins) },
                dashMarginColor = dashMarginColor,
                onDashMarginColor = { dashMarginColor = it; settingsStore?.setDashMarginColor(it) },
                dashApps = dashApps,
                selectedDashApp = dashAppPackage,
                onDashApp = { dashAppPackage = it; settingsStore?.setDashAppPackage(it); dashStartError = null },
                onSetUpDash = { showDashOnboarding = true },
                onDisableDash = { dashEnabled = false; settingsStore?.setDashEnabled(false) },
                bikeName = profile.displayName,
                onChangeBike = { showSettings = false; changingBike = true; selectedBikeId = null },
                update = update,
                onShareLogs = onShareLogs,
                wifiSupported = wifiLink != null && profile.requiresUsb,
                wifiEnabled = wifiEnabled,
                wifiStatus = wifiStatus,
                onWifiEnabled = { wifiLink?.setEnabled(it) },
                onWifiScan = { wifiLink?.scan() },
                onBack = { showSettings = false },
            )
        } else {
            HomeScreen(
                state = if (dashStartError != null && activeMode == CastMode.NONE) app.pillion.core.MirrorState.Error(dashStartError!!) else state,
                bikeName = if (wifiEnabled && profile.requiresUsb) "${profile.displayName} · via Wi-Fi" else profile.displayName,
                update = update,
                onOpenSettings = { showSettings = true },
                onStart = {
                    if (acknowledged) controller.start(MirrorSettings(quality, maxFps, dashResolution))
                    else showDisclaimer = true
                },
                onStop = controller::stop,
                castMode = activeMode,
                showDash = onStartDash != null && !profile.requiresUsb,
                dashReady = dashEnabled,
                onStartDash = {
                    if (dashAppPackage == null) {
                        dashStartError = "Select an app to display in Settings before starting Virtual Display."
                    } else if (dashApps.none { it.packageName == dashAppPackage }) {
                        dashStartError = "The selected app is unavailable. Choose another app in Settings."
                    } else {
                        dashStartError = null
                        onStartDash?.invoke(MirrorSettings(quality, maxFps, dashResolution, dashDpi, dashMargins, dashMarginColor))
                    }
                },
                onStopDash = { onStopDash?.invoke() },
            )
        }

        if (showDisclaimer) {
            ExperimentalDialog(onDismiss = {
                showDisclaimer = false
                acknowledged = true
                controller.start(MirrorSettings(quality, maxFps, dashResolution))
            })
        } else if (update != null && !updateDismissed) {
            UpdateDialog(
                update = update!!,
                onUpdate = { uriHandler.openUri(update!!.url); updateDismissed = true },
                onDismiss = { updateDismissed = true },
            )
        }
    }
}
