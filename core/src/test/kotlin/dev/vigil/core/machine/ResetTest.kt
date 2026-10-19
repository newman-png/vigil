package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.Lock
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.RunPhase
import dev.vigil.core.run.StageMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol 13: the reset (design M5), its offer after misses in a row, and re-entering the day. */
class ResetTest {
    private val reset = BundledProtocols.file.find("reset")!!
    private val alpha = testProtocol("alpha")

    /** Taps through the full reset from its first step, waiting out each step's minutes; [name] for "name it". */
    private fun Sim.doReset(name: String = "") {
        val id = runId("reset")
        after(1) // the stop timer ends by itself
        step(Input.StepDone(id, 1, name))
        after(3, Input.StepDone(id, 2))
        after(5, Input.StepDone(id, 3))
        after(5, Input.StepDone(id, 4))
    }

    private fun Sim.reentryLine(): String? = Machine.stepView(state, runId("reset"), env)?.reentry

    private fun Sim.reenter() = step(Input.StepDone(runId("reset"), 5))

    private fun Sim.bail(id: String) {
        step(Input.Manual(id))
        step(Input.BailOut(runId(id)))
    }

    @Test
    fun theHomeButtonStartsAFullReset() {
        val sim = Sim("2026-10-19T10:00", file = fileWith(alpha))
        assertEquals("reset", Machine.status(sim.state, sim.env).resetProtocol)
        sim.step(Input.Manual("reset"))
        val r = sim.run("reset")!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(LockType.SCREEN, r.lock)
        assertEquals(1, r.stage)
        assertEquals(Intensity.FULL, Machine.intensity(r, sim.file))
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        assertEquals("STOP: PHONE DOWN, DRINK A GLASS OF WATER.", Machine.stepView(sim.state, r.id, sim.env)!!.step.text.uppercase())
        // The stop timer cannot be tapped through; "name it" may stay empty.
        sim.step(Input.StepDone(r.id, 0))
        assertEquals(0, sim.run("reset")!!.step)
        sim.after(1)
        assertEquals(1, sim.run("reset")!!.step)
        sim.step(Input.StepDone(r.id, 1, ""))
        assertEquals(2, sim.run("reset")!!.step)
        // Body 3 minutes: locked until then.
        sim.after(2, Input.StepDone(r.id, 2))
        assertEquals(2, sim.run("reset")!!.step)
        sim.after(1, Input.StepDone(r.id, 2))
        sim.after(5, Input.StepDone(r.id, 3))
        sim.after(5, Input.StepDone(r.id, 4))
        assertEquals("NEXT: WIND-DOWN AT 23:30.", sim.reentryLine())
        sim.reenter()
        assertNull(sim.run("reset"))
        val re = sim.lastOf(EntryType.REENTRY)!!
        assertEquals("next", re.reason)
        assertEquals("NEXT: WIND-DOWN AT 23:30.", re.detail)
        assertNull(re.text)
        assertEquals("reset", sim.lastOf(EntryType.COMPLETED)!!.protocol)
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
        assertEquals(StageMemory(1), sim.state.stages["reset"])
    }

