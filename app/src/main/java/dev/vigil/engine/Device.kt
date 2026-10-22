package dev.vigil.engine

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

/** Read-only facts about the phone right now, plus the one short wake lock VIGIL uses. */
object Device {
    private const val TAG = "VigilDevice"

    /** The front app is looked up here first: usually the last app switch was minutes ago. */
    private const val RECENT_LOOKBACK_MS = 10 * 60_000L

    /** The installed browsers are looked up again after this long (a browser installed meanwhile joins the pool). */
    private const val BROWSERS_TTL_MS = 5 * 60_000L

    /**
     * Keys of other apps' call notifications (incoming or ongoing calls), kept up to date by the notification
     * listener. VoIP apps leave the audio mode alone while ringing, so this is the only way to see that.
     */
    @Volatile
    var callNotifications: Set<String> = emptySet()

    /** Keys of other apps' call notifications that say the call is connected (CallStyle ongoing). */
    @Volatile
    var ongoingCallNotifications: Set<String> = emptySet()

    /** The phone's call state from the TelephonyCallback ([TelephonyManager.CALL_STATE_IDLE] when unknown). */
    @Volatile
    var telephonyState: Int = TelephonyManager.CALL_STATE_IDLE

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var browsers: Set<String> = emptySet()

    @Volatile
    private var browsersAt = 0L

    /** In a phone or VoIP call, or ringing. Takeovers never appear then. */
    fun inCall(context: Context): Boolean {
        if (callNotifications.isNotEmpty() || telephonyState != TelephonyManager.CALL_STATE_IDLE) return true
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode != AudioManager.MODE_NORMAL && mode != AudioManager.MODE_INVALID && mode != AudioManager.MODE_CURRENT
    }

    /**
     * The apps whose call notification is up (a WhatsApp or Instagram call, ringing or connected), read from the
     * notification keys ("user|package|id|tag|uid"). The Guard never closes them, even when one is a pool app.
     */
    fun callOwners(): Set<String> = callNotifications.mapNotNullTo(HashSet()) { it.split('|').getOrNull(1)?.takeIf { p -> p.isNotEmpty() } }

    /** The phone rings (or a VoIP call rings) and no call is connected yet: nothing of VIGIL's may pop up then. */
    fun ringing(context: Context): Boolean = inCall(context) && !callConnected(context)

    /**
     * A call is connected (or being dialled): a phone call (call state off-hook, or the audio in call mode), a VoIP
     * call (the audio in communication mode, or another app's ongoing call notification). Ringing is not connected.
     * Protocol timers and locks pause while this is true.
     */
    fun callConnected(context: Context): Boolean {
        if (telephonyState == TelephonyManager.CALL_STATE_OFFHOOK || ongoingCallNotifications.isNotEmpty()) return true
        return when (context.getSystemService(AudioManager::class.java)?.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT -> true
            else -> false
        }
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

    /** Phone, dialer and emergency apps: a takeover never pushes itself over these, and they are always usable. */
    fun isCallApp(context: Context, packageName: String?): Boolean {
        if (packageName == null) return false
        return packageName == defaultDialer(context) || packageName in CALL_PACKAGES
    }

    /**
     * MESSAGE (a call card's short version: send a message instead): WhatsApp when it is installed, else the phone's
     * messaging app. No contact is stored, so it opens the app, not a chat.
     */
    fun messageIntent(context: Context): Intent =
        (context.packageManager.getLaunchIntentForPackage(WHATSAPP) ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val WHATSAPP = "com.whatsapp"

    /** The default phone app, or null when there is none (or it cannot be read). */
    fun defaultDialer(context: Context): String? = try {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    } catch (e: RuntimeException) {
        null
    }

    /**
     * Every installed browser: the apps that open a plain https link (design: the pool includes them all). Cached for
     * a few minutes. VIGIL itself never counts.
     */
    fun browsers(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (browsersAt != 0L && now - browsersAt < BROWSERS_TTL_MS) return browsers
        val found = try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
            context.packageManager
                .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
                .mapNotNull { it.activityInfo?.packageName }
                .filter { it != context.packageName }
                .toSet()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not list the browsers: ${e.message}")
            return browsers
        }
        browsers = found
        browsersAt = now
        return found
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
