package dk.biomon.insect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.biomon.insect.AppSettings
import dk.biomon.insect.SettingsRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Thresholds and framerates.
 *
 * Every slider is bounded by [SettingsRanges], which is the same object the
 * store clamps against -- the `:core` configs validate in their init blocks, so
 * a UI that could offer an out-of-range value would be a UI that could crash the
 * app on the next session start.
 *
 * Changes apply at the next session start, not to a running session. Saying so
 * is better than silently doing nothing, and better than pretending to retune a
 * background model that has been adapting for six hours.
 */
@Composable
fun SettingsScreen(repository: SettingsRepository, onBack: () -> Unit) {
    val settings by repository.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun edit(transform: (AppSettings) -> AppSettings) {
        scope.launch { repository.update(transform) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text("Done") }
            }
            Text(
                "Applied at the next session start. A running session keeps the " +
                    "settings it began with.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Capture")
            SettingSlider(
                label = "Analysis rate",
                value = settings.capture.analysisFps.toFloat(),
                range = SettingsRanges.analysisFps,
                format = { "${it.roundToInt()} fps" },
            ) { v -> edit { it.copy(capture = it.capture.copy(analysisFps = v.roundToInt())) } }

            SettingSlider(
                label = "Capture rate, moving",
                value = settings.capture.movingFps,
                range = SettingsRanges.movingFps,
                format = { "%.1f fps".format(it) },
            ) { v -> edit { it.copy(capture = it.capture.copy(movingFps = v)) } }

            SettingSlider(
                label = "Capture rate, stationary",
                value = settings.capture.stationaryFps,
                range = SettingsRanges.stationaryFps,
                format = { "%.1f fps".format(it) },
            ) { v -> edit { it.copy(capture = it.capture.copy(stationaryFps = v)) } }

            SettingSlider(
                label = "Post-roll",
                value = settings.capture.postRollMillis / 1000f,
                range = SettingsRanges.postRollSeconds,
                format = { "%.0f s".format(it) },
            ) { v ->
                edit { it.copy(capture = it.capture.copy(postRollMillis = (v * 1000).toLong())) }
            }

            SettingSlider(
                label = "JPEG quality",
                value = settings.capture.jpegQuality.toFloat(),
                range = SettingsRanges.jpegQuality,
                format = { "q${it.roundToInt()}" },
            ) { v -> edit { it.copy(capture = it.capture.copy(jpegQuality = v.roundToInt())) } }
            Note(
                "Hard floor of q85. Below about q75 the 8x8 blocking artefacts show " +
                    "up in the laptop's background-subtraction residual and inflate " +
                    "blob counts."
            )

            Section("Trigger")
            SettingSlider(
                label = "Contrast floor",
                value = settings.trigger.minContrastFraction,
                range = SettingsRanges.minContrastFraction,
                format = { "%.1f%%".format(it * 100) },
            ) { v -> edit { it.copy(trigger = it.trigger.copy(minContrastFraction = v)) } }
            Note(
                "A fraction of local brightness, not an absolute step. That is what " +
                    "keeps a corner bait station as sensitive as a centre one."
            )

            SettingSlider(
                label = "Noise margin",
                value = settings.trigger.noiseSigmas,
                range = SettingsRanges.noiseSigmas,
                format = { "%.1f sigma".format(it) },
            ) { v -> edit { it.copy(trigger = it.trigger.copy(noiseSigmas = v)) } }

            SettingSlider(
                label = "Minimum blob area",
                value = settings.trigger.minBlobAreaPx.toFloat(),
                range = SettingsRanges.minBlobAreaPx,
                format = { "${it.roundToInt()} px" },
            ) { v -> edit { it.copy(trigger = it.trigger.copy(minBlobAreaPx = v.roundToInt())) } }
            Note("In working (post-downsample) pixels, not sensor pixels.")

            SettingSlider(
                label = "Analysis downsample",
                value = settings.trigger.downsample.toFloat(),
                range = SettingsRanges.downsample,
                format = { "${it.roundToInt()}x" },
            ) { v -> edit { it.copy(trigger = it.trigger.copy(downsample = v.roundToInt())) } }
            Note(
                "Higher reduces noise but costs target area as the square: at 4x " +
                    "a fly at 31cm was under the minimum blob area, so the rig " +
                    "could not see its own subject. Check the Detection geometry " +
                    "section of SUMMARY.md after changing this."
            )

            SettingSlider(
                label = "Illumination threshold",
                value = settings.trigger.illuminationAreaFraction,
                range = SettingsRanges.illuminationAreaFraction,
                format = { "%.1f%% of frame".format(it * 100) },
            ) { v ->
                edit { it.copy(trigger = it.trigger.copy(illuminationAreaFraction = v)) }
            }
            Note(
                "A blob larger than this is the light changing, not a subject. " +
                    "Capture is suppressed for the frame, the background is " +
                    "re-baselined, and an illumination_event is recorded. " +
                    "A fraction of frame area, so it survives a resolution change."
            )

            SettingSlider(
                label = "Forced background refresh",
                value = settings.trigger.forcedRefreshSeconds.toFloat(),
                range = SettingsRanges.forcedRefreshSeconds,
                format = { "%.0f s".format(it) },
            ) { v ->
                edit { it.copy(trigger = it.trigger.copy(forcedRefreshSeconds = v.roundToInt())) }
            }
            Note(
                "How long a pixel may stay triggered before it is folded back into " +
                    "the background. Too short and a feeding insect dissolves; too " +
                    "long and a moved dish pins the model for the rest of the day."
            )

            Note(
                "Focus is on the main screen, not here: it needs re-aiming for " +
                    "every deployment."
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                format(value),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}
