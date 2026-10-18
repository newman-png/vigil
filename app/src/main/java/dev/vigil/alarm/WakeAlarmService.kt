package dev.vigil.alarm

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import dev.vigil.engine.Device
import dev.vigil.engine.Notifications
import dev.vigil.engine.Vigil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Rings the wake alarm: a beeping tone made in code, looping on the alarm stream (heard in silent mode and Do Not
 * Disturb), its volume rising over 30 seconds, with vibration, and the full-screen alarm ([WakeAlarmActivity]). A
 * foreground service with a wake lock, so it keeps ringing with the screen off and VIGIL in the background. It stops
 * when the alarm is dismissed ([stop]) or at the engine's stop time, when it reports the ring unanswered (the engine
 * rings it again later). Silent while a call is on.
 */
class WakeAlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedAt = 0L
    private var test = false

    private val ramp = object : Runnable {
        override fun run() {
            val t = track ?: return
            val rising = min(1f, (SystemClock.elapsedRealtime() - startedAt) / RAMP_MS.toFloat())
            val volume = if (Device.inCall(this@WakeAlarmService)) 0f else START_VOLUME + (1f - START_VOLUME) * rising
            runCatching { t.setVolume(volume) }
            handler.postDelayed(this, RAMP_STEP_MS)
        }
    }

    private val timeout = Runnable { finish(unanswered = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        test = intent?.getBooleanExtra(EXTRA_TEST, false) ?: false
        val stopAt = intent?.getLongExtra(EXTRA_STOP_AT, 0L) ?: 0L
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startRinging(stopAt)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        silence()
        _ringing.value = false
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        // Never a full-screen alarm over a call: then the notification only leads to it.
        val n = Notifications.wakeAlarm(this, test, fullScreen = !Device.inCall(this))
        startForeground(Notifications.ID_ALARM, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.w(TAG, "Not allowed to ring from the background: ${e.message}")
        false
    } catch (e: RuntimeException) {
        Log.e(TAG, "Could not enter the foreground", e)
        false
    }

    private fun startRinging(stopAtMs: Long) {
        silence()
        startedAt = SystemClock.elapsedRealtime()
        val left = (stopAtMs - System.currentTimeMillis()).coerceIn(MIN_RING_MS, MAX_RING_MS)
        wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vigil:wakealarm")
            ?.also {
                it.setReferenceCounted(false)
                it.acquire(left + WAKE_LOCK_EXTRA_MS)
            }
        track = try {
            beepTrack().also {
                it.setVolume(START_VOLUME)
                it.play()
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not play the alarm sound", e)
            null
        }
        vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator?.also {
            try {
                it.vibrate(
                    VibrationEffect.createWaveform(VIBRATION, 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not vibrate: ${e.message}")
            }
        }
        _ringing.value = true
        handler.post(ramp)
        handler.postDelayed(timeout, left)
        // The full-screen intent opens the alarm on a locked or dark phone; in use it is started directly where allowed.
        if (!Device.inCall(this) && Settings.canDrawOverlays(this)) {
            try {
                startActivity(WakeAlarmActivity.intent(this, test))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not open the alarm screen: ${e.message}")
            }
        }
    }

    /** Stops the sound, the vibration and the wake lock. */
    private fun silence() {
        handler.removeCallbacks(ramp)
        handler.removeCallbacks(timeout)
        track?.let { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = null
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    /** The ring is over: unanswered (its time ran out) is reported to the engine, which rings it again later. */
    private fun finish(unanswered: Boolean) {
        silence()
        _ringing.value = false
        if (unanswered) {
            try {
                Vigil.wakeAlarmUnanswered(test)
            } catch (e: Exception) {
                Log.e(TAG, "Reporting the unanswered alarm failed", e)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Four short beeps and a pause, made in code (no sound file), looped by the track. */
    private fun beepTrack(): AudioTrack {
        val samples = ShortArray(RATE * PATTERN_MS / 1000)
        for (beep in 0 until BEEPS) {
            val from = RATE * beep * (BEEP_MS + GAP_MS) / 1000
            val length = RATE * BEEP_MS / 1000
            val edge = RATE * EDGE_MS / 1000
            for (i in 0 until length) {
                val t = i.toDouble() / RATE
                // A short fade at both ends, so the beeps do not click.
                val envelope = min(1.0, min(i, length - 1 - i).toDouble() / edge)
                val wave = 0.75 * sin(2 * PI * TONE_HZ * t) + 0.25 * sin(4 * PI * TONE_HZ * t)
                samples[from + i] = (wave * envelope * AMPLITUDE).toInt().toShort()
            }
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        track.setLoopPoints(0, samples.size, -1)
        return track
    }

    companion object {
        private const val TAG = "VigilWakeAlarm"
        private const val EXTRA_STOP_AT = "stop_at"
        private const val EXTRA_TEST = "test"

        private const val RATE = 44_100
        private const val TONE_HZ = 880.0
        private const val BEEPS = 4
        private const val BEEP_MS = 110
        private const val GAP_MS = 70
        private const val EDGE_MS = 6
        private const val PATTERN_MS = 1_400
        private const val AMPLITUDE = 28_000.0

        /** The volume starts low and rises to full over [RAMP_MS]. */
        private const val START_VOLUME = 0.15f
        private const val RAMP_MS = 30_000L
        private const val RAMP_STEP_MS = 500L

        private val VIBRATION = longArrayOf(0, 700, 500)

        /** A ring lasts at least this long, and never more than the engine's longest (an hour). */
        private const val MIN_RING_MS = 30_000L
        private const val MAX_RING_MS = 60 * 60_000L
        private const val WAKE_LOCK_EXTRA_MS = 60_000L

        private val _ringing = MutableStateFlow(false)

        /** True while the alarm sounds. */
        val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

        /** Rings now until [stopAt] (the engine's [dev.vigil.core.machine.Effect.RingWakeAlarm]); [test] for TEST. */
        fun ring(context: Context, stopAt: java.time.Instant, test: Boolean) {
            val intent = Intent(context, WakeAlarmService::class.java)
                .putExtra(EXTRA_STOP_AT, stopAt.toEpochMilli())
                .putExtra(EXTRA_TEST, test)
            try {
                context.startForegroundService(intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Could not start ringing: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not start ringing: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not start ringing: ${e.message}")
            }
        }

        /** Stops a ringing alarm (dismissed, or the engine is done with it). */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WakeAlarmService::class.java)) }
        }
    }
}
