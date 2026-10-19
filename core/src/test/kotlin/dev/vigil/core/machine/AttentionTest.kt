package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.Lock
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolFile
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.RunPhase
import dev.vigil.core.run.StageMemory
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol 9: the attention reset (design M6), with the bundled `attention` protocol after the Dhuhr lock. */
class AttentionTest {
    private val full = M7_FILE

    /** The bundled file with the attention reset's `offset_min` set to [offset]. */
    private fun withOffset(offset: Int): ProtocolFile {
        val p = full.find("attention")!!
        return full.copy(protocols = full.protocols.map { if (it.id == "attention") p.copy(params = JsonObject(p.params + ("offset_min" to JsonPrimitive(offset)))) else it })
    }

    private fun Sim.attention() = run("attention")

    private fun Sim.view() = Machine.stepView(state, runId("attention"), env)!!

    /** Dhuhr at 12:30, prayed and confirmed at 12:38: its lock ends at 12:50. */
    private fun Sim.dhuhr(adhan: String = "2026-10-19T12:30") {
        at(adhan, Input.Adhan(Prayer.DHUHR))
        after(8, Input.Confirm(runId("prayer-dhuhr")))
    }

    /** Faces the wall and puts the phone face-down; the look starts. */
    private fun Sim.lookStarts() {
        step(Input.StepDone(runId("attention"), 0))
        step(Input.StepDone(runId("attention"), 1))
    }

    @Test
    fun rightAfterTheDhuhrLock() {
        val sim = Sim(file = full)
        sim.dhuhr()
        assertNull(sim.attention())
        sim.at("2026-10-19T12:50")
        assertNull(sim.run("prayer-dhuhr"))
        val deferred = sim.lastOf(EntryType.DEFERRED)!!
        assertEquals("after:prayer-dhuhr", deferred.reason)
        assertEquals("2026-10-19T12:50:00+02:00", deferred.until)
        val r = sim.attention()!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals("after:prayer-dhuhr", r.reason)
        assertEquals(LockType.SCREEN, r.lock)
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        assertEquals(Intensity.FULL, Machine.intensity(r, sim.file))
        assertEquals(3, r.lengthMin)
        assertEquals(3, sim.lastOf(EntryType.FIRED)!!.minutes)
        // VIGIL's own sounds stop: no sound while you look at one spot.
        assertTrue(sim.effectsOf<Effect.Audio>().any { it.action == Effect.Audio.STOP && it.sound == null })
        assertEquals(listOf(StepType.CHECK to null, StepType.CHECK to null, StepType.TIMER to 3), Machine.stepsFor(r, sim.env).map { it.type to it.min })
        sim.lookStarts()
        val v = sim.view()
        assertTrue(v.step.chime)
        assertFalse(v.canTap)
        assertEquals(rome("2026-10-19T12:53"), v.timerEndsAt)
        sim.at("2026-10-19T12:53")
        assertNull(sim.attention())
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
        assertEquals(AttentionMemory(1, "2026-10-19"), sim.state.attention)
        assertTrue(sim.state.day!!.deferred.isEmpty())
    }

    @Test
    fun anUnconfirmedLockEndAndALeaveAfterTheMinimumAlsoStartIt() {
        val unconfirmed = Sim(file = full)
        unconfirmed.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        unconfirmed.at("2026-10-19T12:50")
        assertEquals(RunPhase.OPEN, unconfirmed.run("prayer-dhuhr")!!.phase)
        assertEquals(RunPhase.ACTIVE, unconfirmed.attention()!!.phase)
        val released = Sim(file = full)
        released.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        released.at("2026-10-19T12:40", Input.BailOut(released.runId("prayer-dhuhr")))
        assertNotNull(released.attention())
        // Left before the minimum: a bail-out, and nothing follows it.
        val bailed = Sim(file = full)
        bailed.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        bailed.at("2026-10-19T12:33", Input.BailOut(bailed.runId("prayer-dhuhr")))
        bailed.at("2026-10-19T13:30")
        assertNull(bailed.attention())
        assertTrue(bailed.of(EntryType.DEFERRED).isEmpty())
    }

