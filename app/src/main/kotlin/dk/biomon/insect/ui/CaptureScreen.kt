package dk.biomon.insect.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.biomon.insect.CaptureBus
import dk.biomon.insect.CaptureUiState
import dk.biomon.insect.core.policy.DiskLevel
import dk.biomon.insect.core.policy.ThermalLevel
import dk.biomon.insect.service.CaptureService

/**
 * The one screen.
 *
 * It is read at arm's length, outdoors, usually in a hurry, to answer one
 * question: is this thing actually working? So the degradations are not tucked
 * into a details pane -- disk pressure, thermal backoff and the last error are
 * on the face of it, because a session quietly capturing nothing is the failure
 * this whole app is built to avoid.
 */
@Composable
fun CaptureScreen(
    permissionsGranted: Boolean,
    batteryExemptionNeeded: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by CaptureBus.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black)
            ) {
                if (permissionsGranted) {
                    PreviewSurface(modifier = Modifier.fillMaxSize())
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

            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .padding(start = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusHeader(state)
                Warnings(state, batteryExemptionNeeded, onRequestBatteryExemption)
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
}

/**
 * The preview surface, handed straight to the service.
 *
 * Attaching and detaching rebuilds the camera session, so this is bound to the
 * composable's lifetime and nothing else -- when the Activity goes away the
 * surface is released and capture carries on without it, which is the state the
 * rig spends nine hours in.
 */
@Composable
private fun PreviewSurface(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        CaptureService.setPreviewSurface(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        CaptureService.setPreviewSurface(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        CaptureService.clearPreviewSurface()
                    }
                })
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose { CaptureService.clearPreviewSurface() }
    }
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

/** Anything that means the session is doing less than it was asked to. */
@Composable
private fun Warnings(
    state: CaptureUiState,
    batteryExemptionNeeded: Boolean,
    onRequestBatteryExemption: () -> Unit,
) {
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
            ThermalLevel.WARN, ThermalLevel.HOT -> Warning(
                "Thermal backoff: analysis reduced to ${state.analysisFps}fps.",
                StateAmber,
            )
            ThermalLevel.CRITICAL -> Warning("Too hot: capture stopped.", StateRed)
            ThermalLevel.NOMINAL -> Unit
        }
    }
    state.lastError?.let { Warning(it, StateRed) }
    if (batteryExemptionNeeded) {
        Warning("Battery optimisation is on; a long session may be throttled.", StateAmber)
        TextButton(onClick = onRequestBatteryExemption) { Text("Exempt this app") }
    }
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
    Reading(
        "Temperature",
        guard?.let { "%.1f C".format(it.temperatureCelsius) } ?: "-",
    )
    Reading("Focus", "%.2f D".format(state.focusDistanceDiopters))
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
