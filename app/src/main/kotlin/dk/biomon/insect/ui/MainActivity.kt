package dk.biomon.insect.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * The only Activity, and it is incidental.
 *
 * The deployment runs with the screen off and the Activity destroyed; nothing in
 * the capture path may depend on this class existing. It exists to grant
 * permissions, aim the camera, and start and stop the service.
 */
class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result[Manifest.permission.CAMERA] ?: hasCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = hasCamera()
        if (!permissionsGranted) requestPermissions.launch(requiredPermissions())

        setContent {
            BiomonTheme {
                var showSettings by remember { mutableStateOf(false) }
                val settings = remember { SettingsStore.get(applicationContext) }
                if (showSettings) {
                    SettingsScreen(
                        repository = settings,
                        onBack = { showSettings = false },
                    )
                } else {
                    CaptureScreen(
                        permissionsGranted = permissionsGranted,
                        batteryExemptionNeeded = !isBatteryExempt(),
                        onRequestPermissions = { requestPermissions.launch(requiredPermissions()) },
                        onRequestBatteryExemption = { requestBatteryExemption() },
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
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

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

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
}
