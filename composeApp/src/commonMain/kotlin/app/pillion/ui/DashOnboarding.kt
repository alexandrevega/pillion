package app.pillion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.pillion.core.DashSetup
import app.pillion.core.DashStage
import app.pillion.core.MirrorSettings
import app.pillion.resources.Res
import app.pillion.resources.dash_step_dialog
import app.pillion.resources.dash_step_toggle
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Guided setup for "dedicated dash display" mode — one idea per screen, value first, with an
 * opt-out on the welcome step ([onOptOut]). Depends only on the [DashSetup] abstraction; it drives
 * pairing/connecting and reflects progress from its state.
 *
 * Screens: welcome (+ opt-out) → enable Wireless debugging → pair → ready.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashOnboarding(
    dash: DashSetup,
    settings: MirrorSettings,
    onOptOut: () -> Unit,
    onFinish: () -> Unit,
) {
    val state by dash.state.collectAsState()
    var step by remember { mutableStateOf(0) }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (step) {
            0 -> Page(
                hero = { IconHero(Icons.Filled.TwoWheeler) },
                title = "Your nav, on the dash",
                subtitle = "Keep your maps app on the bike screen — in landscape, with your phone " +
                    "screen off. Unlike mirroring, the dash shows only your app and your phone stays free.",
                step = 0,
                primary = "Get started" to { step = 1 },
                secondary = "Maybe later" to onOptOut,
            )
            1 -> Page(
                hero = { ScreenshotHero(Res.drawable.dash_step_toggle) },
                title = "Turn on Wireless debugging",
                subtitle = "In Settings → System → Developer options, switch on Wireless debugging. " +
                    "Do this on Wi-Fi (your home network or a hotspot) — it's a one-time, no-PC setup.",
                step = 1,
                primary = "Next" to { step = 2 },
                secondary = "Back" to { step = 0 },
            )
            2 -> Page(
                hero = { ScreenshotHero(Res.drawable.dash_step_dialog) },
                title = "Pair with the code",
                subtitle = "Tap \"Pair device with pairing code\" and keep that dialog open. Enter its " +
                    "port and 6-digit code below.",
                step = 2,
                primary = (if (state.stage == DashStage.Connected) "Next" else "Pair") to {
                    if (state.stage == DashStage.Connected) step = 3
                    else dash.pair("127.0.0.1", port.trim().toIntOrNull() ?: 0, code.trim())
                },
                secondary = "Back" to { step = 1 },
            ) {
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    port, { port = it }, label = { Text("Pairing port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    code, { code = it }, label = { Text("6-digit code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                PairProgress(state.stage, state.message)
            }
            else -> Page(
                hero = { IconHero(Icons.Filled.CheckCircle) },
                title = "You're ready",
                subtitle = "Open the app you want on the dash, come back, and tap Cast. Then block your " +
                    "phone — the dash keeps showing it. You only redo this after a phone restart.",
                step = 3,
                primary = "Cast foreground app" to { dash.startCast(settings) },
                secondary = "Done" to onFinish,
            ) {
                if (state.stage == DashStage.Casting) {
                    Spacer(Modifier.height(12.dp))
                    Text("✅ Casting", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ---- one-screen scaffold ----

@Composable
private fun Page(
    hero: @Composable () -> Unit,
    title: String,
    subtitle: String,
    step: Int,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        hero()
        Spacer(Modifier.height(36.dp))
        Text(
            title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        content()
        Spacer(Modifier.height(36.dp))
        PageDots(step, total = 4)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = primary.second,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text(primary.first, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        if (secondary != null) {
            TextButton(onClick = secondary.second, modifier = Modifier.fillMaxWidth()) { Text(secondary.first) }
        }
    }
}

@Composable
private fun IconHero(icon: ImageVector) {
    Box(
        Modifier.size(180.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(88.dp))
    }
}

@Composable
private fun ScreenshotHero(image: DrawableResource) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Image(
            painter = painterResource(image),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
}

@Composable
private fun PageDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            val active = i == current
            Box(
                Modifier.height(8.dp).width(if (active) 24.dp else 8.dp).clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
        }
    }
}

@Composable
private fun PairProgress(stage: DashStage, message: String?) {
    val (text, color) = when (stage) {
        DashStage.Pairing, DashStage.Connecting -> "Pairing…" to MaterialTheme.colorScheme.onSurfaceVariant
        DashStage.Connected -> "✅ Connected" to MaterialTheme.colorScheme.primary
        DashStage.Error -> "⚠ ${message ?: "Pairing failed — check the code and try again"}" to MaterialTheme.colorScheme.error
        else -> return
    }
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, textAlign = TextAlign.Center)
}
