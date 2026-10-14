package dev.vigil.trigger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.vigil.Graph
import dev.vigil.core.engine.AdhanMatcher
import dev.vigil.core.engine.Debouncer
import dev.vigil.core.plan.Prayer
import dev.vigil.core.plan.Trigger
import dev.vigil.engine.Device
import dev.vigil.engine.Vigil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The `adhan` trigger. VIGIL does not compute prayer times: it waits for the user's adhan app to post its
 * notification ("It's time for Isha"), reads the prayer name with [AdhanMatcher] and fires the plan's
 * directives for that prayer. Which app and which words count is configured in Settings.
 *
 * It also notices other apps' call notifications, so a takeover never covers a ringing or ongoing call.
 */
class AdhanListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected.value = true
        Device.callNotifications = runCatching { activeNotifications.filter(::isCall).map { it.key }.toSet() }.getOrDefault(emptySet())
    }

    override fun onListenerDisconnected() {
        connected.value = false
        Device.callNotifications = emptySet()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key in Device.callNotifications) Device.callNotifications = Device.callNotifications - sbn.key
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // Calls (including VoIP apps that are still ringing) keep takeovers away; see Device.inCall.
        if (isCall(sbn)) Device.callNotifications = Device.callNotifications + sbn.key
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        onNotification(sbn.packageName, title, text, System.currentTimeMillis(), test = false)
    }

    private fun isCall(sbn: StatusBarNotification): Boolean =
        sbn.packageName != packageName && sbn.notification?.category == Notification.CATEGORY_CALL

    /** One notification as seen by the listener, for the settings screen. */
    data class Seen(val packageName: String, val title: String?, val text: String?, val atMs: Long, val prayer: Prayer?)

    companion object {
        private const val RECENT_MAX = 30
        /** Adhan apps often re-post or update the same notification; one prayer fires once per window. */
        private const val DEBOUNCE_MS = 30 * 60_000L

        private val debouncer = Debouncer(DEBOUNCE_MS)
        private val _recent = MutableStateFlow<List<Seen>>(emptyList())

        /** Recent notifications from any app (newest first), kept in memory only, to help pick the adhan app. */
        val recent: StateFlow<List<Seen>> = _recent.asStateFlow()

        val connected = MutableStateFlow(false)

        /**
         * Handles a notification. With [test] true (the settings test button) it skips the debounce and logs
         * the trigger as "adhan_test", but otherwise goes through exactly the same path.
         * Returns the prayer it matched, if any.
         */
        fun onNotification(packageName: String, title: String?, text: String?, atMs: Long, test: Boolean): Prayer? {
            val prayer = AdhanMatcher.match(Graph.settings.adhanConfig, packageName, title, text)
            if (!test) {
                _recent.value = (listOf(Seen(packageName, title, text, atMs, prayer)) + _recent.value).take(RECENT_MAX)
            }
            if (prayer == null) return null
            if (!test && !debouncer.accept(prayer.json, atMs)) return prayer
            Vigil.trigger(directivesFor(prayer), if (test) "adhan_test" else Trigger.Adhan.TYPE)
            return prayer
        }

        /** Active directives triggered by this prayer. */
        fun directivesFor(prayer: Prayer): List<String> =
            Graph.activeSet().directives.filter { (it.trigger as? Trigger.Adhan)?.prayer == prayer }.map { it.id }
    }
}