    @Test
    fun reenterDropsWhatWasMissedAndStartsTheNextProtocol() {
        val leftover = testProtocol("leftover", lock = Lock(LockType.SCREEN, 5, null))
        val waiter = testProtocol("waiter")
        val timed = testProtocol("timed", trigger = "time:11:00")
        val sim = Sim("2026-10-19T09:00", file = fileWith(alpha, leftover, waiter, timed))
        sim.step(Input.Manual("leftover"))
        sim.at("2026-10-19T09:06")
        assertEquals(RunPhase.OPEN, sim.run("leftover")!!.phase)
        sim.bail("alpha")
        assertEquals(1, sim.state.missesInARow)
        val stagesBefore = sim.state.stages
        sim.at("2026-10-19T10:20", Input.Manual("reset"))
        sim.after(2, Input.Manual("waiter"))
        assertEquals(RunPhase.QUEUED, sim.run("waiter")!!.phase)
        sim.doReset("Slept through the alarm")
        assertEquals("TIMED STARTS NOW.", sim.reentryLine())
        sim.reenter()
        // Dropped without an outcome (no stage change), the count cleared, the next protocol started now.
        assertEquals(setOf("leftover", "waiter"), sim.of(EntryType.DROPPED).filter { it.reason == "reset" }.mapNotNull { it.protocol }.toSet())
        assertNull(sim.run("leftover"))
        assertNull(sim.run("waiter"))
        assertEquals(0, sim.state.missesInARow)
        assertEquals(stagesBefore - "reset", sim.state.stages - "reset")
        val timedRun = sim.run("timed")!!
        assertEquals(RunPhase.ACTIVE, timedRun.phase)
        assertEquals("reenter", timedRun.reason)
        assertEquals(timedRun, Machine.activeRun(sim.state, sim.env))
        val re = sim.lastOf(EntryType.REENTRY)!!
        assertEquals("started", re.reason)
        assertEquals("Slept through the alarm", re.text)
        // It does not start a second time at 11:00.
        sim.at("2026-10-19T11:00")
        assertEquals(1, sim.of(EntryType.FIRED).count { it.protocol == "timed" })
    }

