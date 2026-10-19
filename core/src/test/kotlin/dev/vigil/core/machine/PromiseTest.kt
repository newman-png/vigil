package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import dev.vigil.core.run.StageMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** Protocol 12: the 10-minute promise (design M5). */
class PromiseTest {
    private val line = "Call the dentist"

    private fun Sim.promise(): Run? = state.runs.firstOrNull { it.promise != null }

    private fun Sim.promised(reason: String) = of(EntryType.PROMISE).filter { it.reason == reason }

    @Test
    fun laterThenDueThenDone() {
        val sim = Sim("2026-10-19T10:00")
        assertEquals(listOf(5, 10, 15, 30), Machine.status(sim.state, sim.env).promiseDelays)
        sim.step(Input.Promise("  $line  \nand more", 10))
        var r = sim.promise()!!
        assertEquals(RunPhase.WAITING, r.phase)
        assertEquals(line, r.promise!!.text)
        assertEquals(rome("2026-10-19T10:10"), r.promise!!.dueAt)
        assertEquals(r, Machine.status(sim.state, sim.env).promise)
        assertEquals(rome("2026-10-19T10:10"), Machine.nextWake(sim.state, sim.env))
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertTrue(sim.decision() !is PoolDecision.Blocked || (sim.decision() as PoolDecision.Blocked).reason != BlockReason.RUN_LOCK)
        val promised = sim.promised("promised").single()
        assertEquals(line, promised.text)
        assertEquals(10, promised.minutes)
        val notice = sim.lastEffects<Effect.Notify>().single()
        assertEquals(NotifyKind.PROMISE, notice.kind)
        assertEquals("PROMISE AT 10:10", notice.title)
        assertEquals(line, notice.text)
        // Due: a full takeover with exactly the line, and the pool locked until DONE (the screen is not).
        sim.at("2026-10-19T10:10")
        r = sim.promise()!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertTrue(r.asking)
        assertEquals(LockType.POOL, r.lock)
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        assertFalse(Machine.reasserts(r))
        assertEquals(Intensity.FULL, Machine.intensity(r, sim.file))
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == r.id })
        assertEquals(BlockReason.RUN_LOCK, (sim.decision() as PoolDecision.Blocked).reason)
        assertEquals(5, Machine.snoozeMinutes(r, sim.file))
        assertEquals(1, sim.promised("due").size)
        // ON IT: the takeover closes, the pool lock stays.
        sim.after(1, Input.Dismiss(r.id))
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals(BlockReason.RUN_LOCK, (sim.decision() as PoolDecision.Blocked).reason)
        assertEquals(1, sim.promised("on_it").size)
        // DONE: kept.
        sim.after(4, Input.Confirm(r.id))
        assertNull(sim.promise())
        assertEquals(15, sim.promised("kept").single().minutes)
        assertEquals("promise", sim.lastOf(EntryType.COMPLETED)!!.protocol)
        assertFalse(sim.decision() is PoolDecision.Blocked && (sim.decision() as PoolDecision.Blocked).reason == BlockReason.RUN_LOCK)
        assertTrue(sim.lastEffects<Effect.CancelNotify>().any { it.id == Txn.runNotice(r) })
    }

    @Test
    fun oneSnoozeOfHalfTheTime() {
        val sim = Sim("2026-10-19T10:00")
        sim.step(Input.Promise(line, 15))
        sim.at("2026-10-19T10:15")
        val id = sim.promise()!!.id
        assertEquals(8, Machine.snoozeMinutes(sim.promise()!!, sim.file))
        sim.step(Input.Snooze(id))
        var r = sim.promise()!!
        assertEquals(RunPhase.WAITING, r.phase)
        assertFalse(r.asking)
        assertTrue(r.promise!!.snoozed)
        assertEquals(rome("2026-10-19T10:23"), r.promise!!.dueAt)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertFalse(sim.decision() is PoolDecision.Blocked && (sim.decision() as PoolDecision.Blocked).reason == BlockReason.RUN_LOCK)
        assertEquals(8, sim.promised("snoozed").single().minutes)
        sim.at("2026-10-19T10:23")
        r = sim.promise()!!
        assertTrue(r.asking)
        // A second snooze is not offered.
        assertNull(Machine.snoozeMinutes(r, sim.file))
        sim.step(Input.Snooze(id))
        assertEquals(RunPhase.ACTIVE, sim.promise()!!.phase)
        assertEquals(1, sim.promised("snoozed").size)
        // Half the time, at least 2 minutes.
        val five = Sim("2026-10-19T10:00")
        five.step(Input.Promise(line, 3))
        five.at("2026-10-19T10:03")
        assertEquals(2, Machine.snoozeMinutes(five.promise()!!, five.file))
    }

    @Test
    fun oneAtATime() {
        val sim = Sim("2026-10-19T10:00")
        sim.step(Input.Promise(line, 5))
        sim.step(Input.Promise("Second", 30))
        assertEquals(line, sim.promise()!!.promise!!.text)
        assertEquals("one_at_a_time", sim.lastOf(EntryType.SKIPPED)!!.reason)
        // A blank line promises nothing.
        val blank = Sim("2026-10-19T10:00")
        blank.step(Input.Promise("  \n ", 5))
        assertNull(blank.promise())
        // Not from a plain START.
        blank.step(Input.Manual("promise"))
        assertNull(blank.promise())
        assertEquals("needs_text", blank.lastOf(EntryType.SKIPPED)!!.reason)
    }

    @Test
    fun doneBeforeItIsDue() {
        val sim = Sim("2026-10-19T10:00")
        sim.step(Input.Promise(line, 30))
        sim.after(7, Input.Confirm(sim.promise()!!.id))
        assertNull(sim.promise())
        assertEquals("done before it was due", sim.promised("kept").single().detail)
        sim.at("2026-10-19T10:30")
        assertNull(Machine.activeRun(sim.state, sim.env))
    }

    @Test
    fun leaveIsABailOutWithThePenalty() {
        val sim = Sim("2026-10-19T10:00")
        sim.step(Input.Promise(line, 5))
        sim.at("2026-10-19T10:05")
        sim.step(Input.BailOut(sim.promise()!!.id))
        assertNull(sim.promise())
        assertEquals(1, sim.promised("bailed").size)
        assertEquals("promise", sim.lastOf(EntryType.BAILED)!!.protocol)
        assertEquals(120, sim.lastOf(EntryType.PENALTY)!!.minutes)
        assertEquals(1, sim.state.missesInARow)
        assertEquals(StageMemory(1), sim.state.stages["promise"])
    }

    @Test
    fun itExpiresWithTheDay() {
        val sim = Sim("2026-10-19T22:00")
        sim.step(Input.Promise(line, 30))
        sim.at("2026-10-19T22:30")
        assertTrue(sim.promise()!!.asking)
        sim.at("2026-10-19T22:40", Input.GoingToSleep())
        assertNull(sim.promise())
        assertEquals(1, sim.promised("expired_at_day_end").size)
        assertEquals("day_end", sim.of(EntryType.DROPPED).last { it.protocol == "promise" }.reason)
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
        assertTrue(sim.of(EntryType.MISSED).none { it.protocol == "promise" })
        // Also while still counting down, at the fallback end (after midnight).
        val night = Sim("2026-10-19T23:00", file = PRAYERS_ONLY.copy(protocols = PRAYERS_ONLY.protocols + BundledProtocols.file.find("promise")!!))
        night.at("2026-10-20T05:50", Input.Promise(line, 30))
        assertEquals("2026-10-19", night.state.day!!.key)
        night.at("2026-10-20T06:01")
        assertNull(night.promise())
        assertEquals(1, night.promised("expired_at_day_end").size)
    }

    @Test
    fun aScreenLockGoesFirstAndTheAskComesBack() {
        val alpha = testProtocol("alpha")
        val sim = Sim("2026-10-19T12:00", file = fileWith(alpha))
        sim.step(Input.Promise(line, 5))
        sim.at("2026-10-19T12:05")
        val r = sim.promise()!!
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        // Another screen protocol starts at once (it does not wait behind the ask).
        sim.step(Input.Manual("alpha"))
        assertEquals(RunPhase.ACTIVE, sim.run("alpha")!!.phase)
        assertEquals(sim.run("alpha"), Machine.activeRun(sim.state, sim.env))
        sim.step(Input.BailOut(sim.runId("alpha")))
        // The ask is back once the screen is free.
        assertEquals(r.id, Machine.activeRun(sim.state, sim.env)!!.id)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == r.id })
        // A prayer too.
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        assertEquals("prayer-dhuhr", Machine.activeRun(sim.state, sim.env)!!.protocol)
        sim.at("2026-10-19T12:50")
        assertEquals(r.id, Machine.activeRun(sim.state, sim.env)!!.id)
    }

    @Test
    fun dueDuringACall() {
        val sim = Sim("2026-10-19T10:00")
        sim.step(Input.Promise(line, 5))
        sim.at("2026-10-19T10:03", Input.CallActive(true))
        // Due on time; the app keeps every takeover off the call screen until the call ends.
        sim.at("2026-10-19T10:05")
        assertTrue(sim.promise()!!.asking)
        sim.at("2026-10-19T10:20", Input.CallActive(false))
        assertTrue(sim.promise()!!.asking)
    }

    @Test
    fun quietAndNoStepDown() {
        val sim = Sim("2026-10-19T10:00")
        sim.step(Input.Toggle(ToggleName.QUIET, true))
        sim.step(Input.Promise(line, 5))
        sim.at("2026-10-19T10:05")
        val r = sim.promise()!!
        assertTrue(Machine.isSilent(sim.state, r))
        sim.step(Input.NotOkay(r.id))
        assertEquals(Version.FULL, sim.promise()!!.version)
        assertTrue(sim.of(EntryType.STEPPED_DOWN).isEmpty())
    }

    @Test
    fun theKillSwitch() {
        val sim = Sim("2026-10-19T10:00")
        sim.step(Input.Promise(line, 5))
        sim.step(Input.Suspend(30))
        assertNull(sim.promise())
        assertEquals("kill_switch", sim.lastOf(EntryType.DROPPED)!!.reason)
        sim.step(Input.Promise(line, 5))
        assertNull(sim.promise())
        assertEquals("suspended", sim.lastOf(EntryType.SKIPPED)!!.reason)
    }

    @Test
    fun theClockChange() {
        // 02:50 summer time on 25 October, 30 minutes: due 30 real minutes later, at 02:20 winter time.
        val sim = Sim("2026-10-24T22:00", file = PRAYERS_ONLY.copy(protocols = PRAYERS_ONLY.protocols + BundledProtocols.file.find("promise")!!))
        sim.at("2026-10-25T02:50", Input.Promise(line, 30))
        val due = rome("2026-10-25T02:50").plus(Duration.ofMinutes(30))
        assertEquals(due, sim.promise()!!.promise!!.dueAt)
        assertEquals("PROMISE AT 02:20", sim.lastEffects<Effect.Notify>().single().title)
        sim.now = due.minusSeconds(60)
        sim.step()
        assertFalse(sim.promise()!!.asking)
        sim.now = due
        sim.step()
        assertTrue(sim.promise()!!.asking)
    }

    @Test
    fun alwaysStageOne() {
        val sim = Sim("2026-10-19T10:00")
        sim.state = sim.state.copy(stages = mapOf("promise" to StageMemory(3)))
        sim.step(Input.Promise(line, 5))
        assertEquals(1, sim.promise()!!.stage)
        sim.at("2026-10-19T10:05")
        assertTrue(sim.promise()!!.asking)
    }

    @Test
    fun aTestPromiseCountsForNothing() {
        val sim = Sim("2026-10-19T10:00")
        sim.step(Input.Promise("TEST PROMISE", 1, test = true))
        val r = sim.promise()!!
        assertTrue(r.test)
        sim.after(1)
        assertTrue(sim.promise()!!.asking)
        sim.step(Input.BailOut(r.id))
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
        assertEquals(0, sim.state.missesInARow)
        assertTrue(sim.entries.filter { it.run == r.id }.all { it.test == true })
        assertNull(sim.state.lastFired["promise"])
    }

    @Test
    fun stateRoundTrip() {
        val sim = Sim("2026-10-19T10:00")
        sim.step(Input.Promise(line, 10))
        val waiting = sim.state.encode()
        assertTrue(waiting.contains("\"phase\":\"waiting\""))
        assertEquals(sim.state, MachineState.decode(waiting))
        sim.at("2026-10-19T10:10")
        assertEquals(sim.state, MachineState.decode(sim.state.encode()))
        assertNotNull(MachineState.decode(sim.state.encode()).runs.single().promise)
    }
}