    @Test
    fun theRampCountsCompletedDays() {
        fun lengthAfter(days: Int): Int? {
            val sim = Sim(file = full)
            sim.state = sim.state.copy(attention = AttentionMemory(days, "2026-10-01"))
            sim.step(Input.Manual("attention"))
            return sim.attention()!!.lengthMin
        }
        assertEquals(listOf(3, 3, 4, 4, 5, 9, 10, 10), listOf(0, 1, 2, 3, 4, 13, 14, 40).map { lengthAfter(it) })
        // Twice the same day counts once; a step-down and a TEST never count.
        val sim = Sim(file = fileWith(full.find("attention")!!, base = PRAYERS_ONLY))
        repeat(2) {
            sim.step(Input.Manual("attention"))
            sim.lookStarts()
            sim.after(3)
        }
        assertEquals(AttentionMemory(1, "2026-10-19"), sim.state.attention)
        val short = Sim(file = full)
        short.step(Input.Manual("attention"))
        short.step(Input.NotOkay(short.runId("attention")))
        short.after(3)
        assertNull(short.attention())
        assertEquals(AttentionMemory(), short.state.attention)
        val test = Sim(file = full)
        test.step(Input.Manual("attention", test = true))
        test.lookStarts()
        test.after(3)
        assertEquals(AttentionMemory(), test.state.attention)
        // The next day (after the 06:00 fallback end) counts again.
        sim.at("2026-10-20T09:00", Input.Manual("attention"))
        assertEquals("2026-10-20", sim.state.day!!.key)
        assertEquals(3, sim.attention()!!.lengthMin)
        sim.lookStarts()
        sim.after(3)
        assertEquals(AttentionMemory(2, "2026-10-20"), sim.state.attention)
    }

    @Test
    fun shortIsThreeMinutesAndThereIsNoMinimal() {
        val sim = Sim(file = full)
        sim.state = sim.state.copy(attention = AttentionMemory(20, "2026-10-01"))
        sim.step(Input.Manual("attention"))
        assertEquals(10, sim.attention()!!.lengthMin)
        sim.step(Input.NotOkay(sim.runId("attention")))
        val r = sim.attention()!!
        assertEquals(Version.SHORT, r.version)
        assertEquals(listOf(StepType.TIMER to 3), Machine.stepsFor(r, sim.env).map { it.type to it.min })
        sim.step(Input.NotOkay(r.id))
        assertEquals(Version.SHORT, sim.attention()!!.version)
        assertEquals(1, sim.of(EntryType.STEPPED_DOWN).size)
        sim.after(3)
        assertNull(sim.attention())
    }

