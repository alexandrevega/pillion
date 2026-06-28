package app.pillion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pillion.core.headunit.HeadUnitProfile
import app.pillion.core.headunit.VideoPreference
import app.pillion.resources.Res
import app.pillion.resources.app_icon
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/** The app's own product name. (StreetCross / Motorize are Garmin's apps — used only as compatibility
 *  labels on the bike cards, never as our brand.) */
private const val BRAND = "Pillion"

/**
 * First-run onboarding: a paged intro that explains what the app does and how it connects, ending with
 * head-unit selection. Selecting a bike is what completes onboarding ([onSelect]). When the user revisits
 * via Settings ▸ "Change", [skipIntro] jumps straight to the selection page.
 */
@Composable
fun OnboardingScreen(
    profiles: List<HeadUnitProfile>,
    onSelect: (HeadUnitProfile) -> Unit,
    skipIntro: Boolean = false,
) {
    val introPages = 2
    val pageCount = introPages + 1            // intro pages + the selection page
    val selectionPage = introPages
    val pager = rememberPagerState(initialPage = if (skipIntro) selectionPage else 0) { pageCount }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 12.dp)) {
            // Top bar — Skip on intro pages only.
            Box(Modifier.fillMaxWidth().height(40.dp)) {
                if (pager.currentPage < selectionPage) {
                    TextButton(
                        onClick = { scope.launch { pager.animateScrollToPage(selectionPage) } },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> HowItConnectsPage()
                    else -> SelectionPage(profiles, onSelect)
                }
            }

            Spacer(Modifier.height(16.dp))
            // Trademark notice only on the selection page, where the brand names appear — above the dots.
            if (pager.currentPage == selectionPage) {
                TrademarkNotice()
                Spacer(Modifier.height(16.dp))
            }
            PageDots(current = pager.currentPage, count = pageCount)
            Spacer(Modifier.height(16.dp))

            // Continue advances the intro; the selection page completes by picking a bike instead.
            if (pager.currentPage < selectionPage) {
                Button(
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text("Continue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // Keep the bottom height stable so the dots don't jump when the button disappears.
                Spacer(Modifier.height(54.dp))
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.app_icon),
            contentDescription = "Pillion",
            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)),
        )
        Spacer(Modifier.height(28.dp))
        Text(BRAND, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Turn your phone into your motorcycle's dash.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Navigation, speed and maps — projected right onto the screen built into your bike, where you can " +
                "actually see them at a glance.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun HowItConnectsPage() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("How it connects", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Two ways to reach the dash, depending on what your bike supports.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        FeatureRow(
            Icons.Filled.Bluetooth,
            "Bluetooth",
            "Wireless. Sends a fast slideshow of your screen — perfect for turn-by-turn navigation.",
        )
        Spacer(Modifier.height(18.dp))
        FeatureRow(
            Icons.Filled.Usb,
            "USB",
            "A wired connection with smooth, full-motion video — great for maps and live navigation.",
        )
        Spacer(Modifier.height(18.dp))
        FeatureRow(
            Icons.Filled.Map,
            "More bikes over time",
            "We're adding support for more motorcycles and dashes — just keep the app updated.",
        )
    }
}

@Composable
private fun SelectionPage(profiles: List<HeadUnitProfile>, onSelect: (HeadUnitProfile) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Choose your motorcycle", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Pick the head unit your bike uses. You can change this anytime in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        profiles.forEach { profile ->
            BikeCard(profile, onClick = { onSelect(profile) })
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TrademarkNotice() {
    Text(
        "Yamaha, StreetCross and Motorize are trademarks of their respective owners. Pillion is an " +
            "independent project and is not affiliated with, endorsed, or sponsored by them.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BikeCard(profile: HeadUnitProfile, onClick: () -> Unit) {
    val usb = profile.requiresUsb
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(if (usb) Icons.Filled.Usb else Icons.Filled.Bluetooth, size = 52.dp, iconFraction = 0.5f)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                // Framed by the Garmin companion app it works with (nominative compatibility), not "Yamaha".
                val title = if (profile.compatibleApp.isNotEmpty()) "${profile.compatibleApp}-compatible"
                else profile.displayName
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (usb) "USB cable to the bike" else "Wireless over Bluetooth",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (profile.exampleBikes.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        profile.exampleBikes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                CapabilityPill(profile.videoPreference)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CapabilityPill(video: VideoPreference) {
    val (label, icon) = when (video) {
        is VideoPreference.H264 -> "Full-motion video" to Icons.Filled.Bolt
        is VideoPreference.JpegSlideshow -> "Slideshow" to Icons.Filled.Speed
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        IconBadge(icon, size = 48.dp, iconFraction = 0.5f)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, size: androidx.compose.ui.unit.Dp, iconFraction: Float = 0.46f) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * iconFraction))
    }
}

@Composable
private fun PageDots(current: Int, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val active = i == current
            val width by animateDpAsState(if (active) 22.dp else 8.dp)
            val color by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
            Box(Modifier.padding(horizontal = 4.dp).size(width = width, height = 8.dp).clip(CircleShape).background(color))
        }
    }
}
