package dk.biomon.insect.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dk.biomon.insect.R

/**
 * Type for an instrument, not an app. Biomon design system, `.claude/skills/biomon-ui`.
 *
 * Fraunces for display and headings, Instrument Sans for UI body, Martian Mono
 * for **all** numerals with tabular figures, always.
 *
 * **Bundled, not downloaded.** The `.ttf` files are in `res/font/` and the SIL
 * OFL licences ship in `assets/licenses/`. Downloadable fonts through Play
 * Services were rejected deliberately: a field instrument should not depend on
 * a network service to render its own numbers, and a phone in a box behind a
 * stick pile is exactly where that dependency would fail.
 *
 * **Numbers are monospaced everywhere.** Not decoration. A reading whose glyph
 * widths change as the value changes makes the whole row twitch, and this screen
 * is read at a glance in bright sun to answer "is this still working" --
 * movement in the layout reads as movement in the data.
 *
 * Never Inter, Roboto, Arial or Space Grotesk. That rules out the system
 * families as well: `FontFamily.SansSerif` on Android *is* Roboto, which is why
 * the faces are bundled rather than fallen back to.
 */
private val displayFont = FontFamily(
    // The 9pt optical cut, not 72pt or 144pt. Headings here are physically small
    // on a phone, and the text cut carries lower stroke contrast, which is what
    // survives being read in direct sun. WONK is left at default: the spec says
    // use it sparingly, and an instrument face is not the place to spend it.
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
)

private val uiFont = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
)

/**
 * Numerals. Use this for every value that changes while the screen is up:
 * readings, counts, sizes, temperatures, focus distance.
 */
val NumericFont = FontFamily(
    Font(R.font.martian_mono_regular, FontWeight.Normal),
    Font(R.font.martian_mono_medium, FontWeight.Medium),
)

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
    fontSize = 23.sp,
    lineHeight = 28.sp,
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