    @Test
    fun anOpenPrayerComesFirst() {
        val sim = Sim("2026-10-19T12:00")
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        sim.at("2026-10-19T12:50")
        assertEquals(RunPhase.OPEN, sim.run("prayer-dhuhr")!!.phase)
        sim.at("2026-10-19T13:00", Input.Manual("reset"))
        sim.doReset()
        assertEquals("DHUHR COMES FIRST: PRAY IT NOW.", sim.reentryLine())
        sim.reenter()
        // The prayer's takeover starts as soon as the reset is over, with a fresh lock.
        val dhuhr = sim.run("prayer-dhuhr")!!
        assertEquals(RunPhase.ACTIVE, dhuhr.phase)
        assertEquals(sim.now.plusSeconds(20 * 60), dhuhr.lockEndsAt)
        assertEquals(dhuhr, Machine.activeRun(sim.state, sim.env))
        assertEquals("prayer", sim.lastOf(EntryType.REENTRY)!!.reason)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == dhuhr.id })
    }

    @Test
    fun aFarOrPrayerStartIsOnlyNamed() {
        val late = testProtocol("late", trigger = "time:16:00")
        val sim = Sim("2026-10-19T08:00", file = fileWith(late, base = PRAYERS_ONLY + reset))
        sim.at("2026-10-19T10:00", Input.Manual("reset"))
        sim.doReset()
        assertEquals("NEXT: LATE AT 16:00.", sim.reentryLine())
        sim.reenter()
        assertNull(sim.run("late"))
        // A prayer's adhan within the hour is not started early: it is named with its estimate.
        val prayer = Sim("2026-10-19T08:00", file = PRAYERS_ONLY + reset)
        prayer.state = prayer.state.copy(prayers = PrayerMemory(lastAdhan = mapOf(Prayer.DHUHR to rome("2026-10-18T10:40"))))
        prayer.at("2026-10-19T10:00", Input.Manual("reset"))
        prayer.doReset()
        assertEquals("NEXT: DHUHR AT ~10:40.", prayer.reentryLine())
        prayer.reenter()
        assertNull(prayer.run("prayer-dhuhr"))
        // Nothing planned at all.
        val empty = Sim("2026-10-19T08:00", file = PRAYERS_ONLY + reset)
        empty.at("2026-10-19T21:00", Input.Manual("reset"))
        empty.doReset()
        assertEquals("NOTHING ELSE IS PLANNED TODAY.", empty.reentryLine())
    }

    @Test
    fun twoMissesInARowOfferTheReset() {
        val sim = Sim("2026-10-19T09:00", file = fileWith(alpha))
        sim.bail("alpha")
        assertNull(sim.state.offer)
        sim.bail("alpha")
        val offer = sim.state.offer!!
        assertEquals("reset", offer.protocol)
        assertEquals("misses:2", offer.reason)
        assertEquals(offer, Machine.status(sim.state, sim.env).offer)
        val card = sim.lastEffects<Effect.Notify>().single { it.kind == NotifyKind.OFFER }
        assertEquals("RESET?", card.title)
        assertFalse(card.silent)
        assertNull(sim.run("reset"))
        // NOT NOW costs nothing, and the next offer needs two more misses.
        sim.step(Input.AnswerOffer(start = false))
        assertNull(sim.state.offer)
        assertEquals("not_now", sim.lastOf(EntryType.OFFER)!!.reason)
        assertTrue(sim.lastEffects<Effect.CancelNotify>().any { it.id == Txn.OFFER_NOTICE })
        assertNull(sim.run("reset"))
        assertEquals(2, sim.of(EntryType.PENALTY).size)
        sim.bail("alpha")
        assertNull(sim.state.offer)
        // QUIET: the card is silent.
        sim.step(Input.Toggle(ToggleName.QUIET, true))
        sim.bail("alpha")
        assertNotNull(sim.state.offer)
        assertTrue(sim.lastEffects<Effect.Notify>().single { it.kind == NotifyKind.OFFER }.silent)
        // START starts it now.
        sim.step(Input.AnswerOffer(start = true))
        assertEquals("offer", sim.run("reset")!!.reason)
        assertEquals("started", sim.lastOf(EntryType.OFFER)!!.reason)
    }

    @Test
    fun theOfferEndsWithTheDayTheKillSwitchOrTheButton() {
        val sim = Sim("2026-10-19T09:00", file = fileWith(alpha, base = PRAYERS_ONLY + reset))
        sim.bail("alpha")
        sim.bail("alpha")
        assertNotNull(sim.state.offer)
        // The RESET button answers it.
        sim.step(Input.Manual("reset"))
        assertNull(sim.state.offer)
        assertEquals("started", sim.lastOf(EntryType.OFFER)!!.reason)
        sim.step(Input.BailOut(sim.runId("reset")))
        sim.bail("alpha")
        assertNotNull(sim.state.offer)
        sim.at("2026-10-19T23:00", Input.GoingToSleep())
        assertNull(sim.state.offer)
        assertEquals("expired", sim.lastOf(EntryType.OFFER)!!.reason)
        sim.at("2026-10-20T09:00")
        sim.bail("alpha")
        sim.bail("alpha")
        sim.step(Input.Suspend(30))
        assertNull(sim.state.offer)
        assertEquals("VIGIL was suspended", sim.lastOf(EntryType.OFFER)!!.detail)
        // An answer with no offer changes nothing.
        sim.step(Input.AnswerOffer(start = true))
        assertNull(sim.run("reset"))
    }

    @Test
    fun theLadder() {
        val sim = Sim("2026-10-19T10:00", file = PRAYERS_ONLY + reset)
        sim.step(Input.Manual("reset"))
        val id = sim.runId("reset")
        sim.step(Input.NotOkay(id))
        assertEquals(Version.SHORT, sim.run("reset")!!.version)
        assertEquals(3, Machine.stepsFor(sim.run("reset")!!, sim.env).size)
        sim.step(Input.NotOkay(id))
        assertEquals(Version.MINIMAL, sim.run("reset")!!.version)
        sim.step(Input.NotOkay(id))
        assertEquals(Version.SINGLE, sim.run("reset")!!.version)
        assertNotNull(Machine.stepView(sim.state, id, sim.env)!!.reentry)
        sim.step(Input.StepDone(id, 0))
        assertEquals("nothing", sim.lastOf(EntryType.REENTRY)!!.reason)
        assertEquals("done", sim.lastOf(EntryType.COMPLETED)!!.reason)
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
    }

    @Test
    fun leavingEarlyIsANormalBailOut() {
        val sim = Sim("2026-10-19T10:00", file = PRAYERS_ONLY + reset)
        sim.step(Input.Manual("reset"))
        sim.after(1, Input.BailOut(sim.runId("reset")))
        assertNull(sim.run("reset"))
        assertEquals(120, sim.lastOf(EntryType.PENALTY)!!.minutes)
        assertEquals(1, sim.state.missesInARow)
        assertEquals(StageMemory(1), sim.state.stages["reset"])
        assertTrue(sim.of(EntryType.REENTRY).isEmpty())
    }

    @Test
    fun aTestResetChangesNothing() {
        val leftover = testProtocol("leftover", lock = Lock(LockType.SCREEN, 5, null))
        val sim = Sim("2026-10-19T09:00", file = fileWith(leftover, base = PRAYERS_ONLY + reset))
        sim.step(Input.Manual("leftover"))
        sim.at("2026-10-19T10:00", Input.Manual("reset", test = true))
        sim.doReset()
        assertTrue(sim.reentryLine()!!.startsWith("TEST"))
        sim.reenter()
        assertTrue(sim.of(EntryType.REENTRY).isEmpty())
        assertNotNull(sim.run("leftover"))
    }

    @Test
    fun afterMidnight() {
        val night = testProtocol("night", trigger = "time:01:00")
        val sim = Sim("2026-10-19T08:00", file = fileWith(night, base = PRAYERS_ONLY + reset))
        sim.at("2026-10-20T00:20", Input.Manual("reset"))
        assertEquals("2026-10-19", sim.state.day!!.key)
        sim.doReset()
        assertEquals("NIGHT STARTS NOW.", sim.reentryLine())
        sim.reenter()
        assertEquals("reenter", sim.run("night")!!.reason)
    }

    @Test
    fun theClockChange() {
        // 02:40 summer time on 25 October; 03:30 comes 1 h 50 min later (the clocks go back at 03:00): only named.
        val night = testProtocol("night", trigger = "time:03:30")
        val sim = Sim("2026-10-24T08:00", file = fileWith(night, base = PRAYERS_ONLY + reset))
        sim.at("2026-10-25T02:40", Input.Manual("reset"))
        sim.doReset()
        assertEquals("NEXT: NIGHT AT 03:30.", sim.reentryLine())
        sim.reenter()
        assertNull(sim.run("night"))
    }

    @Test
    fun aCallPausesItsTimers() {
        val sim = Sim("2026-10-19T10:00", file = PRAYERS_ONLY + reset)
        sim.step(Input.Manual("reset"))
        sim.afterSeconds(30, Input.CallActive(true))
        sim.at("2026-10-19T10:05", Input.CallActive(false))
        assertEquals(0, sim.run("reset")!!.step)
        sim.at("2026-10-19T10:06")
        assertEquals(1, sim.run("reset")!!.step)
    }

    @Test
    fun theKillSwitchDropsIt() {
        val sim = Sim("2026-10-19T10:00", file = PRAYERS_ONLY + reset)
        sim.step(Input.Manual("reset"))
        sim.step(Input.Suspend(60))
        assertNull(sim.run("reset"))
        assertEquals("kill_switch", sim.lastOf(EntryType.DROPPED)!!.reason)
        sim.step(Input.Manual("reset"))
        assertNull(sim.run("reset"))
    }

    @Test
    fun stateRoundTrip() {
        val sim = Sim("2026-10-19T09:00", file = fileWith(alpha))
        sim.bail("alpha")
        sim.bail("alpha")
        sim.step(Input.Manual("reset", test = true))
        val text = sim.state.encode()
        assertTrue(text.contains("\"offer\":{\"protocol\":\"reset\""))
        assertEquals(sim.state, MachineState.decode(text))
    }
}

private operator fun dev.vigil.core.protocol.ProtocolFile.plus(p: dev.vigil.core.protocol.Protocol) = copy(protocols = protocols + p)
