package app.pillion.android

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pillion.nav.HereGeocoder
import app.pillion.nav.LatLng
import app.pillion.nav.hereApiKeyOrEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dev navigation launcher: type a destination (address or lat,lng), optionally an origin (defaults
 * to current GPS), pick Live GPS (the bike) or Simulate (the desk), and start [NavService].
 *
 *   adb shell am start -n app.pillion/app.pillion.android.NavDevActivity   (or its launcher icon)
 */
class NavDevActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS),
                1,
            )
        }
        setContent { MaterialTheme { NavDevScreen() } }
    }

    @Composable
    private fun NavDevScreen() {
        val scope = rememberCoroutineScope()
        var dest by remember { mutableStateOf("") }
        var origin by remember { mutableStateOf("") }
        var useCurrent by remember { mutableStateOf(true) }
        var live by remember { mutableStateOf(true) }
        var status by remember { mutableStateOf("Enter a destination, then Start.") }

        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Pillion — live navigation (dev)", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = dest, onValueChange = { dest = it },
                label = { Text("Destination (address or lat,lng)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useCurrent, onCheckedChange = { useCurrent = it })
                Text("  Use current location as origin")
            }
            if (!useCurrent) {
                OutlinedTextField(
                    value = origin, onValueChange = { origin = it },
                    label = { Text("Origin (address or lat,lng)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = live, onCheckedChange = { live = it })
                Text("  Live GPS (off = simulate along route)")
            }
            Button(
                onClick = {
                    scope.launch {
                        status = "resolving…"
                        val d = resolve(dest)
                        if (d == null) { status = "destination not found"; return@launch }
                        val o = if (useCurrent) null else resolve(origin)
                        if (!useCurrent && o == null) { status = "origin not found"; return@launch }
                        val intent = Intent(this@NavDevActivity, NavService::class.java).apply {
                            putExtra("dlat", d.lat); putExtra("dlng", d.lng)
                            if (o != null) { putExtra("olat", o.lat); putExtra("olng", o.lng) }
                            putExtra("live", live)
                        }
                        startForegroundService(intent)
                        status = "navigating → ${"%.5f".format(d.lat)}, ${"%.5f".format(d.lng)}" +
                            if (live) "  (live GPS)" else "  (simulated)"
                    }
                },
                enabled = dest.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start navigation") }
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }

    /** Parse "lat,lng" directly, else geocode the address via HERE. */
    private suspend fun resolve(query: String): LatLng? {
        parseLatLng(query)?.let { return it }
        val key = hereApiKeyOrEmpty()
        if (key.isBlank()) return null
        return withContext(Dispatchers.IO) { HereGeocoder(key).geocode(query) }
    }

    private fun parseLatLng(s: String): LatLng? {
        val m = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$""").find(s) ?: return null
        return LatLng(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
    }
}
