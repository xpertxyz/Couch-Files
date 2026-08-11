package com.xpertxyz.sharetotv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always dark, matching the TV app — the brand is a dark living-room UI.
private val Harbor = darkColorScheme(
    primary = SignalAmber,
    onPrimary = AmberDeep,
    primaryContainer = SignalAmber,
    onPrimaryContainer = AmberDeep,
    secondary = AerialTeal,
    onSecondary = DeepHarbor,
    secondaryContainer = HarborEdge,
    onSecondaryContainer = Mist,
    tertiary = AerialTeal,
    background = DeepHarbor,
    onBackground = Mist,
    surface = DeepHarbor,
    onSurface = Mist,
    surfaceVariant = HarborRaised,
    onSurfaceVariant = MistDim,
    surfaceContainer = HarborRaised,
    surfaceContainerHigh = HarborEdge,
    outline = HarborEdge,
    outlineVariant = HarborEdge,
)

@Composable
fun ShareToTVTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Harbor,
        typography = Typography,
        content = content
    )
}
