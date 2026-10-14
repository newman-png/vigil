package dev.vigil.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.vigil.Graph
import dev.vigil.engine.Device
import dev.vigil.engine.Notifications
import dev.vigil.engine.Vigil
import dev.vigil.trigger.AdhanListener

/**
 * Keeps VIGIL's process alive so triggers are noticed and an unfinished takeover can come back.
 * It watches the screen turning on/off and runs a short periodic tick; all decisions stay in the engine.
 */
class VigilService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    /** Ticks since the service started, for the once-a-minute notification listener check. */
    private var ticks = 0L

    /** Awake and unlocked at the last tick or screen event. */
    private var present = false

    private var receiverRegistered = false

    private val tick = object : Runnable {
        override fun run() {
            // One failing tick must not stop the ones after it, or crash the process in the background
            // (storage errors included, hence Exception).
            try {
                onTick()
            } catch (e: Exception) {
                Log.e(TAG, "Tick failed", e)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        present = Device.isPresent(context)
                        Vigil.onScreenOn(context)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        present = false
                        Vigil.onScreenOff(context)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Handling ${intent.action} failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // startForeground fails without its channel. The app creates the channels at start-up; this makes sure.
        Notifications.createChannels(this)
        enterForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() needs its own startForeground(); when already in the foreground this only
        // updates the notification.
        val foreground = enterForeground()
        if (!running) {
            if (!foreground) {
                // Refused (a sticky restart in the background without the battery exemption). startForeground was
                // called, so stopping is safe; the next foreground moment (opening the app, an alarm, a takeover)
                // starts the service again.
                stopSelf(startId)
                return START_NOT_STICKY
            }
            begin()
        }
        AdhanListener.ensureBound(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        if (receiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        receiverRegistered = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** The service is in the foreground for the first time: start watching. */
    private fun begin() {
        try {
            // SCREEN_ON/OFF and USER_PRESENT are protected broadcasts that only the system can send, so exporting
            // the receiver opens nothing. USER_PRESENT comes from System UI rather than the system process, and
            // a receiver registered as not exported does not get it on every phone.
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                Context.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        } catch (e: RuntimeException) {
            // The tick below still notices an unlocked phone, only a little later.
            Log.e(TAG, "Could not watch the screen", e)
        }
        present = Device.isPresent(this)
        ticks = 0
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun onTick() {
        ticks++
        // Each part on its own, so one that keeps failing does not stop the others.
        guard("Housekeeping") { Vigil.onServiceTick(this) }
        if (ticks % BIND_CHECK_TICKS == 0L) AdhanListener.ensureBound(this)
        val nowPresent = Device.isPresent(this)
        val wasPresent = present
        present = nowPresent
        guard("Presence check") {
            if (nowPresent && !wasPresent) {
                // The phone became usable without the unlock broadcast reaching VIGIL (some phones send it late or
                // not at all): handle it like one. This also shows a due morning check-in.
                Vigil.onScreenOn(this)
            } else if (nowPresent) {
                // The morning check-in becomes due at the day boundary, often while the phone is already in use.
                val checkIn = Graph.state.current.checkIn
                if (checkIn != null && !checkIn.fired) Vigil.onUserPresent()
            }
        }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "$what failed", e)
        }
    }

    /** Enters (or stays in) the foreground with the current status. False when the system refuses. */
    private fun enterForeground(): Boolean {
        val text = runCatching { Vigil.statusLine() }.getOrDefault("Running.")
        val notification = Notifications.service(this, text, Graph.state.current.quiet)
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifications.ID_SERVICE, notification)
            }
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Not allowed to enter the foreground now: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not enter the foreground: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not enter the foreground: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "VigilService"
        private const val TICK_MS = 5_000L

        /** About once a minute: the notification listener is checked every this many ticks. */
        private const val BIND_CHECK_TICKS = 12L

        @Volatile
        var running = false
            private set

        /**
         * Starts the service (or tells the running one to refresh). Refused while VIGIL is in the background
         * without an exemption (the battery one, an exact alarm going off, a visible takeover); that is logged only.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, VigilService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // Happens when the battery exemption is missing; the setup screen asks for it.
                Log.w(TAG, "Foreground service not allowed right now: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not start the service: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not start the service: ${e.message}")
            }
        }

        fun refreshNotification(context: Context) {
            if (!running) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                nm.notify(Notifications.ID_SERVICE, Notifications.service(context, Vigil.statusLine(), Graph.state.current.quiet))
            }
        }
    }
}
