package dev.vigil.trigger

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import dev.vigil.Graph
import dev.vigil.core.engine.AppUsageTracker
import dev.vigil.core.plan.Trigger
import dev.vigil.engine.Device
import dev.vigil.engine.Vigil

/**
 * The `app_usage` trigger. Tells the core [AppUsageTracker] which app is in front; the tracker decides
 * when "opened N times in M minutes" or "used M minutes nonstop" is reached.
 *
 * Two sources: polling UsageStatsManager every few seconds while the screen is on (needs usage access),
 * or, if the user enabled it, the accessibility service, which reports app switches instantly.
 * All calls happen on the main thread.
 */
object UsageWatcher {
    private val tracker = AppUsageTracker()

    /** True while the accessibility service is connected; polling is skipped then. */
    @Volatile
    var accessibilityConnected = false

    /** Usage events have been read up to here (exclusive). */
    private var queriedUntilMs = 0L

    /** Timestamp of the last usage event handled, so overlapping queries don't count an open twice. */
    private var lastEventMs = 0L

    private fun triggers(): Map<String, Trigger.AppUsage> =
        Graph.activeSet().directives
            .mapNotNull { d -> (d.trigger as? Trigger.AppUsage)?.let { d.id to it } }
            .toMap()

    /** The accessibility service saw [packageName] come to the front. */
    fun onAccessibilityForeground(packageName: String, nowMs: Long) = feed(packageName, nowMs)

    /** From the service tick: read new usage events (unless accessibility is on) and check continuous use. */
    fun poll(context: Context, nowMs: Long) {
        val triggers = triggers()
        if (triggers.isEmpty()) {
            queriedUntilMs = nowMs
            return
        }
        if (!accessibilityConnected && Device.isInteractive(context) && Device.hasUsageAccess(context)) {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            if (usm != null) {
                // Events are recorded a moment after their timestamp, so each query overlaps the last by a few seconds.
                val from = if (queriedUntilMs == 0L || nowMs - queriedUntilMs > 10 * 60_000L) nowMs - 15_000L else queriedUntilMs - OVERLAP_MS
                val events = usm.queryEvents(from, nowMs)
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED || e.timeStamp <= lastEventMs) continue
                    lastEventMs = e.timeStamp
                    feed(e.packageName, e.timeStamp, triggers)
                }
                queriedUntilMs = nowMs
            }
        }
        fire(tracker.onTick(nowMs, triggers))
    }

    /** The screen went off: that ends any continuous use, and coming back counts as a new open. */
    fun onScreenOff(nowMs: Long) = feed(null, nowMs)

    /** Forget all counts (after a new plan is imported). */
    fun reset() = tracker.reset()

    private const val OVERLAP_MS = 3_000L

    fun openCount(directiveId: String): Int = tracker.openCount(directiveId)

    val foreground: String? get() = tracker.foreground

    private fun feed(packageName: String?, atMs: Long, triggers: Map<String, Trigger.AppUsage> = triggers()) {
        if (triggers.isEmpty()) return
        fire(tracker.onForeground(packageName, atMs, triggers))
    }

    private fun fire(ids: List<String>) {
        if (ids.isNotEmpty()) Vigil.trigger(ids, Trigger.AppUsage.TYPE)
    }
}

/**
 * Optional: instant app-switch detection for the `app_usage` trigger. It only listens for "a different
 * window came to the front" and never reads screen content.
 */
class UsageAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        UsageWatcher.accessibilityConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The notification shade and the keyboard are not "opening an app".
        if (pkg == "com.android.systemui" || pkg == keyboardPackage()) return
        UsageWatcher.onAccessibilityForeground(pkg, System.currentTimeMillis())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        UsageWatcher.accessibilityConnected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        UsageWatcher.accessibilityConnected = false
        super.onDestroy()
    }

    private fun keyboardPackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
}
