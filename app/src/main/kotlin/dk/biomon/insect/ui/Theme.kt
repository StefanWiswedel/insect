package dk.biomon.insect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Biomon tokens, adapted to Compose.
 *
 * The warm near-black ground and the four named colours come from the project's
 * design system, so the insect trap reads as the same instrument as the bird
 * station rather than as a second identity.
 *
 * Restraint is the brief. This is read at a glance in bright sun and then walked
 * away from for nine hours, so: no dynamic colour (it must look identical on
 * whatever handset is in the field), no elevation games, and colour used only to
 * carry state. Everything that is not saying something is grey.
 */

/** `--bg`. Warm near-black; the ground everything sits on. */
val Bg = Color(0xFF100D0B)

/** `--ink`. Warm off-white; body text and readings. */
val Ink = Color(0xFFF4EDE2)

/** `--alive`. Working as intended: capturing, watching, healthy. */
val Alive = Color(0xFF9CC471)

/** `--ember`. Degraded but still running: disk pressure, thermal backoff. */
val Ember = Color(0xFFE8A33D)

/**
 * `--signal`. **Rare events only.** Capture stopped, or an outstanding error.
 *
 * Reserved deliberately: if it is used for ordinary state it stops meaning
 * anything, and the one screen that must communicate "this is broken" at a
 * glance loses its only way of saying so.
 */
val Signal = Color(0xFFFF6B4A)

/**
 * Ink at reduced emphasis, for labels and the rows nobody reads in the field.
 * Derived from [Ink] rather than being a token of its own.
 */
val InkMuted = Color(0xFF9C948A)
val InkFaint = Color(0xFF6B635B)

/**
 * Surfaces lifted off [Bg] by warming and lightening it, keeping the same hue.
 * Derived, not specified -- see the note in DESIGN.md 5 about what is still
 * missing from the design system.
 */
private val Surface1 = Color(0xFF181411)
private val Surface2 = Color(0xFF221C18)
private val OutlineWarm = Color(0xFF3A312B)
private val OutlineFaint = Color(0xFF272120)

/** Kept under the old names so call sites read as state, not as colour. */
val StateGreen = Alive
val StateAmber = Ember
val StateRed = Signal

private val BiomonColors = darkColorScheme(
    primary = Alive,
    onPrimary = Bg,
    primaryContainer = Color(0xFF2B3A22),
    onPrimaryContainer = Color(0xFFD5E8BE),
    secondary = Ember,
    onSecondary = Bg,
    secondaryContainer = Color(0xFF3A2C16),
    onSecondaryContainer = Color(0xFFF3DCB6),
    background = Bg,
    onBackground = Ink,
    surface = Bg,
    onSurface = Ink,
    surfaceVariant = Surface1,
    onSurfaceVariant = InkMuted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    outline = OutlineWarm,
    outlineVariant = OutlineFaint,
    error = Signal,
    onError = Bg,
    errorContainer = Color(0xFF3D1D16),
    onErrorContainer = Color(0xFFFFD9CF),
)

@Composable
fun BiomonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BiomonColors,
        typography = BiomonTypography,
        content = content,
    )
}