    @Test
    fun itWaitsForTheOffsetAndForAClass() {
        val classAt = MyTimes(classes = listOf(Slot(listOf("mon"), "12:55", "13:40", label = "Physics")))
        val sim = Sim(file = withOffset(10), times = classAt)
        sim.dhuhr()
        sim.at("2026-10-19T12:50")
        assertNull(sim.attention())
        assertEquals(rome("2026-10-19T13:00"), sim.state.day!!.deferred.single().due)
        assertEquals(rome("2026-10-19T13:00"), Machine.status(sim.state, sim.env).deferred.single().due)
        sim.at("2026-10-19T13:00")
        // A class is in progress: it waits until the class ends.
        assertNull(sim.attention())
        assertEquals(rome("2026-10-19T13:40"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T13:40")
        assertEquals(RunPhase.ACTIVE, sim.attention()!!.phase)
        assertEquals("after:prayer-dhuhr", sim.attention()!!.reason)
    }

    @Test
    fun itWaitsForAProtocolOnTheScreen() {
        val busy = testProtocol("busy", lock = Lock(LockType.SCREEN, 30, null))
        val sim = Sim(file = fileWith(busy, base = full))
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        sim.at("2026-10-19T12:45", Input.Manual("busy"))
        assertEquals(RunPhase.QUEUED, sim.run("busy")!!.phase)
        sim.at("2026-10-19T12:50")
        assertEquals("busy", Machine.activeRun(sim.state, sim.env)!!.protocol)
        assertNull(sim.attention())
        sim.at("2026-10-19T13:20")
        assertEquals("attention", Machine.activeRun(sim.state, sim.env)!!.protocol)
    }

    @Test
    fun skippedWhenItCannotStartWithinTwoHoursNotAMiss() {
        val longClass = MyTimes(classes = listOf(Slot(listOf("mon"), "12:55", "15:30", label = "Lab")))
        val sim = Sim(file = withOffset(10), times = longClass)
        sim.dhuhr()
        sim.at("2026-10-19T12:50")
        val misses = sim.state.missesInARow
        sim.at("2026-10-19T14:59")
        assertTrue(sim.state.day!!.deferred.isNotEmpty())
        sim.at("2026-10-19T15:00")
        assertNull(sim.attention())
        assertTrue(sim.state.day!!.deferred.isEmpty())
        val skipped = sim.lastOf(EntryType.SKIPPED)!!
        assertEquals("too_late", skipped.reason)
        assertEquals("attention", skipped.protocol)
        assertTrue(sim.of(EntryType.MISSED).none { it.protocol == "attention" })
        assertEquals(misses, sim.state.missesInARow)
        sim.at("2026-10-19T15:30")
        assertNull(sim.attention())
    }

    @Test
    fun fridayAfterTheJummahLock() {
        val sim = Sim("2026-10-23T08:00", file = full)
        sim.at("2026-10-23T13:20")
        val j = sim.state.runs.first { it.jummah != null }
        for (i in 0..3) sim.step(Input.StepDone(j.id, i))
        sim.at("2026-10-23T13:40", Input.Confirm(j.id))
        sim.at("2026-10-23T14:35")
        assertNull(sim.attention())
        // DONE after the prayer ends the Jummah lock: the attention reset follows it, as it follows Dhuhr.
        sim.at("2026-10-23T14:50", Input.StepDone(j.id, 5))
        assertTrue(sim.state.runs.none { it.jummah != null })
        assertEquals("after:jummah", sim.attention()!!.reason)
        assertNull(sim.run("prayer-dhuhr"))
    }

    @Test
    fun quietSilencesItACallPausesIt() {
        val sim = Sim(file = full)
        sim.step(Input.Toggle(ToggleName.QUIET, true))
        sim.step(Input.Manual("attention"))
        val r = sim.attention()!!
        assertTrue(Machine.isSilent(sim.state, r))
        sim.lookStarts()
        sim.after(1, Input.CallActive(true))
        sim.after(5, Input.CallActive(false))
        assertEquals(sim.now.plusSeconds(120), sim.view().timerEndsAt)
        sim.after(2)
        assertNull(sim.attention())
    }

    @Test
    fun theKillSwitchAndTheDayEndDropTheWait() {
        val sim = Sim(file = withOffset(10))
        sim.dhuhr()
        sim.at("2026-10-19T12:50")
        sim.at("2026-10-19T12:55", Input.Suspend(60))
        sim.at("2026-10-19T13:00")
        assertNull(sim.attention())
        assertEquals("suspended", sim.lastOf(EntryType.SKIPPED)!!.reason)
        assertTrue(sim.state.day!!.deferred.isEmpty())
        val night = Sim(file = withOffset(240))
        night.dhuhr()
        night.at("2026-10-19T12:50")
        night.at("2026-10-19T14:00", Input.GoingToSleep())
        val skipped = night.of(EntryType.SKIPPED).last { it.protocol == "attention" }
        assertEquals("day_end", skipped.reason)
        assertTrue(night.state.day!!.deferred.isEmpty())
    }

    @Test
    fun stagesAndBailOut() {
        val guided = Sim(file = full)
        guided.state = guided.state.copy(stages = guided.state.stages + ("attention" to StageMemory(2)))
        guided.step(Input.Manual("attention"))
        guided.lookStarts()
        assertTrue(guided.view().canTap)
        val reminder = Sim(file = full)
        reminder.state = reminder.state.copy(stages = reminder.state.stages + ("attention" to StageMemory(3)))
        reminder.dhuhr()
        reminder.at("2026-10-19T12:50")
        assertEquals(RunPhase.REMINDER, reminder.attention()!!.phase)
        val left = Sim(file = full)
        left.step(Input.Manual("attention"))
        left.step(Input.BailOut(left.runId("attention")))
        assertNull(left.attention())
        assertEquals(120, left.lastOf(EntryType.PENALTY)!!.minutes)
    }

    @Test
    fun onTheDayTheClocksGoBack() {
        // Sunday 25 October: the clocks went back at 03:00. Dhuhr at 12:20 (CET); 10 minutes of offset.
        val sim = Sim("2026-10-25T08:00", file = withOffset(10))
        sim.state = sim.state.copy(attention = AttentionMemory(2, "2026-10-24"))
        sim.dhuhr("2026-10-25T12:20")
        sim.at("2026-10-25T12:40")
        assertEquals("2026-10-25T12:50:00+01:00", sim.lastOf(EntryType.DEFERRED)!!.until)
        sim.at("2026-10-25T12:50")
        assertEquals(4, sim.attention()!!.lengthMin)
        sim.lookStarts()
        assertEquals(rome("2026-10-25T12:54"), sim.view().timerEndsAt)
        sim.at("2026-10-25T12:54")
        assertEquals(AttentionMemory(3, "2026-10-25"), sim.state.attention)
    }

    @Test
    fun aWaitingResetSurvivesEncodeDecode() {
        val sim = Sim(file = withOffset(30))
        sim.dhuhr()
        sim.at("2026-10-19T12:50")
        val text = sim.state.encode()
        assertTrue(text.contains("\"deferred\":[{"))
        val decoded = MachineState.decode(text)
        assertEquals(sim.state, decoded)
        val env = MachineEnv(rome("2026-10-19T13:20"), ROME, sim.file)
        val o = Machine.step(decoded, Input.Tick, env)
        assertNotNull(o.state.runs.firstOrNull { it.protocol == "attention" })
    }
}
