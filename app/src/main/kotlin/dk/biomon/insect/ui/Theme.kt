package dk.biomon.insect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Biomon design tokens. Source of truth: `.claude/skills/biomon-ui/SKILL.md`.
 *
 * "Nocturnal field ledger" — warm near-black ground, specimen-label typography,
 * mostly still. The visual peak is reserved for rare events.
 *
 * Shared with the bird station. Neither app changes these unilaterally; the
 * skill file changes first.
 */

/** `--bg`. Base ground. */
val Bg = Color(0xFF100D0B)

/** `--bg-raised`. Raised surfaces. */
val BgRaised = Color(0xFF1A1512)

/** `--bg-sunken`. Sunken wells -- the preview sits in one. */
val BgSunken = Color(0xFF0A0807)

/** `--ink`. Primary text. */
val Ink = Color(0xFFF4EDE2)

/** `--ink-soft`. Secondary text -- **and every candidate or unconfirmed state**. */
val InkSoft = Color(0xFFA99B8B)

/** `--ink-faint`. Tertiary, labels, disabled. */
val InkFaint = Color(0xFF6B5F55)

/** `--alive`. Confirmed, capturing, healthy. */
val Alive = Color(0xFF9CC471)

/** `--ember`. Activity, warnings, and the mask overlay. */
val Ember = Color(0xFFE8A33D)

/**
 * `--signal`. **RESERVED. Never on ordinary UI.**
 *
 * In this app that is exactly three states, all of them terminal — the session
 * is over and a human has to do something:
 *
 * * thermal stop
 * * disk stop
 * * camera error
 *
 * Not warnings. Not degradations. Not "recording". Not low battery, which is a
 * *planned* graceful shutdown rather than a fault, and so takes [Ember]. If a
 * fourth use ever appears, it is almost certainly wrong.
 */
val Signal = Color(0xFFFF6B4A)

/**
 * The spectrogram ramp. Not used in this app -- there is nothing to plot -- but
 * kept here so that if anything ever is plotted it stays in the family rather
 * than acquiring a viridis or a turbo.
 */
val SpectrogramRamp = listOf(
    Color(0xFF100D0B),
    Color(0xFF3B2416),
    Color(0xFF8A4A1E),
    Color(0xFFE8A33D),
    Color(0xFFFFF3D6),
)

/**
 * Candidate state carries **no colour**. Absence of colour means "not yet real".
 *
 * That is load-bearing here rather than decorative: the first sessions recorded
 * artefacts as detections, and a blob sitting over the illumination suspect gate
 * that has not collected its corroborating signals is exactly a thing that
 * should not look confirmed.
 */
val Candidate = InkSoft

/**
 * Outlines, derived rather than specified -- the skill is silent on them. Warm
 * steps between [Bg] and [BgRaised] at the same hue.
 */
private val OutlineWarm = Color(0xFF3A312B)
private val OutlineFaint = Color(0xFF241E1A)

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
    surfaceVariant = BgRaised,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = BgSunken,
    surfaceContainer = BgRaised,
    surfaceContainerHigh = BgRaised,
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
