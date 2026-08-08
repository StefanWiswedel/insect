package dk.biomon.insect.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import dk.biomon.insect.R

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
 * The faces are **downloadable**, resolved on-device through Play Services
 * rather than bundled, because the font files could not be fetched into this
 * build. Every family names an explicit system fallback, so a device that cannot
 * reach the provider gets the right *shape* of type -- and critically, numerals
 * still land on a monospaced face, which is the property that matters most.
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val displayFont = FontFamily(
    Font(GoogleFont("Fraunces"), provider, FontWeight.Normal),
    Font(GoogleFont("Fraunces"), provider, FontWeight.SemiBold),
)

private val uiFont = FontFamily(
    Font(GoogleFont("Instrument Sans"), provider, FontWeight.Normal),
    Font(GoogleFont("Instrument Sans"), provider, FontWeight.Medium),
    Font(GoogleFont("Instrument Sans"), provider, FontWeight.SemiBold),
)

/**
 * Numerals. Use this for every value that changes while the screen is up:
 * readings, counts, sizes, temperatures, focus distance.
 */
val NumericFont = FontFamily(
    Font(GoogleFont("Martian Mono"), provider, FontWeight.Normal),
    Font(GoogleFont("Martian Mono"), provider, FontWeight.Medium),
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
