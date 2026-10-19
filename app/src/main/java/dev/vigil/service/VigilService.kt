package dev.vigil.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.vigil.engine.Device
import dev.vigil.engine.Notifications
import dev.vigil.engine.Vigil
import dev.vigil.trigger.AdhanListener
import dev.vigil.trigger.CallWatcher
import dev.vigil.trigger.StepCounter

/**
 * Keeps VIGIL's process alive so triggers are noticed and a takeover can come back. It turns screen on/off/unlock,
 * calls and the step counter into engine inputs and runs a short periodic tick (the engine's heartbeat); all decisions
 * stay in the engine. It enters the foreground in onCreate and in every onStartCommand, and no failure inside escapes.
 */
class VigilService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    /** Ticks since the service started, for the slower checks. */
    private var ticks = 0L

    /** Awake and unlocked at the last tick or screen event. */
    private var present = false

    private var receiverRegistered = false

    private val tick = object : Runnable {
        override fun run() {
            // One failing tick must not stop the ones after it, or crash the process in the background.
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
                    Intent.ACTION_SCREEN_ON -> {
                        present = Device.isPresent(context)
                        Vigil.onScreen(on = true, unlocked = present)
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        present = true
                        Vigil.onScreen(on = true, unlocked = true)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        present = false
                        Vigil.onScreen(on = false, unlocked = false)
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
        guard("Channels") { Notifications.createChannels(this) }
        enterForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() needs its own startForeground(); when already in the foreground this only
        // updates the notification.
        val foreground = enterForeground()
        if (!running) {
            if (!foreground) {
                // Refused (a sticky restart in the background without the battery exemption). startForeground was
                // tried, so stopping is safe; the next foreground moment (opening the app, an alarm, a takeover)
                // starts the service again.
                stopSelf(startId)
                return START_NOT_STICKY
            }
            guard("Start") { begin() }
        }
        guard("Listener check") { AdhanListener.ensureBound(this) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        if (receiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        receiverRegistered = false
        runCatching { CallWatcher.stop(this) }
        runCatching { StepCounter.stop(this) }
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
        guard("Call watch") { CallWatcher.start(this) }
        guard("Step counter") { StepCounter.sync(this) }
        // Not yet seen as present: when the service starts on a phone already in use (after a reboot, an update or a
        // restart), the first tick handles it like an unlock, so a waiting takeover comes back. The engine learns the
        // screen state now (it may have changed while VIGIL was not running).
        present = false
        guard("Screen state") {
            val on = Device.isInteractive(this)
            if (on != Vigil.state.value.screenOn) Vigil.onScreen(on = on, unlocked = on && Device.isPresent(this))
        }
        ticks = 0
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun onTick() {
        ticks++
        // Each part on its own, so one that keeps failing does not stop the others.
        if (ticks % ENGINE_TICKS == 1L) guard("Engine tick") { Vigil.onTick() }
        guard("Call check") { CallWatcher.check(this) }
        if (ticks % SLOW_TICKS == 0L) {
            guard("Listener check") { AdhanListener.ensureBound(this) }
            // READ_PHONE_STATE (or PHYSICAL ACTIVITY) may have been granted since the service started.
            guard("Call watch") { CallWatcher.start(this) }
            guard("Step counter") { StepCounter.sync(this) }
        }
        val nowPresent = Device.isPresent(this)
        val wasPresent = present
        present = nowPresent
        // The phone became usable without the unlock broadcast reaching VIGIL (some phones send it late or not at
        // all): handle it like one.
        if (nowPresent && !wasPresent) guard("Presence check") { Vigil.onScreen(on = true, unlocked = true) }
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
        val quiet = runCatching { Vigil.state.value.toggles.quiet }.getOrDefault(false)
        return try {
            val notification = Notifications.service(this, text, quiet)
            startForeground(Notifications.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not enter the foreground", e)
            false
        }
    }

    companion object {
        private const val TAG = "VigilService"
        private const val TICK_MS = 5_000L

        /** The engine's heartbeat runs every this many ticks (15 s); exact alarms cover the timed moments. */
        private const val ENGINE_TICKS = 3L

        /** About once a minute: the notification listener and the call watch are checked every this many ticks. */
        private const val SLOW_TICKS = 12L

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

        /** Puts [text] (default: the current status) on the always-on notification, when the service runs. */
        fun refreshNotification(context: Context, text: String? = null) {
            if (!running) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                val line = text ?: Vigil.statusLine()
                nm.notify(Notifications.ID_SERVICE, Notifications.service(context, line, Vigil.state.value.toggles.quiet))
            }
        }
    }
}
