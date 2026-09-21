package dev.mambuco.watchproximity.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// Catppuccin Mocha / Material You Dark Palette
val CatppuccinBase = Color(0xFF1E1E2E)
val CatppuccinMantle = Color(0xFF181825)
val CatppuccinCrust = Color(0xFF11111B)
val CatppuccinText = Color(0xFFCDD6F4)
val CatppuccinSubtext = Color(0xFFA6ADC8)
val CatppuccinBlue = Color(0xFF89B4FA)
val CatppuccinSapphire = Color(0xFF74C7EC)
val CatppuccinTeal = Color(0xFF94E2D5)
val CatppuccinGreen = Color(0xFFA6E3A1)
val CatppuccinYellow = Color(0xFFF9E2AF)
val CatppuccinPeach = Color(0xFFFAB387)
val CatppuccinRed = Color(0xFFF38BA8)
val CatppuccinMauve = Color(0xFFCBA6F7)

val WearColorPalette = Colors(
    primary = CatppuccinSapphire,
    primaryVariant = CatppuccinBlue,
    secondary = CatppuccinTeal,
    secondaryVariant = CatppuccinMauve,
    background = Color.Black,
    surface = CatppuccinMantle,
    error = CatppuccinRed,
    onPrimary = CatppuccinCrust,
    onSecondary = CatppuccinCrust,
    onBackground = CatppuccinText,
    onSurface = CatppuccinText,
    onError = CatppuccinCrust
)

@Composable
fun WatchProximityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WearColorPalette,
        content = content
    )
}
