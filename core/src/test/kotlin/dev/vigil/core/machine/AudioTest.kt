package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design M8: VIGIL's audio (white noise, the metronome, the user's files), one sound at a time. */
class AudioTest {
    private val play = Effect.Audio.PLAY
    private val stop = Effect.Audio.STOP

    @Test
    fun playAndStopAreJournaledWithTheirMinutes() {
        val sim = Sim()
        sim.at("2026-10-19T21:00", Input.PlayAudio(Effect.Audio.WHITE_NOISE))
        assertEquals(listOf(Effect.Audio(play, Effect.Audio.WHITE_NOISE)), sim.lastEffects<Effect.Audio>())
        assertEquals(Effect.Audio.WHITE_NOISE, sim.state.audio!!.sound)
        assertEquals(Effect.Audio.WHITE_NOISE, Machine.status(sim.state, sim.env).audio!!.sound)
        val start = sim.lastOf(EntryType.AUDIO_START)!!
        assertEquals("audio", start.reason)
        assertEquals("white_noise", start.detail)
        sim.at("2026-10-19T21:25", Input.StopAudio())
        assertEquals(listOf(Effect.Audio(stop)), sim.lastEffects<Effect.Audio>())
        assertNull(sim.state.audio)
        val end = sim.lastOf(EntryType.AUDIO_STOP)!!
        assertEquals("stop", end.reason)
        assertEquals(25, end.minutes)
        // A file that ended by itself.
        sim.step(Input.PlayAudio("file:a1b2c3", name = "Rain.mp3"))
        assertEquals(listOf(Effect.Audio(play, "file:a1b2c3")), sim.lastEffects<Effect.Audio>())
        assertEquals("Rain.mp3", sim.lastOf(EntryType.AUDIO_START)!!.detail)
        sim.after(3, Input.StopAudio("ended"))
        assertEquals("ended", sim.lastOf(EntryType.AUDIO_STOP)!!.reason)
        assertEquals("Rain.mp3", sim.lastOf(EntryType.AUDIO_STOP)!!.detail)
    }

    @Test
    fun theMetronomeKeepsItsRangeAndASecondSoundReplacesTheFirst() {
        val sim = Sim()
        sim.step(Input.PlayAudio(Effect.Audio.METRONOME, bpm = 300))
        assertEquals(listOf(Effect.Audio(play, Effect.Audio.METRONOME, 208)), sim.lastEffects<Effect.Audio>())
        assertEquals("metronome 208 bpm", sim.lastOf(EntryType.AUDIO_START)!!.detail)
        // A new speed for the playing metronome: no new journal line.
        sim.step(Input.PlayAudio(Effect.Audio.METRONOME, bpm = 10))
        assertEquals(listOf(Effect.Audio(play, Effect.Audio.METRONOME, 40)), sim.lastEffects<Effect.Audio>())
        assertEquals(40, sim.state.audio!!.bpm)
        assertEquals(1, sim.of(EntryType.AUDIO_START).size)
        sim.step(Input.PlayAudio(Effect.Audio.METRONOME))
        assertEquals(Effect.Audio.DEFAULT_BPM, sim.state.audio!!.bpm)
        // A file replaces it; looping only for files.
        sim.after(2, Input.PlayAudio("file:x-1", loop = true))
        assertEquals("replaced", sim.lastOf(EntryType.AUDIO_STOP)!!.reason)
        assertEquals(listOf(Effect.Audio(play, "file:x-1", null, true)), sim.lastEffects<Effect.Audio>())
        sim.step(Input.PlayAudio(Effect.Audio.WHITE_NOISE, loop = true))
        assertEquals(false, sim.state.audio!!.loop)
    }

    @Test
    fun onlyVigilsOwnSoundsPlay() {
        val sim = Sim()
        for (bad in listOf("spotify", "file:", "file:../../etc", "file:a b", "https://x.y/z.mp3")) {
            sim.step(Input.PlayAudio(bad))
            assertTrue(bad, sim.lastEffects<Effect.Audio>().isEmpty())
        }
        assertNull(sim.state.audio)
        assertTrue(sim.of(EntryType.AUDIO_START).isEmpty())
    }

    @Test
    fun protocolsStopTheSound() {
        // The wake alarm stops every VIGIL sound when it rings.
        val sim = Sim()
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        sim.step(Input.PlayAudio(Effect.Audio.WHITE_NOISE))
        sim.at("2026-10-20T07:00")
        assertTrue(sim.lastEffects<Effect.Audio>().contains(Effect.Audio(stop)))
        assertEquals("wake_alarm", sim.lastOf(EntryType.AUDIO_STOP)!!.reason)
        assertNull(sim.state.audio)
        // The attention reset stops every sound when it starts.
        val attention = Sim(file = M7_FILE)
        attention.at("2026-10-19T12:00", Input.PlayAudio("file:abc"))
        attention.step(Input.Manual("attention"))
        assertEquals("attention", attention.lastOf(EntryType.AUDIO_STOP)!!.reason)
        assertNull(attention.state.audio)
        // The night lock's end stops only white noise: a metronome goes on.
        val night = Sim()
        night.at("2026-10-19T23:30", Input.GoingToSleep())
        night.at("2026-10-20T07:00")
        night.after(1, Input.PlayAudio(Effect.Audio.METRONOME, bpm = 60))
        night.after(1, Input.WakeAlarm())
        assertEquals("wake", night.lastOf(EntryType.NIGHT_LOCK)!!.reason)
        assertTrue(night.lastEffects<Effect.Audio>().contains(Effect.Audio(stop, Effect.Audio.WHITE_NOISE)))
        assertEquals(Effect.Audio.METRONOME, night.state.audio?.sound)
    }

    @Test
    fun theSoundSurvivesEncodeDecode() {
        val sim = Sim()
        sim.step(Input.PlayAudio(Effect.Audio.METRONOME, bpm = 90))
        assertEquals(sim.state, MachineState.decode(sim.state.encode()))
    }
}
