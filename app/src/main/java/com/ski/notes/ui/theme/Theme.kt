package com.ski.notes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF4F7CFF)
val Bg = Color(0xFF14161A)
val Panel = Color(0xFF1D2026)
val PanelHigh = Color(0xFF262A32)
val Line = Color(0xFF343944)
val TextDim = Color(0xFFA3A9B6)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFFFFB547),
    background = Bg,
    surface = Bg,
    surfaceVariant = Panel,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelHigh,
    onSurface = Color(0xFFE9ECF2),
    onSurfaceVariant = TextDim,
    outline = Line,
)

@Composable
fun SkiNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
