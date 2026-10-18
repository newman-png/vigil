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
import dev.vigil.core.protocol.Prayer
import dev.vigil.engine.Device
import dev.vigil.engine.Vigil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * The adhan trigger (`event:adhan:<prayer>`). VIGIL does not compute prayer times: it waits for the user's adhan app
 * to post its notification ("It's time for Isha"), reads the prayer name with [AdhanMatcher] and gives the engine its
 * adhan input. Which app and which words count is configured in SETUP ([dev.vigil.data.Settings]).
 *
 * It also notices other apps' call notifications: a ringing or ongoing call keeps takeovers away, and a connected
 * VoIP call pauses protocol timers and locks ([CallWatcher]).
 *
 * The system can drop a listener's binding (low memory, battery savers, an app update). It asks to be bound again
 * when that happens, and the service checks about once a minute ([ensureBound]).
 */
class AdhanListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = WeakReference(this)
        connected.value = true
        readCalls()
    }

    override fun onListenerDisconnected() {
        connected.value = false
        Device.callNotifications = emptySet()
        Device.ongoingCallNotifications = emptySet()
        CallWatcher.check(this)
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
        if (sbn.key in Device.callNotifications || sbn.key in Device.ongoingCallNotifications) {
            Device.callNotifications = Device.callNotifications - sbn.key
            Device.ongoingCallNotifications = Device.ongoingCallNotifications - sbn.key
            CallWatcher.check(this)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Another app's odd notification (extras that fail to unparcel) or a storage error in the engine step must not
        // crash VIGIL's process.
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a notification from ${sbn.packageName}: ${e.message}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // Calls (including VoIP apps that are still ringing) keep takeovers away; a connected one pauses protocols.
        if (isCall(sbn)) {
            Device.callNotifications = Device.callNotifications + sbn.key
            Device.ongoingCallNotifications =
                if (isOngoingCall(sbn)) Device.ongoingCallNotifications + sbn.key else Device.ongoingCallNotifications - sbn.key
            CallWatcher.check(this)
        }
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        onNotification(sbn.packageName, title, text, System.currentTimeMillis(), test = false)
    }

    /** Re-reads the call notifications showing now (after connecting, and about once a minute). */
    private fun readCalls() {
        val calls = try {
            activeNotifications?.filter(::isCall)
        } catch (e: RuntimeException) {
            null
        } ?: return
        Device.callNotifications = calls.map { it.key }.toSet()
        Device.ongoingCallNotifications = calls.filter(::isOngoingCall).map { it.key }.toSet()
        CallWatcher.check(this)
    }

    private fun isCall(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val n = sbn.notification ?: return false
        // Only a live call: a missed-call or "call back" notice also uses the call category but stays in the shade,
        // and would otherwise keep every takeover away until it is cleared.
        if (n.category == Notification.CATEGORY_CALL) {
            val live = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
            return live || n.fullScreenIntent != null
        }
        // Calling apps that use the call template without setting the category.
        return try {
            n.extras?.getString(Notification.EXTRA_TEMPLATE) == CALL_STYLE
        } catch (e: RuntimeException) {
            false
        }
    }

    /** A call notification that says the call is connected (CallStyle "ongoing"); ringing and screening are not. */
    private fun isOngoingCall(sbn: StatusBarNotification): Boolean = try {
        sbn.notification?.extras?.getInt(Notification.EXTRA_CALL_TYPE, 0) == Notification.CallStyle.CALL_TYPE_ONGOING
    } catch (e: RuntimeException) {
        false
    }

    /** One notification as seen by the listener, for the SETUP screen. */
    data class Seen(val packageName: String, val title: String?, val text: String?, val atMs: Long, val prayer: Prayer?)

    companion object {
        private const val TAG = "VigilAdhan"
        private const val RECENT_MAX = 30

        /** Adhan apps often re-post or update the same notification; the engine also ignores repeats within 30 min. */
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
                    listener.readCalls()
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
         * Handles a notification and returns the prayer it matched, if any. A match gives the engine its adhan input
         * (repeats within 30 minutes are ignored). With [test] true (the SETUP test button) it skips the debounce and
         * starts a TEST preview of that prayer's protocol instead, which never touches the real prayer memory.
         */
        fun onNotification(packageName: String, title: String?, text: String?, atMs: Long, test: Boolean): Prayer? {
            val prayer = AdhanMatcher.match(Graph.settings.adhanConfig, packageName, title, text)
            if (!test) {
                _recent.value = (listOf(Seen(packageName, title, text, atMs, prayer)) + _recent.value).take(RECENT_MAX)
            }
            if (prayer == null) return null
            if (test) {
                Vigil.file().prayerProtocol(prayer)?.let { Vigil.startManual(it.id, test = true) }
                return prayer
            }
            val settings = Graph.settings
            if (settings.lastAdhanPackage != packageName) settings.lastAdhanPackage = packageName
            if (!debouncer.accept(prayer.json, atMs)) return prayer
            Vigil.onAdhan(prayer)
            return prayer
        }

        /** The protocol ids this prayer's adhan starts (for the SETUP screen). */
        fun protocolsFor(prayer: Prayer): List<String> = listOfNotNull(Vigil.file().prayerProtocol(prayer)?.id)

        /** The v1 name of [protocolsFor], kept for the SETUP screen. */
        fun directivesFor(prayer: Prayer): List<String> = protocolsFor(prayer)
    }
}
