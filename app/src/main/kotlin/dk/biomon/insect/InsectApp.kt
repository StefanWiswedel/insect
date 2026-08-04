package dk.biomon.insect

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * Application entry point. Deliberately almost empty.
 *
 * The only thing that happens here is notification-channel creation, and it
 * happens here rather than in the capture service because `startForeground` has
 * to post a notification within five seconds of `onStartCommand` or the platform
 * kills the service. Creating the channel is a binder round trip; doing it once
 * at process start takes it off that deadline. Process start also covers the
 * case that matters most in the field: the service being recreated by
 * `START_STICKY` after an OS kill, with no Activity involved.
 *
 * There is no dependency-injection graph and no eager initialisation of the
 * capture stack. Nothing here may assume a UI ever runs -- the deployment case
 * is a phone with the screen off for nine hours.
 */
class InsectApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_LOW: the notification must be persistent and readable at a
        // glance, but a sound or heads-up every time the frame count changes
        // would be intolerable on a nine-hour deployment.
        val channel = NotificationChannel(
            CAPTURE_CHANNEL_ID,
            CAPTURE_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows session progress while the trap is capturing."
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CAPTURE_CHANNEL_ID = "biomon.capture"
        const val CAPTURE_CHANNEL_NAME = "Capture session"
    }
}
