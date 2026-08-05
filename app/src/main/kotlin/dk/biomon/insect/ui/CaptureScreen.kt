package dk.biomon.insect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.biomon.insect.AppSettings
import dk.biomon.insect.CaptureBus
import dk.biomon.insect.CaptureUiState
import dk.biomon.insect.SettingsRepository
import dk.biomon.insect.core.policy.DiskLevel
import dk.biomon.insect.core.policy.ThermalLevel
import dk.biomon.insect.service.CaptureService
import kotlinx.coroutines.launch

/**
 * The one screen, in portrait.
 *
 * Portrait because the rig is a phone on a stand pointing straight down at a
 * board, which is a portrait posture; the UI orientation says nothing about the
 * sensor, and captured frames are never rotated to match it.
 *
 * It is read at arm's length, outdoors, usually in a hurry, to answer one
 * question: is this thing actually working? So the degradations are not tucked
 * into a details pane -- disk pressure, thermal backoff and the last error are
 * on the face of it, because a session quietly capturing nothing is the failure
 * this whole app exists to avoid.
 */
@Composable
fun CaptureScreen(
    settingsRepository: SettingsRepository,
    permissionsGranted: Boolean,
    storageFallback: Boolean,
    plannedStoragePath: String,
    onRequestPermissions: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by CaptureBus.state.collectAsStateWithLifecycle()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Preview frames are built on the analysis thread only while this screen is
    // composed. Nine hours of screen-off deployment allocates none of them.
    DisposableEffect(Unit) {
        CaptureBus.previewWanted = true
        onDispose { CaptureBus.previewWanted = false }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(Color.Black)
            ) {
                if (permissionsGranted) {
                    PreviewImage(state, modifier = Modifier.fillMaxSize())
                    MaskOverlay(snapshot = state.mask, modifier = Modifier.fillMaxSize())
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "Camera permission is required.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        TextButton(onClick = onRequestPermissions) { Text("Grant") }
                    }
                }
            }

            StatusHeader(state)
            FocusControl(state, settings) { diopters ->
                // Apply to the running session immediately so the preview shows
                // the result, and persist it for the next one.
                CaptureService.setFocusDiopters(diopters)
                scope.launch {
                    settingsRepository.update { it.copy(focusDistanceDiopters = diopters) }
                }
            }
            Warnings(state)
            StorageState(state, storageFallback, plannedStoragePath, onRequestAllFilesAccess)
            Readings(state)

            Button(
                onClick = {
                    if (state.running) CaptureService.stop(context)
                    else CaptureService.start(context)
                },
                enabled = permissionsGranted,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (state.running) "Stop session" else "Start session")
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Settings")
            }
        }
    }
}

/**
 * Focus lives here, not in settings.
 *
 * It has to be re-aimed for every deployment -- the stand moves, the board
 * moves -- so burying it behind a settings screen would put the one control
 * that always needs touching in the place you have to go looking for.
 */
@Composable
private fun FocusControl(
    state: CaptureUiState,
    settings: AppSettings,
    onChange: (Float) -> Unit,
) {
    // Prefer what the camera actually applied: the lens may not reach the
    // requested distance, and showing the request would be a quiet lie.
    val value = if (state.running && state.focusDistanceDiopters > 0f) {
        state.focusDistanceDiopters
    } else {
        settings.focusDistanceDiopters
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Focus", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = focusLabel(value),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value.coerceIn(SettingsRanges.focusDiopters),
            onValueChange = onChange,
            valueRange = SettingsRanges.focusDiopters,
        )
    }
}

/** Dioptres are 1/metres, so the centimetres are just the reciprocal. */
private fun focusLabel(diopters: Float): String =
    if (diopters <= 0.01f) "infinity" else "%.2f D  (%.0f cm)".format(diopters, 100f / diopters)

/**
 * The preview, rendered from the analysis stream rather than a camera surface.
 *
 * That is what lets the Camera2 session stay configured across a screen lock:
 * there is no surface to attach or detach. It is grayscale and 320x240, which is
 * ample for aiming a fixed rig at a board, and it has the useful property that
 * the mask overlay sits exactly on the pixels the trigger judged.
 */
