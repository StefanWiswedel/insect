package dk.biomon.insect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dk.biomon.insect.CaptureUiState
import dk.biomon.insect.AppSettings

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
 */
@Composable
fun FramingScreen(
    state: CaptureUiState,
    settings: AppSettings,
    onFocusChange: (Float) -> Unit,
    onStartRecording: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            PreviewImage(state, modifier = Modifier.fillMaxSize())
            MaskOverlay(snapshot = state.mask, modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Framing — nothing is being recorded",
                    style = MaterialTheme.typography.titleSmall,
                    color = StateAmber,
                )
                Text(
                    "No session directory, no frames, no totals. " +
                        "Green overlay is what the trigger currently sees as motion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFBBBBBB),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val diopters = state.focusDistanceDiopters.takeIf { it > 0f }
                    ?: settings.focusDistanceDiopters
                Text(
                    text = "Focus  ${"%.2f".format(diopters)} D  (${focusCm(diopters)})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Slider(
                    value = diopters,
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

private fun focusCm(diopters: Float): String =
    if (diopters <= 0.01f) "infinity" else "%.0f cm".format(100f / diopters)
