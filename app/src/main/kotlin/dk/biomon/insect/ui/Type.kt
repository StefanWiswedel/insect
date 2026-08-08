package dk.biomon.insect.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type for an instrument, not an app.
 *
 * Three faces, from the Biomon design system: Fraunces for display, Instrument
 * Sans for UI, Martian Mono for anything numeric.
 *
 * **Numbers are monospaced everywhere.** That is not decoration. A reading whose
 * glyph widths change as the value changes makes the whole row twitch, and the
 * screen is read at a glance in bright sun to answer "is this still working" --
 * movement in the layout reads as movement in the data. Martian Mono is
 * monospaced by construction, and `tnum` is set as well so the fallback face is
 * tabular too.
 *
 * **The three named faces are not in this build**, and these are system stand-ins
 * chosen to match their category: serif for display, sans for UI, monospace for
 * numerals. Two things blocked them and both need something from outside this
 * environment:
 *
 * * The font files could not be fetched -- `fonts.google.com` is refused by this
 *   build environment's network policy -- so they cannot be bundled.
 * * Downloadable fonts through Play Services need the provider's certificate
 *   array, which `ui-text-google-fonts` does not ship. Writing that blob from
 *   memory is not acceptable: a wrong certificate does not fail loudly, it makes
 *   every font silently fall back forever.
 *
 * Swapping the real faces in is a change to these three declarations and nothing
 * else, once either the `.ttf` files land in `res/font/` or the certificate
 * array lands in `res/values/`.
 *
 * What survives the substitution is the part that matters operationally:
 * numerals are monospaced and tabular either way, so readings do not twitch.
 */
private val displayFont = FontFamily.Serif

private val uiFont = FontFamily.SansSerif

/**
 * Numerals. Use this for every value that changes while the screen is up:
 * readings, counts, sizes, temperatures, focus distance.
 */
val NumericFont = FontFamily.Monospace

/** Tabular figures, so the fallback face does not jitter either. */
const val TABULAR = "tnum"

/**
 * The one number that answers "is it working", at a size readable at arm's
 * length in sun.
 */
val PrimaryReadingStyle = TextStyle(
    fontFamily = NumericFont,
    fontFeatureSettings = TABULAR,
    fontWeight = FontWeight.Medium,
    fontSize = 26.sp,
    lineHeight = 30.sp,
)

/** Secondary readings: still numeric, still tabular, quieter. */
val ReadingStyle = TextStyle(
    fontFamily = NumericFont,
    fontFeatureSettings = TABULAR,
    fontSize = 14.sp,
    lineHeight = 18.sp,
)

/** Small numerics, for the rows nobody reads in the field. */
val SmallReadingStyle = TextStyle(
    fontFamily = NumericFont,
    fontFeatureSettings = TABULAR,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

val BiomonTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = displayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = displayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = uiFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = uiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(fontFamily = uiFont, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = uiFont, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = uiFont, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontFamily = uiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(fontFamily = uiFont, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = uiFont, fontSize = 11.sp, lineHeight = 14.sp),
)
