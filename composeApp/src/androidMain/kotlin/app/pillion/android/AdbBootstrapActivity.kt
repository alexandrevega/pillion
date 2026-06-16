package app.pillion.android

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dev-only proof screen for Milestone 1 of the dedicated-dash bootstrap: pair + connect to the
 * phone's own adbd over Wireless debugging entirely in-app (no PC), then run a shell command to
 * confirm we hold shell-uid privilege. Launch with:
 *
 *   adb shell am start -n app.pillion/app.pillion.android.AdbBootstrapActivity
 *
 * Setup on the phone first: Settings → Developer options → Wireless debugging → ON, then open
 * "Pair device with pairing code" and copy the IP, port, and 6-digit code into the fields here.
 */
class AdbBootstrapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { BootstrapScreen() }
            }
        }
    }

    @Composable
    private fun BootstrapScreen() {
        val scope = rememberCoroutineScope()
        val pairing by AdbPairingCoordinator.state.collectAsState()
        var host by remember { mutableStateOf("127.0.0.1") }
        var pairPort by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var pkg by remember { mutableStateOf("") }
        var log by remember { mutableStateOf("Enable Wireless debugging, then pair below.\n") }

        fun append(line: String) { log += line + "\n" }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text("Dash ADB bootstrap (dev)", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            Text(
                "No-split test: start the assistant, enable Wireless debugging, open the pairing-code " +
                    "dialog, then enter the code from Pillion's notification.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Status: ${pairing.message ?: pairing.stage.name}" +
                    (pairing.endpoint?.let { " (${it.host}:${it.port})" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    AdbPairingCoordinator.start(applicationContext)
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("0. Start no-split pairing assistant") }
            Button(
                onClick = { AdbPairingCoordinator.pairWithDiscoveredEndpoint(applicationContext, code) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pair using discovered port + typed code") }

            Spacer(Modifier.height(12.dp))
            Text("Manual fallback", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(host, { host = it }, label = { Text("Host (IP from Wireless debugging)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                pairPort, { pairPort = it }, label = { Text("Pairing port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                code, { code = it }, label = { Text("6-digit pairing code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                PillionAdb.getInstance(applicationContext)
                                    .pairDevice(host.trim(), pairPort.trim().toInt(), code.trim())
                            }
                        }.onSuccess { append("✅ Paired with $host:$pairPort") }
                            .onFailure { append("❌ Pair failed: ${it.message}") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("1. Pair") }

            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                PillionAdb.getInstance(applicationContext).autoConnectDevice(applicationContext)
                            }
                        }.onSuccess { append(if (it) "✅ Connected (mDNS auto-discovery)" else "❌ Connect returned false") }
                            .onFailure { append("❌ Connect failed: ${it.message}") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("2. Connect (auto / mDNS)") }

            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                PillionAdb.getInstance(applicationContext).runShell("getprop ro.product.model")
                            }
                        }.onSuccess { append("✅ shell: ${it.trim()}") }
                            .onFailure { append("❌ Shell failed: ${it.message}") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("3. Run test shell command") }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pkg, { pkg = it },
                label = { Text("App package (blank = foreground app)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        val target = pkg.trim().ifBlank { foregroundPackage() }
                        if (target == null) { append("❌ No foreground app (grant Usage access)"); return@launch }
                        val component = packageManager.getLaunchIntentForPackage(target)?.component?.flattenToString()
                        if (component == null) { append("❌ $target has no launcher activity"); return@launch }
                        append("Spawning detached helper for $component…")
                        withContext(Dispatchers.IO) {
                            runCatching {
                                // nohup + & : the helper outlives this ADB stream, so it keeps serving
                                // frames after Wi-Fi drops on the bike.
                                val cmd = "CLASSPATH=\$(pm path app.pillion | grep base.apk | cut -d: -f2) " +
                                    "nohup app_process / app.pillion.server.DashServer " +
                                    "960 480 160 40 480 240 $component >/dev/null 2>&1 &"
                                val stream = PillionAdb.getInstance(applicationContext).openExecStream(cmd)
                                stream.openInputStream().readBytes() // returns once backgrounded
                                stream.close()
                            }.onSuccess { runOnUiThread { append("✅ Helper spawned (detached)") } }
                                .onFailure { runOnUiThread { append("❌ Spawn failed: ${it.message}") } }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("4. Launch helper (detached)") }

            Button(
                onClick = {
                    scope.launch {
                        append("Reading frames over loopback TCP…")
                        withContext(Dispatchers.IO) {
                            val src = DashStreamScreenSource()
                            src.start()
                            var got = 0
                            repeat(20) {
                                Thread.sleep(400)
                                src.latestFrame()?.let { f ->
                                    got++
                                    if (got <= 3) runOnUiThread { append("frame: ${f.size} bytes") }
                                }
                            }
                            src.stop()
                            runOnUiThread {
                                append(if (got > 0) "✅ Received $got frames over loopback TCP" else "❌ No frames (helper running?)")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("5. Read frames (loopback TCP)") }

            Spacer(Modifier.height(16.dp))
            Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }

    /** The most recent foreground package other than Pillion (the app to promote at screen-block). */
    private fun foregroundPackage(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 60_000, now) }.getOrNull() ?: return null
        val event = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName != packageName) {
                pkg = event.packageName
            }
        }
        return pkg
    }
}
