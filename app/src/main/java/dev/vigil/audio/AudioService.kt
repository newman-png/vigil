package dev.vigil.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.vigil.core.machine.Effect
import dev.vigil.engine.Notifications
import dev.vigil.engine.Vigil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * VIGIL's sound, one at a time (design M8): white noise and a metronome made in code, or an audio file the user added
 * ([AudioFiles]). A media-playback foreground service, so it goes on with the screen locked; its notification has STOP.
 * It only plays what the engine's [Effect.Audio] says; when a sound ends by itself (a file without LOOP, white noise
 * after [NOISE_MAX_MS], a file that cannot be read) the engine is told ([Vigil.stopAudio]) and sends the STOP.
 */
class AudioService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    /** The generated sound playing now (white noise or the metronome), or null. */
    private var stream: ToneStream? = null

    /** The file playing now, or null. */
    private var player: MediaPlayer? = null

    private val noiseLimit = Runnable { ended("white noise stops after 8 hours") }

    /** Keeps the CPU running while a sound plays with the screen locked (VIGIL's own thread writes the generated ones). */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pendingStarts.decrementAndGet() <= 0) pendingStarts.set(0)
        val sound = intent?.getStringExtra(EXTRA_SOUND)?.let { Effect.Audio.valid(it) }
        val bpm = intent?.getIntExtra(EXTRA_BPM, Effect.Audio.DEFAULT_BPM) ?: Effect.Audio.DEFAULT_BPM
        val loop = intent?.getBooleanExtra(EXTRA_LOOP, false) ?: false
        val entered = try {
            startForeground(Notifications.ID_AUDIO, Notifications.audio(this, title(sound, bpm)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Not allowed to play now: ${e.message}")
            false
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not enter the foreground", e)
            false
        }
        if (!entered || sound == null) {
            // Only this start's: a newer one on its way plays (or stops) after it.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val now = _playing.value
        if (now != null && now.sound == sound) {
            // The same sound again: only its speed or LOOP changes (the metronome's + and -).
            stream?.bpm = bpm
            player?.isLooping = loop
        } else {
            release()
            when {
                sound == Effect.Audio.WHITE_NOISE -> stream = ToneStream(noise = true, bpm = bpm) { failed() }.also { it.start() }
                sound == Effect.Audio.METRONOME -> stream = ToneStream(noise = false, bpm = bpm) { failed() }.also { it.start() }
                else -> playFile(Effect.Audio.fileId(sound) ?: "", loop)
            }
        }
        handler.removeCallbacks(noiseLimit)
        if (sound == Effect.Audio.WHITE_NOISE) handler.postDelayed(noiseLimit, NOISE_MAX_MS)
        holdAwake()
        _playing.value = Playing(sound, if (sound == Effect.Audio.METRONOME) bpm else null, loop)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(noiseLimit)
        release()
        _playing.value = null
        super.onDestroy()
    }

    private fun release() {
        stream?.stop()
        stream = null
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    /** A partial wake lock for as long as a sound may play (released with the sound), so it goes on with the screen off. */
    private fun holdAwake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = try {
            getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vigil:audio")?.also {
                it.setReferenceCounted(false)
                it.acquire(NOISE_MAX_MS + WAKE_EXTRA_MS)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "No wake lock for the audio: ${e.message}")
            null
        }
    }

    /** A file from [AudioFiles], again from the start when it ends with [loop]; otherwise its end is the end. */
    private fun playFile(id: String, loop: Boolean) {
        val file = AudioFiles.file(this, id)
        if (file == null) {
            ended("the file is gone")
            return
        }
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(file.absolutePath)
            p.isLooping = loop
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { if (!it.isLooping) ended("the file ended") }
            p.setOnErrorListener { _, what, extra ->
                ended("the file could not be played ($what/$extra)")
                true
            }
            player = p
            p.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Could not play $id", e)
            ended("the file could not be played")
        }
    }

    /** A generated sound could not open the audio output. */
    private fun failed() {
        handler.post { ended("the audio output could not be opened") }
    }

    /** The sound is over by itself: the engine journals it and sends the STOP. */
    private fun ended(why: String) {
        Log.i(TAG, "Audio ended: $why")
        try {
            Vigil.stopAudio("ended")
        } catch (e: Exception) {
            Log.e(TAG, "Could not tell the engine", e)
        }
        // A start on its way decides instead (stopping now, before it is in the foreground, would crash VIGIL).
        if (pendingStarts.get() == 0) stopSelf()
    }

    private fun title(sound: String?, bpm: Int): String = when {
        sound == Effect.Audio.WHITE_NOISE -> "WHITE NOISE"
        sound == Effect.Audio.METRONOME -> "METRONOME · $bpm BPM"
        else -> Effect.Audio.fileId(sound)?.let { AudioFiles.name(this, it).uppercase() } ?: "AUDIO"
    }

    /** What plays now, as the service has it. */
    data class Playing(val sound: String, val bpm: Int?, val loop: Boolean)

    companion object {
        private const val TAG = "VigilAudio"
        private const val EXTRA_SOUND = "sound"
        private const val EXTRA_BPM = "bpm"
        private const val EXTRA_LOOP = "loop"

        /** White noise stops by itself after this long (it is for falling asleep). */
        const val NOISE_MAX_MS = 8 * 60 * 60_000L

        /** The wake lock outlasts the longest white noise by this much (each PLAY that changes the sound renews it). */
        private const val WAKE_EXTRA_MS = 10 * 60_000L

        private val _playing = MutableStateFlow<Playing?>(null)

        /** What plays now, or null. */
        val playing: StateFlow<Playing?> = _playing.asStateFlow()

        /**
         * Starts sent to the service that it has not received yet. Android kills the app when such a service is stopped
         * before it entered the foreground, so meanwhile a STOP goes to the service as a start without a sound.
         */
        private val pendingStarts = AtomicInteger(0)

        /** The sound of the newest PLAY the service has not received yet. */
        @Volatile
        private var pendingSound: String? = null

        /** Plays [sound] (replacing whatever plays): the engine's [Effect.Audio] PLAY. */
        fun play(context: Context, sound: String, bpm: Int?, loop: Boolean) {
            val intent = Intent(context, AudioService::class.java)
                .putExtra(EXTRA_SOUND, sound)
                .putExtra(EXTRA_BPM, bpm ?: Effect.Audio.DEFAULT_BPM)
                .putExtra(EXTRA_LOOP, loop)
            pendingSound = sound
            send(context, intent)
        }

        /** Stops VIGIL's sound ([only]: just that sound, when it is the one playing): the engine's STOP. */
        fun stop(context: Context, only: String? = null) {
            val starting = pendingStarts.get() > 0
            val now = (if (starting) pendingSound else null) ?: _playing.value?.sound
            if (only != null && now != null && now != only) return
            // A start still on its way: a start without a sound follows it, which enters the foreground and stops.
            if (starting && send(context, Intent(context, AudioService::class.java))) {
                pendingSound = null
                return
            }
            runCatching { context.stopService(Intent(context, AudioService::class.java)) }
        }

        /** Starts the service with [intent]; false when Android did not allow it. */
        private fun send(context: Context, intent: Intent): Boolean {
            pendingStarts.incrementAndGet()
            val why = try {
                context.startForegroundService(intent)
                return true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                e.message
            } catch (e: IllegalStateException) {
                e.message
            } catch (e: SecurityException) {
                e.message
            }
            pendingStarts.decrementAndGet()
            Log.w(TAG, "Could not start the audio: $why")
            return false
        }
    }
}

