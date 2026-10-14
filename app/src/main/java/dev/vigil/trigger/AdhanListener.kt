package dev.vigil.trigger

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
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
import java.lang.ref.WeakReference

/**
 * The `adhan` trigger. VIGIL does not compute prayer times: it waits for the user's adhan app to post its
 * notification ("It's time for Isha"), reads the prayer name with [AdhanMatcher] and fires the plan's
 * directives for that prayer. Which app and which words count is configured in Settings.
 *
 * It also notices other apps' call notifications, so a takeover never covers a ringing or ongoing call.
 *
 * The system can drop a listener's binding (low memory, battery savers, an app update). It asks to be bound again
 * when that happens, and the service checks about once a minute ([ensureBound]).
 */
class AdhanListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = WeakReference(this)
        connected.value = true
        Device.callNotifications = callKeys() ?: emptySet()
    }

    override fun onListenerDisconnected() {
        connected.value = false
        Device.callNotifications = emptySet()
        rebind(ComponentName(this, AdhanListener::class.java))
    }

    override fun onDestroy() {
        if (instance?.get() === this) {
            instance = null
            connected.value = false
        }
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key in Device.callNotifications) Device.callNotifications = Device.callNotifications - sbn.key
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Another app's odd notification (extras that fail to unparcel) or a storage error while firing must not
        // crash VIGIL's process.
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a notification from ${sbn.packageName}: ${e.message}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
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

    /** Keys of the call notifications showing now, or null when the list cannot be read. */
    private fun callKeys(): Set<String>? = try {
        activeNotifications?.filter(::isCall)?.map { it.key }?.toSet()
    } catch (e: RuntimeException) {
        null
    }

    private fun isCall(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val n = sbn.notification ?: return false
        if (n.category == Notification.CATEGORY_CALL) return true
        // Calling apps that use the call template without setting the category.
        return try {
            n.extras?.getString(Notification.EXTRA_TEMPLATE) == CALL_STYLE
        } catch (e: RuntimeException) {
            false
        }
    }

    /** One notification as seen by the listener, for the settings screen. */
    data class Seen(val packageName: String, val title: String?, val text: String?, val atMs: Long, val prayer: Prayer?)

    companion object {
        private const val TAG = "VigilAdhan"
        private const val RECENT_MAX = 30
        /** Adhan apps often re-post or update the same notification; one prayer fires once per window. */
        private const val DEBOUNCE_MS = 30 * 60_000L
        /** [ensureBound] asks for a rebind at most this often. */
        private const val REBIND_INTERVAL_MS = 30_000L
        private val CALL_STYLE: String = Notification.CallStyle::class.java.name

        private val debouncer = Debouncer(DEBOUNCE_MS)
        private val _recent = MutableStateFlow<List<Seen>>(emptyList())

        /** The connected listener, for re-reading the call notifications. */
        @Volatile
        private var instance: WeakReference<AdhanListener>? = null

        @Volatile
        private var lastRebindMs: Long? = null

        /** Recent notifications from any app (newest first), kept in memory only, to help pick the adhan app. */
        val recent: StateFlow<List<Seen>> = _recent.asStateFlow()

        /** True while the system has the listener bound (so adhan notifications and calls are seen). */
        val connected = MutableStateFlow(false)

        /**
         * Called when the service starts and about once a minute. With notification access granted but the listener
         * not connected, asks the system to bind it again (at most every [REBIND_INTERVAL_MS]). While connected,
         * re-reads the call notifications, so a call whose end was missed does not keep takeovers away for good.
         */
        fun ensureBound(context: Context) {
            try {
                val listener = instance?.get()
                if (connected.value && listener != null) {
                    listener.callKeys()?.let { Device.callNotifications = it }
                    return
                }
                val component = ComponentName(context, AdhanListener::class.java)
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                if (!nm.isNotificationListenerAccessGranted(component)) return
                val now = SystemClock.elapsedRealtime()
                val last = lastRebindMs
                if (last != null && now - last < REBIND_INTERVAL_MS) return
                lastRebindMs = now
                Log.i(TAG, "Notification access is granted but the listener is not connected: asking for a rebind")
                rebind(component)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not check the notification listener: ${e.message}")
            }
        }

        /**
         * Asks the system to bind the listener again. It takes effect when the system had unbound it (the usual
         * reason for a disconnect); a listener whose process died is rebound by the system by itself.
         */
        private fun rebind(component: ComponentName) {
            try {
                NotificationListenerService.requestRebind(component)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not ask for the notification listener to be bound again: ${e.message}")
            }
        }

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
