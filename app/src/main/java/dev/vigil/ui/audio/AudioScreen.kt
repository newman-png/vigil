package dev.vigil.ui.audio

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.audio.AudioFiles
import dev.vigil.core.machine.AudioState
import dev.vigil.core.machine.Effect
import dev.vigil.engine.Vigil
import dev.vigil.ui.components.Label
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.ScreenTitle
import dev.vigil.ui.components.TextInput
import dev.vigil.ui.components.Toggle
import dev.vigil.ui.rememberNow
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * AUDIO (design M8): VIGIL plays only its own sounds, one at a time, and they keep playing with the screen locked (STOP
 * on the notification). WHITE NOISE and the METRONOME (40-208 beats a minute, with - and +, or TAP to set it) are made
 * in code; MY FILES are audio files you add (+ ADD copies them into VIGIL), each with PLAY, RENAME and DELETE, and LOOP
 * for all of them. Every PLAY and STOP goes through the engine, which journals it.
 */
@Composable
fun AudioScreen() {
    val context = LocalContext.current
    val state by Vigil.state.collectAsState()
    val now = rememberNow(15_000L)
    val audio = state.audio
    val scope = rememberCoroutineScope()
    val settings = Graph.settings
    var bpm by remember { mutableIntStateOf(settings.metronomeBpm.coerceIn(Effect.Audio.MIN_BPM, Effect.Audio.MAX_BPM)) }
    var loop by remember { mutableStateOf(settings.audioLoop) }
    var taps by remember { mutableStateOf(listOf<Long>()) }
    // The file list, read again after every change.
    var version by remember { mutableIntStateOf(0) }
    val files = remember(version) { AudioFiles.list(context) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var deleteArmed by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        adding = true
        scope.launch {
            val added = withContext(Dispatchers.IO) { AudioFiles.add(context, uri) }
            adding = false
            version++
            Toast.makeText(context, if (added != null) "Added: ${added.name}" else "Could not read that file.", Toast.LENGTH_SHORT).show()
        }
    }

    /** A new speed: kept, and the playing metronome takes it at once. */
    fun setBpm(value: Int) {
        bpm = value.coerceIn(Effect.Audio.MIN_BPM, Effect.Audio.MAX_BPM)
        settings.metronomeBpm = bpm
        if (audio?.sound == Effect.Audio.METRONOME) Vigil.playAudio(Effect.Audio.METRONOME, bpm)
    }

    Column(Modifier.fillMaxWidth()) {
        ScreenTitle(
            "AUDIO",
            "Only VIGIL's own sounds and your files, one at a time. They keep playing with the screen locked; STOP is on " +
                "the notification. The wake alarm and the attention reset stop them.",
        )
        PixelText(nowPlaying(audio, Instant.ofEpochMilli(now)), Type.Label, color = if (audio != null) Palette.White else Palette.Dim)
        if (audio != null) {
            Spacer(Modifier.height(8.dp))
            PixelButton("STOP", { Vigil.stopAudio() }, Modifier.fillMaxWidth())
        }

        Label("WHITE NOISE")
        PixelText("Soft noise for falling asleep, made in code. It stops after 8 hours at the latest.", Type.Small)
        Spacer(Modifier.height(8.dp))
        PlayButton(audio?.sound == Effect.Audio.WHITE_NOISE) { Vigil.playAudio(Effect.Audio.WHITE_NOISE) }

        Label("METRONOME")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PixelButton("-", { setBpm(bpm - 1) })
            PixelText("$bpm BPM", Type.Item, Modifier.weight(1f).padding(horizontal = 8.dp), textAlign = TextAlign.Center)
            PixelButton("+", { setBpm(bpm + 1) })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            PixelButton("-10", { setBpm(bpm - 10) }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PixelButton(
                "TAP",
                {
                    // Tap the beat a few times: the speed is the average of the last taps (a pause starts over).
                    val t = SystemClock.elapsedRealtime()
                    val recent = (taps + t).filter { t - it < TAP_SPAN_MS }.takeLast(TAP_COUNT)
                    taps = recent
                    if (recent.size >= 2) setBpm(Math.round(60_000.0 * (recent.size - 1) / (recent.last() - recent.first())).toInt())
                },
                Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            PixelButton("+10", { setBpm(bpm + 10) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        PlayButton(audio?.sound == Effect.Audio.METRONOME) { Vigil.playAudio(Effect.Audio.METRONOME, bpm) }

        Label("MY FILES")
        PixelText("Audio files you add are copied into VIGIL, so they play even if the original goes.", Type.Small)
        Spacer(Modifier.height(4.dp))
        Toggle("LOOP", loop) {
            loop = it
            settings.audioLoop = it
            val playing = audio?.sound
            if (playing != null && Effect.Audio.fileId(playing) != null) Vigil.playAudio(playing, loop = it, name = audio?.name)
        }
        if (files.isEmpty()) PixelText("No files yet.", Type.Small)
        for (f in files) {
            val sound = Effect.Audio.FILE_PREFIX + f.id
            val playing = audio?.sound == sound
            Spacer(Modifier.height(8.dp))
            PixelText(f.name.uppercase(), Type.Body, color = if (playing) Palette.White else Palette.Dim)
            PixelText("${(f.bytes + 1023) / 1024} KB" + if (playing) " · PLAYING" else "", Type.Label)
            Spacer(Modifier.height(4.dp))
            if (renaming == f.id) {
                TextInput(newName, { newName = it.replace('\n', ' ') }, placeholder = "Name")
                Row(Modifier.fillMaxWidth()) {
                    PixelButton(
                        "SAVE",
                        {
                            AudioFiles.rename(context, f.id, newName)
                            renaming = null
                            version++
                        },
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    PixelButton("CANCEL", { renaming = null }, Modifier.weight(1f), color = Palette.Dim)
                }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    PixelButton(
                        if (playing) "STOP" else "PLAY",
                        { if (playing) Vigil.stopAudio() else Vigil.playAudio(sound, loop = loop, name = f.name) },
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    PixelButton(
                        "RENAME",
                        {
                            newName = f.name
                            renaming = f.id
                        },
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    PixelButton(
                        if (deleteArmed == f.id) "SURE?" else "DELETE",
                        {
                            if (deleteArmed != f.id) {
                                deleteArmed = f.id
                            } else {
                                if (playing) Vigil.stopAudio()
                                AudioFiles.delete(context, f.id)
                                deleteArmed = null
                                version++
                            }
                        },
                        Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PixelButton(if (adding) "ADDING..." else "+ ADD", { if (!adding) picker.launch(arrayOf("audio/*")) }, Modifier.fillMaxWidth(), enabled = !adding)
        Spacer(Modifier.height(24.dp))
    }
}

/** PLAY, or STOP while this sound plays. */
@Composable
private fun PlayButton(playing: Boolean, onPlay: () -> Unit) {
    PixelButton(if (playing) "STOP" else "PLAY", { if (playing) Vigil.stopAudio() else onPlay() }, Modifier.fillMaxWidth())
}

/** PLAYING: METRONOME 100 BPM · 12 MIN, or NOTHING PLAYS. */
private fun nowPlaying(audio: AudioState?, now: Instant): String {
    if (audio == null) return "NOTHING PLAYS"
    val what = when {
        audio.sound == Effect.Audio.WHITE_NOISE -> "WHITE NOISE"
        audio.sound == Effect.Audio.METRONOME -> "METRONOME ${audio.bpm ?: Effect.Audio.DEFAULT_BPM} BPM"
        else -> (audio.name ?: "A FILE").uppercase() + if (audio.loop) " (LOOP)" else ""
    }
    val minutes = Duration.between(audio.since, now).toMinutes().coerceAtLeast(0)
    return "PLAYING: $what · $minutes MIN"
}

/** TAP sets the speed from at most this many taps, within this long. */
private const val TAP_COUNT = 6
private const val TAP_SPAN_MS = 6_000L
