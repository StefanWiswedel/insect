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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.biomon.insect.AppSettings
import dk.biomon.insect.CaptureBus
import dk.biomon.insect.CaptureUiState
import dk.biomon.insect.SettingsRepository
import dk.biomon.insect.core.policy.DiskLevel
import dk.biomon.insect.core.policy.StopReason
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

    // Framing takes over the whole screen: the point is to see the scene, and a
    // preview boxed into a corner above a wall of readings is what made checking
    // the aim awkward enough to skip.
    if (state.previewing) {
        FramingScreen(
            state = state,
            settings = settings,
            onFocusChange = { diopters ->
                CaptureService.setFocusDiopters(diopters)
                scope.launch {
                    settingsRepository.update { it.copy(focusDistanceDiopters = diopters) }
                }
            },
            onStartRecording = { CaptureService.startRecording(context) },
            onClose = { CaptureService.stop(context) },
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Inside the bars, not under them. safeDrawing covers status
                // bar, navigation bar (gesture *or* three-button, whose heights
                // differ) and any display cutout, so the value is read from the
                // system rather than guessed at as fixed padding.
                .safeDrawingPadding()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(BgSunken)
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
            if (!state.running) {
                TextButton(
                    onClick = { CaptureService.startPreview(context) },
                    enabled = permissionsGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Preview / check framing")
                }
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
            Text(text = focusLabel(value), style = ReadingStyle, color = Ink)
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
internal fun PreviewImage(state: CaptureUiState, modifier: Modifier = Modifier) {
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
        // Not running, still converging, or holding an uncorroborated blob:
        // none of these are confirmed, and the design system reserves absence
        // of colour for exactly that. A candidate must not look like a catch.
        !state.running -> InkSoft
        state.warmingUp -> InkSoft
        state.candidate -> InkSoft

        // --signal is reserved for terminal states. Disk stop and thermal stop
        // qualify; low battery does not, because it is a planned graceful
        // shutdown rather than a fault.
        guard?.stopReason == StopReason.DISK_FULL -> Signal
        guard?.stopReason == StopReason.OVERHEATED -> Signal
        state.lastError != null -> Signal

        guard?.stopReason == StopReason.LOW_BATTERY -> Ember
        guard != null && (guard.disk != DiskLevel.NORMAL || guard.thermal != ThermalLevel.NOMINAL) ->
            Ember
        else -> Alive
    }
    val label = when {
        !state.running -> "Idle"
        state.warmingUp -> "Warming up"
        guard?.captureAllowed == false -> "Capture stopped"
        state.candidate -> "Candidate"
        state.captureMode != null -> "Capturing (${state.captureMode})"
        else -> "Watching"
    }
    // State is the first thing on the screen and the only thing that carries
    // colour here. A dot rather than a coloured word alone, because the answer
    // has to survive being read at arm's length in sun by someone who is
    // already walking away.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(10.dp)
                    .background(colour, CircleShape)
            )
            Text(
                text = label,
                color = colour,
                style = MaterialTheme.typography.displaySmall,
            )
        }
        Text(
            text = state.sessionId ?: "—",
            style = SmallReadingStyle,
            color = InkFaint,
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
        style = SmallReadingStyle,
        color = if (fallback) Ember else InkFaint,
    )
    if (fallback) {
        Warning(
            "Writing to app-specific storage: these frames are DELETED if the app " +
                "is uninstalled, and will not appear over USB or in the gallery. " +
                "Grant All Files Access, then restart the session.",
            Ember,
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
                Ember,
            )
            DiskLevel.STOPPED -> Warning(
                "Disk nearly full: capture stopped, session still open.",
                Signal,
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
                Ember,
            )
            ThermalLevel.STOPPED -> Warning("Too hot: capture stopped.", Signal)
            ThermalLevel.NOMINAL -> Unit
        }
    }
    // Camera error: the third and last thing allowed to be --signal.
    state.lastError?.let { Warning(it, Signal) }
}

@Composable
private fun Warning(text: String, colour: Color) {
    Text(text = text, color = colour, style = MaterialTheme.typography.bodySmall)
}

/**
 * Three tiers, in the order the field actually asks for them.
 *
 * Tier one is state, above. Tier two is the three numbers that decide whether
 * the rig survives the day -- free space, battery, temperature -- large enough
 * to read without stopping. Everything else is tier three, present because it
 * should be auditable, small because nobody checks it before walking away.
 */
@Composable
private fun Readings(state: CaptureUiState) {
    val guard = state.guard
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Vital(
            "FREE",
            guard?.let { "%.1f".format(it.freeBytes / 1e9) } ?: "—",
            "GB",
            // Storage is the binding constraint on a deployment, so it is the
            // one that earns colour when it starts to run out.
            when {
                guard == null -> InkFaint
                guard.disk == DiskLevel.STOPPED -> Signal   // terminal
                guard.disk == DiskLevel.DEGRADED -> Ember
                else -> Ink
            },
        )
        Vital(
            "BATTERY",
            guard?.batteryPercent?.toString() ?: "—",
            "%",
            when {
                guard == null -> InkFaint
                // Never --signal: a flat battery ends the deployment by design,
                // gracefully, and is not one of the three terminal faults.
                guard.batteryPercent in 0..25 -> Ember
                else -> Ink
            },
        )
        Vital(
            "TEMP",
            guard?.let { "%.1f".format(it.temperatureCelsius) } ?: "—",
            "°C",
            when {
                guard == null -> InkFaint
                guard.thermal == ThermalLevel.STOPPED -> Signal   // terminal
                guard.thermal == ThermalLevel.REDUCED -> Ember
                else -> Ink
            },
        )
    }

    Reading("Events", state.stats.events.toString())
    Reading("Frames", state.stats.frames.toString())
    Reading("Written", "%.2f GB".format(state.stats.bytesWritten / 1e9))
    Reading("Analysis", "${state.analysisFps} fps")
    Reading("Illumination", state.illuminationEvents.toString())
}

/** One of the three numbers that decide whether the deployment survives the day. */
@Composable
private fun Vital(label: String, value: String, unit: String, colour: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = InkFaint,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = PrimaryReadingStyle, color = colour)
            Text(
                text = unit,
                style = MaterialTheme.typography.labelMedium,
                color = InkFaint,
                modifier = Modifier.padding(start = 3.dp, bottom = 3.dp),
            )
        }
    }
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
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
        )
        Text(text = value, style = SmallReadingStyle, color = InkSoft)
    }
}
