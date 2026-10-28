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
import app.pillion.core.DashResolution
import app.pillion.core.MirrorController
import app.pillion.core.MirrorSettings
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode
import app.pillion.core.UpdateChecker
import app.pillion.core.UpdateInfo
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
        var quality by rememberSaveable { mutableStateOf(40) }
        var maxFps by rememberSaveable { mutableStateOf(15) }
        var tcpHost by rememberSaveable { mutableStateOf(settingsStore?.tcpHost()?.takeIf { it.isNotEmpty() } ?: "") }
        var tcpPortText by rememberSaveable { mutableStateOf((settingsStore?.tcpPort() ?: 12345).toString()) }
        var usbOverride by remember { mutableStateOf(settingsStore?.usbOverride() ?: true) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showDashOnboarding by rememberSaveable { mutableStateOf(false) }
        var dashEnabled by remember { mutableStateOf(settingsStore?.dashEnabled() ?: false) }
        var dashResolution by remember { mutableStateOf(settingsStore?.dashResolution() ?: DashResolution.DEFAULT) }
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
                tcpHost = tcpHost,
                onTcpHost = {
                    tcpHost = it
                    // Apply as-you-type: there is no Apply button, so the field IS the commit.
                    settingsStore?.setTcpHost(it.trim())
                },
                tcpPortText = tcpPortText,
                onTcpPortText = {
                    tcpPortText = it
                    it.toIntOrNull()?.let { p -> settingsStore?.setTcpPort(p) }
                },
                usbOverride = usbOverride,
                onUsbOverride = {
                    usbOverride = it
                    settingsStore?.setUsbOverride(it)
                },
                themeMode = themeMode,
                onThemeMode = { themeMode = it; settingsStore?.setThemeMode(it) },
                dashSupported = dashSetup != null,
                dashEnabled = dashEnabled,
                dashResolution = dashResolution,
                onDashResolution = {
                    dashResolution = it
                    settingsStore?.setDashResolution(it)
                },
                onSetUpDash = { showDashOnboarding = true },
                onDisableDash = { dashEnabled = false; settingsStore?.setDashEnabled(false) },
                bikeName = profile.displayName,
                onChangeBike = { showSettings = false; changingBike = true; selectedBikeId = null },
                update = update,
                onBack = { showSettings = false },
            )
        } else {
            HomeScreen(
                state = state,
                update = update,
                onOpenSettings = { showSettings = true },
                onStart = {
                    val port = tcpPortText.toIntOrNull() ?: 12345
                    // Persist so the typed IP/port survives restarts — Start on the next launch
                    // reuses it without re-typing (the whole point of the Wi-Fi mode).
                    settingsStore?.setTcpHost(tcpHost.trim())
                    settingsStore?.setTcpPort(port)
                    controller.start(
                        MirrorSettings(
                            quality = quality,
                            maxFps = maxFps,
                            dashResolution = dashResolution,
                            // Tri-state transport opt-in: blank = USB-only, the service arms and waits
                            // for the bike cable — no scan of any kind on a Wi-Fi join. A typed IP /
                            // hostname connects DIRECT to that host on Wi-Fi join. The literal "auto"
                            // opts into gateway-first discovery with one /24 sweep per network.
                            tcpHost = tcpHost.trim().takeIf { it.isNotEmpty() },
                            tcpPort = port,
                        )
                    )
                },
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
