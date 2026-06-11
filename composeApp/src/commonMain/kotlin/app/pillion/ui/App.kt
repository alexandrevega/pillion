package app.pillion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pillion.core.AppInfo
import app.pillion.core.MirrorController
import app.pillion.core.MirrorSettings
import app.pillion.core.MirrorState
import app.pillion.core.UpdateChecker
import app.pillion.core.UpdateInfo
import kotlin.math.roundToInt

/** The public GitHub repository — shown in-app so anyone can read the source. */
const val REPO_URL = "https://github.com/alexandrevega/pillion"

private val DarkColors = darkColorScheme(
    primary = Color(0xFF34D8C8),
    onPrimary = Color(0xFF00201C),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF1E242C),
    onSurfaceVariant = Color(0xFF8B95A3),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF24171A),
    onErrorContainer = Color(0xFFFFB4AB),
    outline = Color(0xFF2A313B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00897B),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDF0F3),
    onSurfaceVariant = Color(0xFF5A636E),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFD3D8DE),
)

@Composable
fun App(controller: MirrorController, updateChecker: UpdateChecker? = null) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val state by controller.state.collectAsState()
            var quality by rememberSaveable { mutableStateOf(40) }
            var maxFps by rememberSaveable { mutableStateOf(15) }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            var showDisclaimer by rememberSaveable { mutableStateOf(true) }
            var update by remember { mutableStateOf<UpdateInfo?>(null) }

            LaunchedEffect(updateChecker) {
                update = updateChecker?.newerThan(AppInfo.VERSION)
            }
            BackHandler(enabled = showSettings) { showSettings = false }

            if (showSettings) {
                SettingsScreen(
                    quality = quality,
                    onQuality = { quality = it },
                    maxFps = maxFps,
                    onMaxFps = { maxFps = it },
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

            if (showDisclaimer) ExperimentalDialog(onDismiss = { showDisclaimer = false })
        }
    }
}

@Composable
private fun HomeScreen(
    state: MirrorState,
    update: UpdateInfo?,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.TopCenter)) { Wordmark() }
            IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                Text("⚙", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (update != null) {
            Spacer(Modifier.height(12.dp))
            UpdateBanner(update)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state is MirrorState.Idle) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ConnectGuide()
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    StatusDisplay(state)
                }
            }
        }
        PrimaryButton(state, onStart, onStop)
        Spacer(Modifier.height(4.dp))
        SourceLink()
    }
}

@Composable
private fun SettingsScreen(
    quality: Int,
    onQuality: (Int) -> Unit,
    maxFps: Int,
    onMaxFps: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(20.dp))
        SettingsCard(quality, onQuality, maxFps, onMaxFps)
        Spacer(Modifier.height(24.dp))
        AboutSection()
    }
}

@Composable
private fun ExperimentalDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text("⚠️", fontSize = 26.sp) },
        title = { Text("Experimental software") },
        text = {
            Text(
                "Pillion is experimental and provided as-is — use it entirely at your own risk. " +
                    "Set your route before you ride and keep your eyes on the road; never interact " +
                    "with your phone while moving. Not affiliated with, or endorsed by, Yamaha or Garmin.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("I understand") }
        },
    )
}

@Composable
private fun UpdateBanner(update: UpdateInfo) {
    val uriHandler = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Update available",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(update.version, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { uriHandler.openUri(update.url) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text("Get", fontWeight = FontWeight.SemiBold)
                }
            }
            if (update.notes.isNotBlank()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) "Hide changelog" else "What's new",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    Text(
                        update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Pillion",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        Text(
            "your screen, on the bike dash",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDisplay(state: MirrorState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            MirrorState.Idle -> Unit
            MirrorState.Connecting -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("Connecting to dash…", style = MaterialTheme.typography.titleMedium)
            }
            is MirrorState.Streaming -> {
                Text(
                    formatFps(state.fps),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "fps",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "mirroring • ${state.kbPerFrame} KB per frame",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is MirrorState.Error -> {
                StatusDot(MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(14.dp))
                Text("Disconnected", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun ConnectGuide() {
    val steps = listOf(
        "Pair your phone with the bike in your Bluetooth settings (one time only).",
        "Mount the phone in landscape and turn on auto-rotate, so the map fills the dash.",
        "On the bike, switch the dash to Navigation mode.",
        "Tap Start mirroring below, then allow screen capture.",
        "Open Waze or Google Maps — it appears on your dash.",
    )
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Before you ride",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        steps.forEachIndexed { index, step -> StepRow(index + 1, step) }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.size(14.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SettingsCard(
    quality: Int,
    onQuality: (Int) -> Unit,
    maxFps: Int,
    onMaxFps: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                "Battery & quality",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            SettingSlider("Image quality", "$quality", quality.toFloat(), 10f, 80f) {
                onQuality(it.roundToInt())
            }
            SettingSlider("Max frame rate", "$maxFps fps", maxFps.toFloat(), 5f, 15f) {
                onMaxFps(it.roundToInt())
            }
            Text(
                "Higher quality is sharper but lowers the frame rate your phone can push " +
                    "(≈14–15 fps at 40% on a fast phone). The cap limits frames to save battery and heat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: String,
    current: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = current, onValueChange = onChange, valueRange = min..max)
    }
}

@Composable
private fun AboutSection() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "About",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Version ${AppInfo.VERSION}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Pillion mirrors your phone screen to a Garmin-powered motorcycle dash over Bluetooth. " +
                "It's experimental and open source — not affiliated with, or endorsed by, Yamaha or Garmin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SourceLink(centered = false)
    }
}

@Composable
private fun PrimaryButton(state: MirrorState, onStart: () -> Unit, onStop: () -> Unit) {
    val active = state is MirrorState.Streaming || state is MirrorState.Connecting
    Button(
        onClick = if (active) onStop else onStart,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(
            if (active) "Stop mirroring" else "Start mirroring",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SourceLink(centered: Boolean = true) {
    val uriHandler = LocalUriHandler.current
    val button: @Composable () -> Unit = {
        TextButton(onClick = { uriHandler.openUri(REPO_URL) }) {
            Text(
                "View source on GitHub  ↗",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (centered) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { button() }
    } else {
        button()
    }
}

private fun formatFps(fps: Double): String {
    val rounded = (fps * 10).toInt()
    return "${rounded / 10}.${rounded % 10}"
}
