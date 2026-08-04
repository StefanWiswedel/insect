package dk.biomon.insect.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * The only Activity, and it is incidental.
 *
 * The deployment runs with the screen off and the Activity destroyed; nothing in
 * the capture path may depend on this class existing. It exists to grant
 * permissions, aim the camera, and start and stop the service.
 */
class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)
    private var batteryExempt by mutableStateOf(true)
    private var allFilesAccess by mutableStateOf(true)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result[Manifest.permission.CAMERA] ?: hasCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Belt and braces alongside the manifest's screenOrientation. The manifest
        // attribute is a request the system is free to ignore -- OEM display
        // modes, desktop/freeform windowing and forced-resizable developer
        // options all override it -- whereas this is applied to the running
        // activity. The rig is a phone on a stand pointing straight down, which
        // is a portrait posture; the sensor's own orientation is unaffected and
        // captured frames are never rotated to match.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        permissionsGranted = hasCamera()
        if (!permissionsGranted) requestPermissions.launch(requiredPermissions())

        setContent {
            BiomonTheme {
                var showSettings by remember { mutableStateOf(false) }
                var askedThisLaunch by remember { mutableStateOf(false) }
                val settings = remember { SettingsStore.get(applicationContext) }

                if (showSettings) {
                    SettingsScreen(repository = settings, onBack = { showSettings = false })
                } else {
                    CaptureScreen(
                        settingsRepository = settings,
                        permissionsGranted = permissionsGranted,
                        onRequestPermissions = { requestPermissions.launch(requiredPermissions()) },
                        onOpenSettings = { showSettings = true },
                    )
                }

                // Asked up front rather than shown as a banner: a throttled
                // session is a lost day, and a banner on a screen nobody scrolls
                // is the same as no warning at all.
                if (!batteryExempt && !askedThisLaunch) {
                    SetupDialog(
                        title = "Allow unrestricted battery use",
                        body = "A deployment runs for nine hours with the screen off. " +
                            "With battery optimisation on, the system can defer the " +
                            "timer that writes the power log, and a session that " +
                            "loses its power samples cannot answer the question it " +
                            "was run to answer.",
                        confirm = "Open settings",
                        onConfirm = {
                            askedThisLaunch = true
                            requestBatteryExemption()
                        },
                        onDismiss = { askedThisLaunch = true },
                    )
                } else if (!allFilesAccess && !askedThisLaunch) {
                    SetupDialog(
                        title = "Allow access to all files",
                        body = "Without this, sessions are written to app-specific " +
                            "storage, which Android deletes when the app is " +
                            "uninstalled and which is awkward to reach over USB. " +
                            "With it, they go to DCIM/Biomon, appear in the " +
                            "gallery and over USB, and survive a reinstall.",
                        confirm = "Open settings",
                        onConfirm = {
                            askedThisLaunch = true
                            requestAllFilesAccess()
                        },
                        onDismiss = { askedThisLaunch = true },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Both permissions are granted in system settings, so the only way to
        // learn the outcome is to look again on the way back.
        permissionsGranted = hasCamera()
        batteryExempt = isBatteryExempt()
        allFilesAccess = hasAllFilesAccess()
    }

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun isBatteryExempt(): Boolean = try {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    } catch (t: Throwable) {
        true
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Nine hours of screen-off capture is exactly what battery optimisation
     * exists to prevent. The foreground service and wake lock carry the camera,
     * but Doze can still defer the timer that writes the power log -- and a
     * manifest with sixty missing samples is a deployment that did not answer the
     * question it was run to answer.
     */
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (t: Throwable) {
            // Some builds refuse the direct intent; the general settings page is
            // better than nothing.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: Throwable) {
                // Nothing further to offer; the service still runs, just at risk.
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (ignored: Throwable) {
                // The app falls back to app-specific storage and records it.
            }
        }
    }
}

@Composable
private fun SetupDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