@Composable
private fun PreviewImage(state: CaptureUiState, modifier: Modifier = Modifier) {
    val preview = state.preview
    if (preview == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = if (state.running) "waiting for frames" else "preview appears once started",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LumaImage(preview, modifier)
}

@Composable
private fun StatusHeader(state: CaptureUiState) {
    val guard = state.guard
    val colour = when {
        !state.running -> MaterialTheme.colorScheme.onSurfaceVariant
        guard?.captureAllowed == false -> StateRed
        guard != null && (guard.disk != DiskLevel.NORMAL || guard.thermal != ThermalLevel.NOMINAL) ->
            StateAmber
        else -> StateGreen
    }
    val label = when {
        !state.running -> "Idle"
        state.warmingUp -> "Warming up"
        guard?.captureAllowed == false -> "Capture stopped"
        state.captureMode != null -> "Capturing (${state.captureMode})"
        else -> "Watching"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colour,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = state.sessionId ?: "no session",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Where the frames are going, and a loud warning when it is not where it should
 * be.
 *
 * On the face of the screen rather than in a file manager: writing to
 * app-specific storage means the frames vanish on uninstall and never appear
 * over USB, and discovering that after a nine-hour deployment is discovering it
 * too late.
 */
@Composable
private fun StorageState(
    state: CaptureUiState,
    permissionFallback: Boolean,
    plannedPath: String,
    onRequestAllFilesAccess: () -> Unit,
) {
    // Prefer where the running session actually landed over where the next one
    // is predicted to land.
    val path = state.storagePath ?: plannedPath
    val fallback = if (state.running) state.storageFallback else permissionFallback

    Reading("Storage", if (fallback) "fallback" else "DCIM/Biomon")
    Text(
        text = path,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (fallback) StateAmber else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (fallback) {
        Warning(
            "Writing to app-specific storage: these frames are DELETED if the app " +
                "is uninstalled, and will not appear over USB or in the gallery. " +
                "Grant All Files Access, then restart the session.",
            StateAmber,
        )
        TextButton(onClick = onRequestAllFilesAccess) { Text("Grant All Files Access") }
    }
}

/** Anything that means the session is doing less than it was asked to. */
@Composable
private fun Warnings(state: CaptureUiState) {
    val guard = state.guard
    if (guard != null) {
        when (guard.disk) {
            DiskLevel.DEGRADED -> Warning(
                "Disk pressure: capture rate reduced and the trigger is more selective.",
                StateAmber,
            )
            DiskLevel.STOPPED -> Warning(
                "Disk nearly full: capture stopped, session still open.",
                StateRed,
            )
            DiskLevel.NORMAL -> Unit
        }
        when (guard.thermal) {
            ThermalLevel.REDUCED -> Warning(
                buildString {
                    append("Thermal backoff: analysis reduced to ${state.analysisFps}fps.")
                    // Capture is requested from the analysis thread, so it
                    // inherits the reduction. Say so rather than leaving the
                    // moving rate on screen as if it were still available.
                    if (guard.captureBoundByAnalysisRate) {
                        append(" Capture capped at %.0ffps with it.".format(guard.maxCaptureFps))
                    }
                },
                StateAmber,
            )
            ThermalLevel.STOPPED -> Warning("Too hot: capture stopped.", StateRed)
            ThermalLevel.NOMINAL -> Unit
        }
    }
    state.lastError?.let { Warning(it, StateRed) }
}

@Composable
private fun Warning(text: String, colour: Color) {
    Text(text = text, color = colour, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Readings(state: CaptureUiState) {
    val guard = state.guard
    Reading("Events", state.stats.events.toString())
    Reading("Frames", state.stats.frames.toString())
    Reading("Written", "%.2f GB".format(state.stats.bytesWritten / 1e9))
    Reading("Free", guard?.let { "%.1f GB".format(it.freeBytes / 1e9) } ?: "-")
    Reading("Battery", guard?.let { "${it.batteryPercent}%" } ?: "-")
    Reading("Temperature", guard?.let { "%.1f C".format(it.temperatureCelsius) } ?: "-")
    Reading("Analysis", "${state.analysisFps} fps")
}

@Composable
private fun Reading(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}
