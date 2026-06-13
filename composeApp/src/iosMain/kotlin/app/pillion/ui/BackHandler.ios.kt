package app.pillion.ui

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no global system-back gesture for this single-screen app; the in-app
    // Settings <-> Home navigation is handled by the UI itself.
}
