package app.pillion.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.pillion.core.DashSetup
import app.pillion.core.DashStage
import app.pillion.core.MirrorSettings

/**
 * Guided setup for "dedicated dash display" mode. Honest about the tradeoffs up front and lets the
 * user back out ([onOptOut]) at any time. Depends only on the [DashSetup] abstraction — it drives
 * pairing/connecting and reflects progress from its state.
 *
 * Steps: Intro (+ opt-out) → enable Wireless debugging → pair → done.
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
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("Dedicated dash display", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Experimental — step ${step + 1} of 4",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))

            when (step) {
                0 -> IntroStep(onNext = { step = 1 }, onOptOut = onOptOut)
                1 -> EnableDebuggingStep(onNext = { step = 2 }, onBack = { step = 0 })
                2 -> PairStep(
                    host = host, onHost = { host = it },
                    port = port, onPort = { port = it },
                    code = code, onCode = { code = it },
                    stage = state.stage, message = state.message,
                    onPair = { dash.pair(host.trim(), port.trim().toIntOrNull() ?: 0, code.trim()) },
                    onConnected = { step = 3 },
                    onBack = { step = 1 },
                )
                else -> DoneStep(
                    onStartCast = { dash.startCast(settings) },
                    onFinish = onFinish,
                    casting = state.stage == DashStage.Casting,
                )
            }
        }
    }
}

@Composable
private fun IntroStep(onNext: () -> Unit, onOptOut: () -> Unit) {
    Body(
        "What this does",
        "Keeps your nav app on the bike dash with the phone screen off, in landscape. Unlike plain " +
            "mirroring, the dash shows just your app and your phone stays free.",
    )
    Body(
        "What it needs — honestly",
        "• A one-time setup using Android's Wireless debugging (no PC, no root).\n" +
            "• You must run setup while on Wi-Fi (e.g. at home) before each ride; after that it keeps " +
            "working with no connection.\n" +
            "• It has to be redone after a phone restart.",
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Set it up") }
    TextButton(onClick = onOptOut, modifier = Modifier.fillMaxWidth()) { Text("No thanks — keep mirroring") }
}

@Composable
private fun EnableDebuggingStep(onNext: () -> Unit, onBack: () -> Unit) {
    Body(
        "1. Turn on Wireless debugging",
        "Open Settings → System → Developer options → Wireless debugging and switch it ON. Make sure " +
            "you're connected to Wi-Fi (your home network or a hotspot).",
    )
    ScreenshotSlot("Settings → Developer options → Wireless debugging")
    StepButtons(backLabel = "Back", onBack = onBack, nextLabel = "Next", onNext = onNext)
}

@Composable
private fun PairStep(
    host: String, onHost: (String) -> Unit,
    port: String, onPort: (String) -> Unit,
    code: String, onCode: (String) -> Unit,
    stage: DashStage, message: String?,
    onPair: () -> Unit, onConnected: () -> Unit, onBack: () -> Unit,
) {
    Body(
        "2. Pair with the code",
        "In Wireless debugging tap \"Pair device with pairing code\". Keep that dialog open (use " +
            "split-screen) and copy the port and 6-digit code below. Host stays 127.0.0.1.",
    )
    ScreenshotSlot("\"Pair device with pairing code\" dialog — port & code")
    OutlinedTextField(host, onHost, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        port, onPort, label = { Text("Pairing port") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        code, onCode, label = { Text("6-digit code") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    when (stage) {
        DashStage.Pairing, DashStage.Connecting -> Status("Pairing…")
        DashStage.Connected -> Status("✅ Connected", MaterialTheme.colorScheme.primary)
        DashStage.Error -> Status("❌ ${message ?: "failed"}", MaterialTheme.colorScheme.error)
        else -> {}
    }
    if (stage == DashStage.Connected) {
        Button(onClick = onConnected, modifier = Modifier.fillMaxWidth()) { Text("Next") }
    } else {
        StepButtons(backLabel = "Back", onBack = onBack, nextLabel = "Pair", onNext = onPair)
    }
}

@Composable
private fun DoneStep(onStartCast: () -> Unit, onFinish: () -> Unit, casting: Boolean) {
    Body(
        "You're set up",
        "Open the app you want on the dash (e.g. your maps app), come back, and tap Cast. Then block " +
            "your phone — the dash keeps showing it. You only redo the pairing after a restart.",
    )
    Button(onClick = onStartCast, modifier = Modifier.fillMaxWidth()) {
        Text(if (casting) "Casting — recast foreground app" else "Cast foreground app to dash")
    }
    TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

// ---- small shared pieces ----

@Composable
private fun Body(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
}

/** Placeholder for an annotated screenshot (replaced with a real cropped image resource). */
@Composable
private fun ScreenshotSlot(caption: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(160.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Status(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun StepButtons(backLabel: String, onBack: () -> Unit, nextLabel: String, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(backLabel) }
        Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text(nextLabel) }
    }
}
