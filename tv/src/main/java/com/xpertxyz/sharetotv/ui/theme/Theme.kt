package com.xpertxyz.sharetotv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@Composable
fun ShareToTVTheme(content: @Composable () -> Unit) {
    // TVs are always dark
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SignalAmber,
            onPrimary = AmberDeep,
            secondary = AerialTeal,
            onSecondary = DeepHarbor,
            background = DeepHarbor,
            onBackground = Mist,
            surface = DeepHarbor,
            onSurface = Mist,
            surfaceVariant = HarborRaised,
            onSurfaceVariant = MistDim,
            border = HarborEdge,
        ),
        typography = Typography,
        content = content
    )
}
