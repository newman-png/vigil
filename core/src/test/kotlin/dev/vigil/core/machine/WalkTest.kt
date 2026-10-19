package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.RunPhase
import dev.vigil.core.timeline.StartStatus
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design M8: the walk (the I'M WALKING switch, the MY TIMES commute rows) and friends mode. */
class WalkTest {
    private val file = fileWith(BundledProtocols.file.find("walk")!!, BundledProtocols.file.find("friends")!!)
    private val ig = "com.instagram.android"
    private val weekdays = listOf("mon", "tue", "wed", "thu", "fri")

    private fun Sim.blocks(): List<BlockReason> = (decision() as? PoolDecision.Blocked)?.blocks?.map { it.reason } ?: emptyList()

    private fun Sim.walking() = state.toggles.walking

    private fun commute(vararg rows: Slot) = MyTimes(commute = rows.toList())

    @Test
    fun theSwitchStartsAWalkThatOnlyBlocksThePool() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T10:00", Input.Toggle(ToggleName.WALKING, true))
        val r = sim.run("walk")!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(LockType.POOL, r.lock)
        assertEquals("toggle:walking", r.reason)
        // No prompts, no takeovers: only a silent, ongoing notice with STOP.
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertTrue(sim.effectsOf<Effect.ShowTakeover>().isEmpty())
        val notice = sim.lastEffects<Effect.Notify>().single()
        assertEquals(NotifyKind.SWITCH, notice.kind)
        assertEquals("WALKING · POOL BLOCKED", notice.title)
        assertTrue(notice.silent)
        assertEquals(listOf(BlockReason.WALKING), sim.blocks())
        assertEquals("switch", sim.lastOf(EntryType.WALK_START)!!.reason)
        // Maps and WhatsApp are not pool apps: they stay usable.
        assertEquals(PoolDecision.Allowed, sim.decision(isPool = false))
        // Off after 25 minutes: the walk ends and the pool is as before.
        sim.after(25, Input.Toggle(ToggleName.WALKING, false))
        assertNull(sim.run("walk"))
        val end = sim.lastOf(EntryType.WALK_END)!!
        assertEquals("off", end.reason)
        assertEquals(25, end.minutes)
        assertEquals(25, sim.lastOf(EntryType.TOGGLE)!!.minutes)
        assertEquals("walk", sim.lastOf(EntryType.COMPLETED)!!.protocol)
        assertTrue(sim.decision() is PoolDecision.Wait)
        // Its outcomes count for nothing: no stage memory, no misses in a row.
        assertNull(sim.state.stages["walk"])
        assertEquals(1, Machine.status(sim.state, sim.env).stages["walk"])
    }

    @Test
    fun aWalkEndsTenMinutesAfterTheLastStep() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T10:00", Input.Steps(5000))
        sim.at("2026-10-19T10:30", Input.Toggle(ToggleName.WALKING, true))
        assertTrue(sim.lastOf(EntryType.WALK_START)!!.detail!!.contains("10 minutes without steps"))
        // The count starts at the walk's start, not at the last step before it: no walk ends at once.
        assertEquals(rome("2026-10-19T10:40"), Machine.status(sim.state, sim.env).walkEndsAt)
        sim.at("2026-10-19T10:38", Input.Steps(5600))
        sim.at("2026-10-19T10:45", Input.Steps(6400))
        sim.at("2026-10-19T10:52", Input.Steps(6400))
        assertTrue(sim.walking())
        assertEquals(rome("2026-10-19T10:55"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T11:05")
        assertFalse(sim.walking())
        val end = sim.lastOf(EntryType.WALK_END)!!
        assertEquals("still", end.reason)
        assertEquals("2026-10-19T10:55:00+02:00", end.ts)
        assertEquals(25, end.minutes)
        assertEquals("off (no steps for 10 minutes)", sim.lastOf(EntryType.TOGGLE)!!.detail)
    }

    @Test
    fun theCommuteRowsStartAWalkByThemselves() {
        val sim = Sim("2026-10-19T07:00", file = file, times = commute(Slot(weekdays, "08:00", minutes = 45, label = "to uni")))
        val planned = Machine.timeline(sim.state, sim.env).single { it.start.protocolId == "walk" }
        assertEquals(rome("2026-10-19T08:00"), planned.start.at)
        sim.at("2026-10-19T08:00")
        assertTrue(sim.walking())
        assertEquals("commute", sim.run("walk")!!.reason)
        assertEquals("commute", sim.lastOf(EntryType.WALK_START)!!.reason)
        assertEquals("on (the 08:00 commute)", sim.lastOf(EntryType.TOGGLE)!!.detail)
        assertEquals(StartStatus.FIRED, Machine.timeline(sim.state, sim.env).single { it.start.protocolId == "walk" }.status)
        // Without a step counter it ends at the commute's end + 15 minutes of grace.
        assertEquals(rome("2026-10-19T09:00"), Machine.status(sim.state, sim.env).walkEndsAt)
        sim.at("2026-10-19T09:10")
        assertFalse(sim.walking())
        assertEquals("time", sim.lastOf(EntryType.WALK_END)!!.reason)
        assertEquals("2026-10-19T09:00:00+02:00", sim.lastOf(EntryType.WALK_END)!!.ts)
        // A commute day counts as the morning walk.
        assertTrue(sim.state.runs.isEmpty())
    }

    @Test
    fun aCommuteStartsLateUntilItsEndAndUsesCommuteMinWithoutMinutes() {
        val times = commute(Slot(weekdays, "08:00", label = "to uni")).copy(commuteMin = 50)
        val sim = Sim("2026-10-19T07:00", file = file, times = times)
        // The phone was off at 08:00: at 08:40 the commute is still on, so the walk starts.
        sim.at("2026-10-19T08:40")
        assertTrue(sim.walking())
        assertEquals(rome("2026-10-19T09:05"), Machine.status(sim.state, sim.env).walkEndsAt)
        // Off at 09:00 (the commute's end): too late for a commute that day.
        val off = Sim("2026-10-19T07:00", file = file, times = times)
        off.at("2026-10-19T09:00")
        assertFalse(off.walking())
        assertEquals(StartStatus.NOT_FIRED, Machine.timeline(off.state, off.env).single { it.start.protocolId == "walk" }.status)
    }

    @Test
    fun aCommuteWhileWalkingIsSkippedAndTheStepCounterDecidesWhenAvailable() {
        val sim = Sim("2026-10-19T07:00", file = file, times = commute(Slot(weekdays, "08:00", minutes = 45)))
        sim.at("2026-10-19T07:55", Input.Toggle(ToggleName.WALKING, true))
        sim.at("2026-10-19T08:00")
        assertEquals("already_running", sim.lastOf(EntryType.SKIPPED)!!.reason)
        assertEquals(1, sim.of(EntryType.WALK_START).size)
        // The phone tells it has no step counter, then that it has one: the rule follows.
        sim.step(Input.Steps(null))
        assertFalse(sim.state.steps.available)
        assertEquals(rome("2026-10-19T10:55"), Machine.status(sim.state, sim.env).walkEndsAt)
        sim.step(Input.Steps(100))
        assertEquals(rome("2026-10-19T08:10"), Machine.status(sim.state, sim.env).walkEndsAt)
    }

    @Test
    fun theWalkEndsAtIAmAtUniStopAndTheDayEnd() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T10:00", Input.Toggle(ToggleName.WALKING, true))
        // STOP on its notification: the switch goes off, no penalty.
        sim.step(Input.BailOut(sim.runId("walk")))
        assertFalse(sim.walking())
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
        assertTrue(sim.of(EntryType.BAILED).isEmpty())
        // NOT OKAY changes nothing (no ladder).
        sim.step(Input.Toggle(ToggleName.WALKING, true))
        sim.step(Input.NotOkay(sim.runId("walk")))
        assertEquals(Version.FULL, sim.run("walk")!!.version)
        assertTrue(sim.of(EntryType.STEPPED_DOWN).isEmpty())
        sim.step(Input.Toggle(ToggleName.WALKING, false))
        // Walking after midnight belongs to the day; GOING TO SLEEP ends the walk with the day.
        sim.at("2026-10-19T23:50", Input.Toggle(ToggleName.WALKING, true))
        sim.at("2026-10-20T00:05", Input.Steps(10))
        sim.at("2026-10-20T00:12", Input.Steps(20))
        sim.at("2026-10-20T00:20")
        assertEquals("2026-10-19", sim.state.day!!.key)
        assertTrue(sim.walking())
        sim.at("2026-10-20T00:21", Input.GoingToSleep())
        assertFalse(sim.walking())
        assertEquals("day_end", sim.lastOf(EntryType.WALK_END)!!.reason)
        assertNull(sim.run("walk"))
    }

    @Test
    fun suspendedTheSwitchStaysButNothingIsBlocked() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T10:00", Input.Toggle(ToggleName.WALKING, true))
        sim.step(Input.Suspend(30))
        assertTrue(sim.walking())
        assertNotNull(sim.run("walk"))
        assertTrue(sim.decision() !is PoolDecision.Blocked)
        sim.after(31)
        assertEquals(listOf(BlockReason.WALKING), sim.blocks())
    }

    @Test
    fun aTestWalkIsAPreviewThatEndsByItself() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T10:00", Input.Manual("walk", test = true))
        val r = sim.run("walk")!!
        assertTrue(r.test)
        assertFalse(sim.walking())
        assertTrue(sim.blocks().isEmpty())
        assertEquals("TEST · WALK", sim.lastEffects<Effect.Notify>().single().title)
        assertEquals(rome("2026-10-19T10:02"), Machine.nextWake(sim.state, sim.env))
        sim.after(3)
        assertNull(sim.run("walk"))
        assertTrue(sim.of(EntryType.WALK_START).isEmpty())
    }

    @Test
    fun aWalkAcrossTheClockChangeLastsItsRealMinutes() {
        // Sunday 25 October: the clocks go back at 03:00 (CEST) to 02:00 (CET). A walk at 02:30 CEST without a step
        // counter ends three real hours later: 04:30 CET.
        val sim = Sim("2026-10-24T22:00", file = file)
        sim.now = java.time.OffsetDateTime.parse("2026-10-25T02:30+02:00").toInstant()
        sim.step(Input.Toggle(ToggleName.WALKING, true))
        assertEquals(java.time.OffsetDateTime.parse("2026-10-25T04:30+01:00").toInstant(), Machine.status(sim.state, sim.env).walkEndsAt)
        // That day's 08:00 commute is 08:00 CET.
        val dst = Sim("2026-10-25T07:00", file = file, times = commute(Slot(listOf("sun"), "08:00", minutes = 30)))
        dst.at("2026-10-25T08:00")
        assertTrue(dst.walking())
        assertEquals("2026-10-25T08:00:00+01:00", dst.lastOf(EntryType.WALK_START)!!.ts)
    }

    @Test
    fun theWalkSurvivesEncodeDecodeAndAnOldStateReadsWithoutTheNewFields() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T10:00", Input.Steps(42))
        sim.step(Input.Toggle(ToggleName.WALKING, true))
        val text = sim.state.encode()
        assertEquals(sim.state, MachineState.decode(text))
        // A state saved before M8 (no steps, no walking_until, no audio, no call fields) still reads.
        val old = """{"version":1,"toggles":{"quiet":false,"walking":true,"friends":false,"walking_since":"2026-10-19T08:00:00Z"}}"""
        val decoded = MachineState.decode(old)
        assertTrue(decoded.toggles.walking)
        assertNull(decoded.toggles.walkingUntil)
        assertEquals(StepState(), decoded.steps)
        assertNull(decoded.audio)
        // Without walking_until it ends after manual_max_min.
        assertEquals(rome("2026-10-19T13:00"), View(decoded, sim.env).walkEnd())
    }

    // ------------------------------------------------------------------ friends

    @Test
    fun friendsBlockThePoolWithNoWaitUntilTheCap() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T14:00", Input.Toggle(ToggleName.FRIENDS, true))
        assertEquals(RunPhase.ACTIVE, sim.run("friends")!!.phase)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals("WITH FRIENDS · POOL BLOCKED", sim.lastEffects<Effect.Notify>().single().title)
        // The pool is refused at once: no wait screen.
        sim.step(Input.Foreground(ig, true))
        val gate = sim.lastEffects<Effect.PoolGate>().single()
        val blocked = gate.decision as PoolDecision.Blocked
        assertEquals(BlockReason.FRIENDS, blocked.reason)
        assertNull(blocked.blocks.first().until)
        assertEquals("POOL BLOCKED · YOU'RE WITH FRIENDS", Machine.poolStatus(sim.state, sim.env))
        assertEquals(rome("2026-10-19T18:00"), Machine.status(sim.state, sim.env).friendsEndsAt)
        sim.at("2026-10-19T18:01")
        assertFalse(sim.state.toggles.friends)
        assertNull(sim.run("friends"))
        val off = sim.lastOf(EntryType.TOGGLE)!!
        assertEquals("off (the 240-minute cap)", off.detail)
        assertEquals(240, off.minutes)
        assertNull(sim.state.stages["friends"])
    }

    @Test
    fun friendsEndAtStopAndWithTheDay() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T20:00", Input.Toggle(ToggleName.FRIENDS, true))
        sim.step(Input.BailOut(sim.runId("friends")))
        assertFalse(sim.state.toggles.friends)
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
        sim.at("2026-10-19T22:00", Input.Toggle(ToggleName.FRIENDS, true))
        sim.at("2026-10-19T23:40", Input.GoingToSleep())
        assertFalse(sim.state.toggles.friends)
        assertEquals("off (the day ended)", sim.of(EntryType.TOGGLE).last { it.reason == "friends" }.detail)
    }

    @Test
    fun theReportShowsWalksAndFriendsTime() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T10:00", Input.Toggle(ToggleName.WALKING, true))
        sim.at("2026-10-19T10:40", Input.Toggle(ToggleName.WALKING, false))
        sim.at("2026-10-19T19:00", Input.Toggle(ToggleName.FRIENDS, true))
        sim.at("2026-10-19T21:00", Input.Toggle(ToggleName.FRIENDS, false))
        val md = dev.vigil.core.journal.Report.markdown(sim.entries, dev.vigil.core.journal.ReportMeta(sim.now, ROME))
        assertTrue(md, md.contains("- Walks: 1 walk, 40 min; with friends 2 h 0 min"))
        assertTrue(md, md.contains("- **Walks and friends** walks 10:40 40 min (switched off) · with friends 120 min"))
    }
}
