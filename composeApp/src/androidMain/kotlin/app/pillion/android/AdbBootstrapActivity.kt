package app.pillion.android

import android.os.Bundle
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
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { BootstrapScreen() }
            }
        }
    }

    @Composable
    private fun BootstrapScreen() {
        val scope = rememberCoroutineScope()
        var host by remember { mutableStateOf("127.0.0.1") }
        var pairPort by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var log by remember { mutableStateOf("Enable Wireless debugging, then pair below.\n") }

        fun append(line: String) { log += line + "\n" }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text("Dash ADB bootstrap (dev)", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
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

            Spacer(Modifier.height(16.dp))
            Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}
