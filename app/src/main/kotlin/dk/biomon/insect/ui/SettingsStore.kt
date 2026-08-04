package dk.biomon.insect.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dk.biomon.insect.AppSettings
import dk.biomon.insect.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "biomon_settings")

/**
 * The one way to get at persisted settings. Called by the UI and by the capture
 * service, so it is a process-wide singleton over a single DataStore instance --
 * two DataStores over one file in one process is a documented way to corrupt it.
 */
object SettingsStore {
    @Volatile
    private var instance: SettingsRepository? = null

    fun get(context: Context): SettingsRepository {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: DataStoreSettings(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Slider bounds, shared with [SettingsScreen] so the UI cannot offer a value the
 * store would then clamp behind the user's back.
 */
internal object SettingsRanges {
    val analysisFps = 1f..10f
    val movingFps = 1f..8f
    val stationaryFps = 0.5f..3f
    val postRollSeconds = 0f..30f
    val jpegQuality = 85f..100f
    val minContrastFraction = 0.005f..0.15f
    val noiseSigmas = 1.5f..10f
    val minBlobAreaPx = 1f..64f
    val forcedRefreshSeconds = 15f..900f
    val focusDiopters = 0f..12f
}

private val DEFAULTS = AppSettings()

private object Keys {
    val analysisFps = intPreferencesKey("analysis_fps")
    val movingFps = floatPreferencesKey("moving_fps")
    val stationaryFps = floatPreferencesKey("stationary_fps")
    val postRollMillis = longPreferencesKey("post_roll_millis")
    val jpegQuality = intPreferencesKey("jpeg_quality")
    val minContrastFraction = floatPreferencesKey("min_contrast_fraction")
    val noiseSigmas = floatPreferencesKey("noise_sigmas")
    val minBlobAreaPx = intPreferencesKey("min_blob_area_px")
    val forcedRefreshSeconds = intPreferencesKey("forced_refresh_seconds")
    val focusDiopters = floatPreferencesKey("focus_diopters")
}

/**
 * The single place that constructs the `:core` configs.
 *
 * `TriggerConfig` and `CaptureConfig` validate in their init blocks, so one bad
 * value -- a half-written preference, a range widened in the UI and forgotten
 * here -- would throw on load and take the app down before a session could
 * start. Everything is coerced here so that cannot happen; the guards are left
 * at their defaults because they are policy, not preference.
 */
private fun settingsOf(
    analysisFps: Int,
    movingFps: Float,
    stationaryFps: Float,
    postRollMillis: Long,
    jpegQuality: Int,
    minContrastFraction: Float,
    noiseSigmas: Float,
    minBlobAreaPx: Int,
    forcedRefreshSeconds: Int,
    focusDiopters: Float,
): AppSettings = AppSettings(
    trigger = DEFAULTS.trigger.copy(
        minContrastFraction = minContrastFraction.finiteOr(DEFAULTS.trigger.minContrastFraction)
            .coerceIn(SettingsRanges.minContrastFraction),
        noiseSigmas = noiseSigmas.finiteOr(DEFAULTS.trigger.noiseSigmas)
            .coerceIn(SettingsRanges.noiseSigmas),
        minBlobAreaPx = minBlobAreaPx.coerceIn(
            SettingsRanges.minBlobAreaPx.start.toInt(),
            SettingsRanges.minBlobAreaPx.endInclusive.toInt(),
        ),
        forcedRefreshSeconds = forcedRefreshSeconds.coerceIn(
            SettingsRanges.forcedRefreshSeconds.start.toInt(),
            SettingsRanges.forcedRefreshSeconds.endInclusive.toInt(),
        ),
    ),
    capture = DEFAULTS.capture.copy(
        analysisFps = analysisFps.coerceIn(
            SettingsRanges.analysisFps.start.toInt(),
            SettingsRanges.analysisFps.endInclusive.toInt(),
        ),
        movingFps = movingFps.finiteOr(DEFAULTS.capture.movingFps)
            .coerceIn(SettingsRanges.movingFps),
        stationaryFps = stationaryFps.finiteOr(DEFAULTS.capture.stationaryFps)
            .coerceIn(SettingsRanges.stationaryFps),
        postRollMillis = postRollMillis.coerceIn(
            (SettingsRanges.postRollSeconds.start * 1000).toLong(),
            (SettingsRanges.postRollSeconds.endInclusive * 1000).toLong(),
        ),
        // DESIGN.md 3.4: below ~q75 the blocking artefacts inflate blob counts in
        // the laptop's residual, and CaptureConfig rejects anything under 85.
        jpegQuality = jpegQuality.coerceIn(
            SettingsRanges.jpegQuality.start.toInt(),
            SettingsRanges.jpegQuality.endInclusive.toInt(),
        ),
    ),
    guards = DEFAULTS.guards,
    focusDistanceDiopters = focusDiopters.finiteOr(DEFAULTS.focusDistanceDiopters)
        .coerceIn(SettingsRanges.focusDiopters),
)

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

private fun AppSettings.clamped(): AppSettings = settingsOf(
    analysisFps = capture.analysisFps,
    movingFps = capture.movingFps,
    stationaryFps = capture.stationaryFps,
    postRollMillis = capture.postRollMillis,
    jpegQuality = capture.jpegQuality,
    minContrastFraction = trigger.minContrastFraction,
    noiseSigmas = trigger.noiseSigmas,
    minBlobAreaPx = trigger.minBlobAreaPx,
    forcedRefreshSeconds = trigger.forcedRefreshSeconds,
    focusDiopters = focusDistanceDiopters,
)

private fun Preferences.toSettings(): AppSettings = settingsOf(
    analysisFps = this[Keys.analysisFps] ?: DEFAULTS.capture.analysisFps,
    movingFps = this[Keys.movingFps] ?: DEFAULTS.capture.movingFps,
    stationaryFps = this[Keys.stationaryFps] ?: DEFAULTS.capture.stationaryFps,
    postRollMillis = this[Keys.postRollMillis] ?: DEFAULTS.capture.postRollMillis,
    jpegQuality = this[Keys.jpegQuality] ?: DEFAULTS.capture.jpegQuality,
    minContrastFraction = this[Keys.minContrastFraction] ?: DEFAULTS.trigger.minContrastFraction,
    noiseSigmas = this[Keys.noiseSigmas] ?: DEFAULTS.trigger.noiseSigmas,
    minBlobAreaPx = this[Keys.minBlobAreaPx] ?: DEFAULTS.trigger.minBlobAreaPx,
    forcedRefreshSeconds = this[Keys.forcedRefreshSeconds] ?: DEFAULTS.trigger.forcedRefreshSeconds,
    focusDiopters = this[Keys.focusDiopters] ?: DEFAULTS.focusDistanceDiopters,
)

private fun AppSettings.writeInto(prefs: androidx.datastore.preferences.core.MutablePreferences) {
    prefs[Keys.analysisFps] = capture.analysisFps
    prefs[Keys.movingFps] = capture.movingFps
    prefs[Keys.stationaryFps] = capture.stationaryFps
    prefs[Keys.postRollMillis] = capture.postRollMillis
    prefs[Keys.jpegQuality] = capture.jpegQuality
    prefs[Keys.minContrastFraction] = trigger.minContrastFraction
    prefs[Keys.noiseSigmas] = trigger.noiseSigmas
    prefs[Keys.minBlobAreaPx] = trigger.minBlobAreaPx
    prefs[Keys.forcedRefreshSeconds] = trigger.forcedRefreshSeconds
    prefs[Keys.focusDiopters] = focusDistanceDiopters
}

private class DataStoreSettings(context: Context) : SettingsRepository {
    private val store = context.settingsDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(seed())

    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            store.data
                .catch { emit(emptyPreferences()) }
                .collect { _settings.value = it.toSettings() }
        }
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        // The transform builds :core config objects, so a caller that hands in an
        // out-of-range value throws inside `transform` itself. Swallow it: losing
        // one settings edit is nothing, losing the process is a lost deployment.
        val next = try {
            transform(_settings.value).clamped()
        } catch (t: Throwable) {
            return
        }
        _settings.value = next
        try {
            store.edit { next.writeInto(it) }
        } catch (t: Throwable) {
            // Persisted or not, the value applies to the next session start.
        }
    }

    /**
     * A blocking first read, because the service asks for settings the instant it
     * starts and an asynchronous load would silently run the first session on
     * defaults instead of whatever was configured in the field. The timeout is
     * there so a wedged read degrades to defaults rather than to a black screen.
     */
    private fun seed(): AppSettings = try {
        runBlocking {
            withTimeoutOrNull(SEED_TIMEOUT_MILLIS) {
                store.data.catch { emit(emptyPreferences()) }.first().toSettings()
            }
        } ?: AppSettings()
    } catch (t: Throwable) {
        AppSettings()
    }

    private companion object {
        const val SEED_TIMEOUT_MILLIS = 2_000L
    }
}
