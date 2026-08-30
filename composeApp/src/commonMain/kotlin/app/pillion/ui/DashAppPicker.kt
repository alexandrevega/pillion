package app.pillion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pillion.core.DashApp
import app.pillion.core.InstalledApps

/**
 * Pick the single app to pin to the dash (rendered in landscape on the dedicated display, regardless
 * of what the phone does). "None" clears the selection and returns to normal mirroring.
 */
@Composable
internal fun DashAppPicker(
    installedApps: InstalledApps,
    selected: String?,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val apps = remember { installedApps.launchable() }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("Choose dash app", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            "The selected app is rendered in landscape on the dash and stays there whatever the phone " +
                "does. Choose None to mirror the phone instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp, end = 6.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                AppRow(label = "None (mirror phone)", checked = selected == null) { onSelect(null); onBack() }
            }
            items(apps, key = { it.component }) { app: DashApp ->
                AppRow(label = app.label, checked = selected == app.component) {
                    onSelect(app.component); onBack()
                }
            }
        }
    }
}

@Composable
private fun AppRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        RadioButton(selected = checked, onClick = onClick)
    }
}
