package app.pillion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pillion.core.MirrorState
import app.pillion.core.CastMode
import app.pillion.core.UpdateInfo

@Composable
internal fun HomeScreen(
    state: MirrorState,
    update: UpdateInfo?,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    castMode: CastMode = CastMode.NONE,
    showDash: Boolean = false,
    dashReady: Boolean = false,
    onStartDash: () -> Unit = {},
    onStopDash: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (update != null) {
            UpdateReminder(update.version, onClick = onOpenSettings)
            Spacer(Modifier.height(12.dp))
        }
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.TopCenter)) { Wordmark() }
            IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        PrimaryButton(state, onStart, onStop, castMode, CastMode.MIRROR, "Mirror screen", standalone = !showDash)
        if (showDash) {
            Spacer(Modifier.height(10.dp))
            Text(if (dashReady) "Virtual Display" else "Set up Virtual Display in Settings", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            PrimaryButton(state, onStartDash, onStopDash, castMode, CastMode.DASH, "Virtual Display", dashReady)
        }
    }
}

@Composable
private fun UpdateReminder(version: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Update available — $version",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
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
            MirrorState.Broadcasting -> {
                StatusDot(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(14.dp))
                Text("Broadcasting", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open Waze or Google Maps — it's on your dash.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
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
        "Tap Start mirroring, then choose \"Entire screen\" and allow capture.",
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
private fun PrimaryButton(
    state: MirrorState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    castMode: CastMode,
    buttonMode: CastMode,
    label: String,
    available: Boolean = true,
    standalone: Boolean = false,
) {
    val active = if (standalone) state is MirrorState.Connecting || state is MirrorState.Streaming || state is MirrorState.Broadcasting else castMode == buttonMode
    Button(
        onClick = if (active) onStop else onStart,
        enabled = active || (available && castMode == CastMode.NONE),
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(
            if (active) "Stop $label" else "Start $label",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatFps(fps: Double): String {
    val rounded = (fps * 10).toInt()
    return "${rounded / 10}.${rounded % 10}"
}
