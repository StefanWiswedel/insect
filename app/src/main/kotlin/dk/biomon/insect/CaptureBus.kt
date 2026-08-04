package dk.biomon.insect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single channel between the capture service and the UI.
 *
 * Deliberately a process-wide object rather than a bound-service connection: the
 * UI is incidental here. The service must run identically whether an Activity is
 * attached, backgrounded, or has been destroyed for eight hours with the screen
 * off, and a binding is one more thing that can fail in the field for no benefit
 * to the deployment.
 *
 * Capture publishes; the UI only reads. Nothing here is on the capture hot path
 * -- the mask snapshot is published at a few hertz, not per analysis frame.
 */
object CaptureBus {
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /** Called from the capture side only. */
    fun publish(transform: (CaptureUiState) -> CaptureUiState) {
        _state.value = transform(_state.value)
    }

    /**
     * Whether a UI is attached and wants preview frames.
     *
     * The deployment runs with the screen off for nine hours, and building a
     * preview bitmap several times a second for nobody would be pure waste on
     * the one thread that must not fall behind. The UI sets this while it is
     * composed and clears it when it goes away.
     */
    @Volatile
    var previewWanted: Boolean = false

    fun reset() {
        _state.value = CaptureUiState()
    }
}
