package dev.vigil.engine

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.os.Process
import android.telecom.TelecomManager

/** Read-only facts about the phone right now. */
object Device {
    /**
     * Keys of other apps' call notifications (incoming or ongoing calls), kept up to date by the notification
     * listener. VoIP apps leave the audio mode alone while ringing, so this is the only way to see that.
     */
    @Volatile
    var callNotifications: Set<String> = emptySet()

    /** In a phone or VoIP call, or ringing. Takeovers never appear then. */
    fun inCall(context: Context): Boolean {
        if (callNotifications.isNotEmpty()) return true
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode != AudioManager.MODE_NORMAL && mode != AudioManager.MODE_INVALID && mode != AudioManager.MODE_CURRENT
    }

    fun isInteractive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The app most recently brought to the foreground, from usage stats (null without usage access). */
    fun foregroundPackage(context: Context, lookbackMs: Long = 6 * 60 * 60 * 1000L): String? {
        if (!hasUsageAccess(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - lookbackMs, end)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }

    /** Phone, dialer and emergency apps: a takeover never pushes itself over these. */
    fun isCallApp(context: Context, packageName: String?): Boolean {
        if (packageName == null) return false
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        return packageName == dialer || packageName in CALL_PACKAGES
    }

    private val CALL_PACKAGES = setOf(
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.incallui",
    )
}
