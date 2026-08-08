package dk.biomon.insect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dk.biomon.insect.AppSettings
import dk.biomon.insect.CaptureUiState

/**
 * Framing mode: what the camera sees, full screen, writing nothing.
 *
 * The rig has to be aimed before every deployment, and the only way to see
 * through the lens used to be to start a real session -- which put hands, the
 * stand being nudged and focus being hunted into the data, as frames a later
 * analysis cannot distinguish from a genuine visit. So this runs the camera and
 * the analysis stream with no recorder behind them: no session directory, no
 * manifest, no session id, nothing counted.
 *
 * The mask overlay is the point as much as the image is. Framing is not only
 * "is the board in shot" but "is the trigger quiet when nothing is happening",
 * and the overlay is the only way to see the second one before committing nine
 * hours to it.
 *
 * **Insets.** The preview runs full-bleed behind the system bars, because the
 * whole purpose is to see as much of the scene as the screen can show. The two
 * control panels do not: their scrims extend under the bars so the image is
 * never cut by a hard edge, while their *contents* are inset by
 * [WindowInsets.safeDrawing]. Taking the value from the system rather than
 * hard-coding padding is what makes this correct under both gesture navigation
 * and three-button navigation, whose bar heights differ by roughly a factor of
 * three.
 */
@Composable
fun FramingScreen(
    state: CaptureUiState,
    settings: AppSettings,
    onFocusChange: (Float) -> Unit,
    onStartRecording: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Full-bleed, behind the bars. Both of these letterbox internally,
            // so the aspect ratio is preserved and the mask stays registered to
            // the pixels the trigger judged.
            PreviewImage(state, modifier = Modifier.fillMaxSize())
            MaskOverlay(snapshot = state.mask, modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    // Scrim first, then insets: the dark band reaches under the
                    // status bar, the text sits below it.
                    .background(Scrim)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "FRAMING — NOT RECORDING",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ember,
                )
                Text(
                    "No session directory, no frames, no totals. " +
                        "Amber shows what the trigger currently reads as motion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Scrim)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val diopters = state.focusDistanceDiopters.takeIf { it > 0f }
                    ?: settings.focusDistanceDiopters
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Focus",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSoft,
                    )
                    Text(
                        text = focusReading(diopters),
                        style = ReadingStyle,
                        color = Ink,
                    )
                }
                Slider(
                    value = diopters.coerceIn(SettingsRanges.focusDiopters),
                    onValueChange = onFocusChange,
                    valueRange = SettingsRanges.focusDiopters,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onStartRecording,
                        modifier = Modifier.weight(1f),
                    ) { Text("Start recording") }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                    ) { Text("Close") }
                }
            }
        }
    }
}

/** The warm ground at 80%, so the scene reads through it without the text fighting. */
private val Scrim = Color(0xCC100D0B)

private fun focusReading(diopters: Float): String =
    if (diopters <= 0.01f) "inf" else "%.2f D  %.0f cm".format(diopters, 100f / diopters)
