package app.pillion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pillion.core.AppInfo
import app.pillion.core.DashResolution
import app.pillion.core.ThemeMode
import app.pillion.core.UpdateInfo
import app.pillion.resources.Res
import app.pillion.resources.app_icon
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    quality: Int,
    onQuality: (Int) -> Unit,
    maxFps: Int,
    onMaxFps: (Int) -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    dashSupported: Boolean = false,
    dashEnabled: Boolean = false,
    dashResolution: DashResolution = DashResolution.DEFAULT,
    onDashResolution: (DashResolution) -> Unit = {},
    onSetUpDash: () -> Unit = {},
    onDisableDash: () -> Unit = {},
    bikeName: String = "",
    onChangeBike: () -> Unit = {},
    update: UpdateInfo?,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
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
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        if (update != null) {
            SectionHeader("Updates")
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(update.version, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "A new version is available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    GroupDivider()
                    Text(
                        update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        SectionHeader("Appearance")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val options = listOf(
                ThemeMode.SYSTEM to "System",
                ThemeMode.LIGHT to "Light",
                ThemeMode.DARK to "Dark",
            )
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(label)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("Motorcycle")
        SettingsGroup {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onChangeBike).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(bikeName.ifEmpty { "Not selected" }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Tap to switch head unit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Change",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("Mirroring")
        SettingsGroup {
            SettingSlider("Image quality", "$quality", quality.toFloat(), 10f, 80f) { onQuality(it.roundToInt()) }
            GroupDivider()
            SettingSlider("Max frame rate", "$maxFps fps", maxFps.toFloat(), 5f, 15f) { onMaxFps(it.roundToInt()) }
        }
        Text(
            "Higher quality looks sharper but makes the picture less smooth (about 14–15 fps at 40% " +
                "on a fast phone). The cap keeps the frame rate down to save battery and reduce heat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp),
        )

        if (dashSupported) {
            Spacer(Modifier.height(24.dp))
            SectionHeader("Dedicated dash display (experimental)")
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (dashEnabled) "On" else "Off",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Keep your nav app on the dash with the phone screen off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (dashEnabled) {
                        OutlinedButton(onClick = onDisableDash, shape = RoundedCornerShape(12.dp)) { Text("Disable") }
                    } else {
                        Button(onClick = onSetUpDash, shape = RoundedCornerShape(12.dp)) { Text("Set up") }
                    }
                }
                if (dashEnabled) {
                    GroupDivider()
                    LinkRow("Re-run setup (after a restart)") { onSetUpDash() }
                }
                GroupDivider()
                DashResolutionSelector(dashResolution, onDashResolution)
            }
            Text(
                "Casts the real app to the dash in landscape with the screen off. Run setup once " +
                    "while Wi-Fi is connected; after that it can start without Wi-Fi until restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("About")
        SettingsGroup {
            AppRow()
            GroupDivider()
            LinkRow("Source code") { uriHandler.openUri(REPO_URL) }
            GroupDivider()
            LinkRow("Report an issue") { uriHandler.openUri("$REPO_URL/issues") }
            GroupDivider()
            LinkRow("Changelog") { uriHandler.openUri("$REPO_URL/blob/main/CHANGELOG.md") }
        }

        Spacer(Modifier.height(28.dp))
        MadeByCredit { uriHandler.openUri("https://github.com/alexandrevega") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), content = content)
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier.padding(vertical = 2.dp),
    )
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
private fun DashResolutionSelector(
    selected: DashResolution,
    onSelect: (DashResolution) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clickable { choosing = true }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Map layout size", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${selected.label} - ${resolutionDetail(selected)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Change",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Map layout size") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Controls how much of the map fits on the dash. The dash always receives a " +
                            "480 × 240 image — a larger layout just shows more at once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    DashResolution.values().forEachIndexed { index, option ->
                        ResolutionDialogRow(
                            option = option,
                            selected = option == selected,
                            onClick = {
                                onSelect(option)
                                choosing = false
                            },
                        )
                        if (index != DashResolution.values().lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ResolutionDialogRow(
    option: DashResolution,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                resolutionDetail(option),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun resolutionDetail(option: DashResolution): String {
    val tenths = option.width * 10 / DashResolution.Native.width
    val scale = if (tenths % 10 == 0) "${tenths / 10}x" else "${tenths / 10}.${tenths % 10}x"
    return when (option) {
        DashResolution.Native -> "Matches the dash panel"
        DashResolution.Balanced -> "Recommended"
        DashResolution.R1920 -> "Most detail · heaviest on battery"
        else -> "$scale · more detail"
    }
}

@Composable
private fun AppRow() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(Res.drawable.app_icon),
            contentDescription = "Pillion",
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Pillion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Version ${AppInfo.VERSION}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun MadeByCredit(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Made with ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = Color(0xFFFF5C8A),
            modifier = Modifier.size(13.dp),
        )
        Text(" by ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "@alexandrevega",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
