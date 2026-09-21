package dev.mambuco.watchproximity.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

val WearOsPalette = Colors(
    primary = Color(0xFF8AB4F8),          // Standard Wear OS Google Blue accent
    primaryVariant = Color(0xFF669DF6),
    secondary = Color(0xFF81C995),        // Standard Wear OS Green
    secondaryVariant = Color(0xFF5BB974),
    background = Color(0xFF000000),       // Pitch black for Wear OS circular OLED
    surface = Color(0xFF202124),          // Standard Wear OS Material chip surface
    onPrimary = Color(0xFF202124),
    onSecondary = Color(0xFF202124),
    onBackground = Color(0xFFE8EAED),
    onSurface = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF9AA0A6),
    error = Color(0xFFF28B82),
    onError = Color(0xFF202124)
)

@Composable
fun WatchProximityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WearOsPalette,
        content = content
    )
}
