package dev.vigil.takeover

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import dev.vigil.Graph
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The soft chime at the end of a timer marked `chime` (a study round or break, the attention reset's look) and of the
 * gym's rest timer: a bell-like tone made in code (no sound file), with one short vibration. The caller leaves it out
 * while QUIET is on; the app's sound and vibration settings switch its parts off. [play]'s `loud` puts it on the alarm
 * stream (a full-intensity protocol: heard with the ringer on vibrate), else it sounds like a notification.
 */
object Chime {
    private const val TAG = "VigilChime"
    private const val RATE = 44_100
    private const val LENGTH_MS = 1_400
    private const val TONE_HZ = 660.0
    private const val ATTACK_MS = 12
    private const val DECAY = 3.2
    private const val AMPLITUDE = 14_000.0
    private const val VIBRATION_MS = 140L
    private const val VIBRATION_STRENGTH = 160

    /** One chime: a fundamental and a soft octave, a quick rise and a long fade. */
    private val samples: ShortArray by lazy {
        val n = RATE * LENGTH_MS / 1000
        val attack = RATE * ATTACK_MS / 1000
        ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val envelope = min(1.0, i.toDouble() / attack) * exp(-DECAY * t)
            val wave = 0.8 * sin(2 * PI * TONE_HZ * t) + 0.2 * sin(4 * PI * TONE_HZ * t)
            (wave * envelope * AMPLITUDE).toInt().toShort()
        }
    }

    private val main = Handler(Looper.getMainLooper())

    fun play(context: Context, loud: Boolean) {
        val settings = Graph.settings
        if (settings.soundEnabled) {
            try {
                sound(loud)
            } catch (e: RuntimeException) {
                Log.w(TAG, "No chime sound: ${e.message}")
            }
        }
        if (settings.vibrationEnabled) {
            try {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(VIBRATION_MS, VIBRATION_STRENGTH),
                    VibrationAttributes.createForUsage(if (loud) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION),
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "No chime vibration: ${e.message}")
            }
        }
    }

    private fun sound(loud: Boolean) {
        val data = samples
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
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
            .setBufferSizeInBytes(data.size * 2)
            .build()
        track.write(data, 0, data.size)
        track.play()
        // A static track plays once; it is let go when it is over.
        main.postDelayed({ runCatching { track.release() } }, LENGTH_MS + 300L)
    }
}
