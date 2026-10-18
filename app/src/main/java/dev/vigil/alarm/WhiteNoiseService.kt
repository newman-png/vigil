package dev.vigil.alarm

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.vigil.engine.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * White noise for falling asleep: soft (low-passed) random noise made in code and streamed to the media stream, no
 * sound file. A media-playback foreground service, so it keeps playing with the screen locked; its notification has
 * STOP. It stops by itself after [MAX_MS] (8 hours), and VIGIL stops it when the wake alarm rings.
 */
class WhiteNoiseService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private val limit = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entered = try {
            startForeground(Notifications.ID_NOISE, Notifications.whiteNoise(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Not allowed to play now: ${e.message}")
            false
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not enter the foreground", e)
            false
        }
        if (!entered) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            thread = Thread({ play() }, "vigil-white-noise").also { it.start() }
        }
        handler.removeCallbacks(limit)
        handler.postDelayed(limit, MAX_MS)
        _playing.value = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(limit)
        _playing.value = false
        super.onDestroy()
    }

    /** Streams until [running] turns false: brown-ish noise (white noise through a gentle low-pass), fading in. */
    private fun play() {
        val track = try {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(min, RATE))
                .build()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not open the audio track", e)
            running = false
            handler.post { stopSelf() }
            return
        }
        val buffer = ShortArray(CHUNK)
        val random = Random()
        var level = 0.0
        val started = SystemClock.elapsedRealtime()
        try {
            track.setVolume(0f)
            track.play()
            while (running) {
                for (i in buffer.indices) {
                    level = (level + 0.02 * (random.nextDouble() * 2 - 1)) / 1.02
                    buffer[i] = (level * 3.5 * AMPLITUDE).coerceIn(-32767.0, 32767.0).toInt().toShort()
                }
                track.setVolume(min(1f, (SystemClock.elapsedRealtime() - started) / FADE_IN_MS.toFloat()) * VOLUME)
                if (track.write(buffer, 0, buffer.size) < 0) break
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "White noise stopped", e)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    companion object {
        private const val TAG = "VigilNoise"
        private const val RATE = 22_050
        private const val CHUNK = 2_048
        private const val AMPLITUDE = 18_000.0
        private const val VOLUME = 0.8f
        private const val FADE_IN_MS = 4_000L

        /** White noise stops by itself after this long. */
        const val MAX_MS = 8 * 60 * 60_000L

        private val _playing = MutableStateFlow(false)

        /** True while white noise plays. */
        val playing: StateFlow<Boolean> = _playing.asStateFlow()

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WhiteNoiseService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Could not start white noise: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not start white noise: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not start white noise: ${e.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WhiteNoiseService::class.java)) }
        }

        /** The WHITE NOISE switch (wind-down's lights-low step, the night lock screen). */
        fun set(context: Context, on: Boolean) = if (on) start(context) else stop(context)
    }
}

/** STOP on the white noise notification. */
class WhiteNoiseStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WhiteNoiseService.stop(context)
    }
}
