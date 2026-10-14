package dev.vigil.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.vigil.R
import dev.vigil.takeover.TakeoverActivity
import dev.vigil.ui.MainActivity

object Notifications {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_TAKEOVER = "takeover"
    const val ID_SERVICE = 1
    const val ID_TAKEOVER = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "VIGIL is watching", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The always-on notification that keeps VIGIL running."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TAKEOVER, "Directives", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens the takeover when a directive fires. The takeover makes its own sound."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun service(context: Context, text: String): Notification =
        Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("VIGIL")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
            .build()

    /**
     * The notification behind every takeover. With [fullScreen] the system opens the takeover by itself when
     * the phone is locked or the screen is off. During a call it is posted without that, so the takeover
     * never covers the call screen.
     */
    fun takeover(context: Context, title: String, fullScreen: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context, 1, TakeoverActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_TAKEOVER)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("VIGIL · ${title.uppercase()}")
            .setContentText("A directive is waiting. Tap to open it.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (fullScreen) setFullScreenIntent(open, true) }
            .build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