/**
 * A generated sound streamed to the media stream on its own thread: soft white noise (white noise through a gentle
 * low-pass, fading in), or the metronome's click ([bpm] beats a minute, read at every beat). [onFailed] runs when the
 * audio output cannot be opened.
 */
private class ToneStream(private val noise: Boolean, @Volatile var bpm: Int, private val onFailed: () -> Unit) {
    @Volatile
    private var running = false

    fun start() {
        running = true
        Thread({ run() }, if (noise) "vigil-white-noise" else "vigil-metronome").start()
    }

    fun stop() {
        running = false
    }

    private fun run() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val track = try {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes(min))
                .build()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not open the audio track", e)
            running = false
            onFailed()
            return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "The audio track did not initialise")
            runCatching { track.release() }
            running = false
            onFailed()
            return
        }
        try {
            track.play()
            if (noise) noise(track) else clicks(track)
            // The loops end on STOP, or when the output refuses the sound (a write error): that one is reported.
            if (running) {
                Log.e(TAG, "The audio output stopped taking the sound")
                running = false
                onFailed()
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "The sound stopped", e)
            // Not a STOP: the engine still thinks it plays, so it is told (the AUDIO screen then shows NOTHING PLAYS).
            if (running) {
                running = false
                onFailed()
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun noise(track: AudioTrack) {
        val buffer = ShortArray(CHUNK)
        val random = Random()
        var level = 0.0
        val started = SystemClock.elapsedRealtime()
        track.setVolume(0f)
        while (running) {
            for (i in buffer.indices) {
                // White noise through a gentle one-pole low-pass (about 1.5 kHz): soft, but still in the range a phone
                // speaker plays.
                level += NOISE_SMOOTHING * ((random.nextDouble() * 2 - 1) - level)
                buffer[i] = (level * NOISE_AMPLITUDE).coerceIn(-32767.0, 32767.0).toInt().toShort()
            }
            track.setVolume(min(1f, (SystemClock.elapsedRealtime() - started) / FADE_IN_MS.toFloat()) * NOISE_VOLUME)
            if (track.write(buffer, 0, buffer.size) < 0) break
        }
    }

    /** One beat at a time: a short, quickly fading tick, then silence until the next beat. */
    private fun clicks(track: AudioTrack) {
        val click = ShortArray(RATE * CLICK_MS / 1000) { i ->
            val t = i.toDouble() / RATE
            (sin(2 * PI * CLICK_HZ * t) * exp(-t * CLICK_DECAY) * CLICK_AMPLITUDE).toInt().toShort()
        }
        val silence = ShortArray(CHUNK)
        while (running) {
            val beat = RATE * 60 / bpm.coerceIn(Effect.Audio.MIN_BPM, Effect.Audio.MAX_BPM)
            if (track.write(click, 0, click.size) < 0) break
            var left = beat - click.size
            while (left > 0 && running) {
                val n = min(left, silence.size)
                if (track.write(silence, 0, n) < 0) return
                left -= n
            }
        }
    }

    private companion object {
        const val TAG = "VigilAudio"
        const val RATE = 22_050
        const val CHUNK = 2_048

        /** Half a second of 16-bit mono sound, in bytes (always a whole number of 2-byte frames). */
        const val HALF_SECOND_BYTES = RATE * 2 / 2

        /**
         * The stream's buffer: at least the system's minimum ([min], an error code when negative) and half a second,
         * rounded up to whole 16-bit frames. AudioTrack refuses a buffer that is not a whole number of frames.
         */
        fun bufferBytes(min: Int): Int {
            val bytes = max(min, HALF_SECOND_BYTES)
            return bytes + bytes % 2
        }

        const val NOISE_SMOOTHING = 0.35
        const val NOISE_AMPLITUDE = 30_000.0
        const val NOISE_VOLUME = 0.8f
        const val FADE_IN_MS = 4_000L
        const val CLICK_MS = 30
        const val CLICK_HZ = 1_600.0
        const val CLICK_DECAY = 160.0
        const val CLICK_AMPLITUDE = 22_000.0
    }
}
