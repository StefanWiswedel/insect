package dk.biomon.insect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark-only, neutral, and deliberately unstyled.
 *
 * There is no `biomon-ui` skill in this repository (DESIGN.md 5), so there are no
 * tokens to follow and inventing a second visual identity would be worse than
 * having none. Everything decorative is therefore absent: no dynamic colour (the
 * tool must read identically on whatever handset is in the field), no custom
 * typography, no elevation games. If the skill ever lands, restyling should be
 * confined to this file.
 */

/** Capturing. The only colour the screen wants to show for nine hours. */
val StateGreen = Color(0xFF57A05B)

/** Degraded but still recording -- disk pressure, thermal backoff. */
val StateAmber = Color(0xFFD79A2B)

/** Capture has stopped, or an error is outstanding. */
val StateRed = Color(0xFFC4544B)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C2C9),
    onPrimary = Color(0xFF11161A),
    primaryContainer = Color(0xFF232A30),
    onPrimaryContainer = Color(0xFFE3E8EC),
    secondary = Color(0xFF9BA5AD),
    onSecondary = Color(0xFF11161A),
    secondaryContainer = Color(0xFF1E252A),
    onSecondaryContainer = Color(0xFFD5DDE3),
    background = Color(0xFF0B0D0F),
    onBackground = Color(0xFFE3E8EC),
    surface = Color(0xFF0B0D0F),
    onSurface = Color(0xFFE3E8EC),
    surfaceVariant = Color(0xFF171C21),
    onSurfaceVariant = Color(0xFF9AA4AC),
    surfaceContainer = Color(0xFF14191D),
    surfaceContainerHigh = Color(0xFF1A2025),
    outline = Color(0xFF3A424A),
    outlineVariant = Color(0xFF272E34),
    error = StateRed,
    onError = Color(0xFF11161A),
    errorContainer = Color(0xFF3A1F1D),
    onErrorContainer = Color(0xFFF2D6D3),
)

@Composable
fun BiomonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
