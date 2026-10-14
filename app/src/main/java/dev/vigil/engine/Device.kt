package dev.vigil.engine

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.os.Process
import android.telecom.TelecomManager
import android.util.Log

/** Read-only facts about the phone right now, plus the one short wake lock VIGIL uses. */
object Device {
    private const val TAG = "VigilDevice"

    /** The front app is looked up here first: usually the last app switch was minutes ago. */
    private const val RECENT_LOOKBACK_MS = 10 * 60_000L

    /**
     * Keys of other apps' call notifications (incoming or ongoing calls), kept up to date by the notification
     * listener. VoIP apps leave the audio mode alone while ringing, so this is the only way to see that.
     */
    @Volatile
    var callNotifications: Set<String> = emptySet()

    private var wakeLock: PowerManager.WakeLock? = null

    /** In a phone or VoIP call, or ringing. Takeovers never appear then. */
    fun inCall(context: Context): Boolean {
        if (callNotifications.isNotEmpty()) return true
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode != AudioManager.MODE_NORMAL && mode != AudioManager.MODE_INVALID && mode != AudioManager.MODE_CURRENT
    }

    fun isInteractive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    /** The lock screen is showing (or the phone is locked behind a dark screen). */
    fun isLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** The user can be at the phone: the screen is on and it is unlocked. */
    fun isPresent(context: Context): Boolean = isInteractive(context) && !isLocked(context)

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
        // This runs every few seconds while a takeover waits, so a short query comes first; its latest event is
        // also the latest overall. Only when nothing happened in it does the long query run.
        lastResumed(usm, end - minOf(RECENT_LOOKBACK_MS, lookbackMs), end)?.let { return it }
        return if (lookbackMs > RECENT_LOOKBACK_MS) lastResumed(usm, end - lookbackMs, end) else null
    }

    private fun lastResumed(usm: UsageStatsManager, from: Long, to: Long): String? = try {
        val events = usm.queryEvents(from, to)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        last
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not read usage events: ${e.message}")
        null
    }

    /** Phone, dialer and emergency apps: a takeover never pushes itself over these. */
    fun isCallApp(context: Context, packageName: String?): Boolean {
        if (packageName == null) return false
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        return packageName == dialer || packageName in CALL_PACKAGES
    }

    /**
     * Keeps the CPU (not the screen) running for up to [ms], so a new takeover gets on screen even when the phone
     * would fall back asleep right after the alarm or notification that fired it. Not reference counted: calling it
     * again only moves the timeout. [releaseAwake] ends it early.
     */
    @Synchronized
    fun keepAwake(context: Context, ms: Long) {
        try {
            val lock = wakeLock ?: context.applicationContext.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vigil:takeover")
                ?.also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
                ?: return
            lock.acquire(ms)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not keep the phone awake: ${e.message}")
        }
    }

    /** The takeover is on screen (it keeps the screen on itself) or nothing is active any more. */
    @Synchronized
    fun releaseAwake() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not release the wake lock: ${e.message}")
        }
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
