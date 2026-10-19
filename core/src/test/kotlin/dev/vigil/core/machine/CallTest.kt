package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.journal.Report
import dev.vigil.core.journal.ReportMeta
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import dev.vigil.core.run.StageMemory
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/** Design M8: the call protocol, one run per MY TIMES calls row. */
class CallTest {
    private val file = fileWith(BundledProtocols.file.find("call")!!)
    private val everyDay = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val mom = Slot(everyDay, "13:00", label = "MOM")
    private val dad = Slot(everyDay, "14:00", label = "DAD")

    private fun calls(vararg rows: Slot) = MyTimes(calls = rows.toList())

    private fun Sim.call(label: String = "MOM"): Run? = state.runs.firstOrNull { it.call != null && it.label == label }

    private fun Sim.view(label: String = "MOM") = Machine.stepView(state, call(label)!!.id, env)!!

    private fun Sim.phoneCall(from: String, to: String) {
        at(from, Input.CallActive(true))
        at(to, Input.CallActive(false))
    }

    @Test
    fun theCardAppearsAndACallOfThreeMinutesUnlocksDone() {
        val sim = Sim(file = file, times = calls(mom))
        sim.at("2026-10-19T13:00")
        val r = sim.call()!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(LockType.NONE, r.lock)
        assertTrue(r.asking)
        assertEquals("slot:call", r.reason)
        assertEquals(r.id, Machine.activeRun(sim.state, sim.env)?.id)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == r.id })
        assertEquals(Intensity.FULL, Machine.intensity(r, sim.file))
        assertFalse(Machine.reasserts(r))
        val notice = sim.lastEffects<Effect.Notify>().single()
        assertEquals(NotifyKind.CALL, notice.kind)
        assertEquals("CALL MOM", notice.title)
        val card = sim.lastOf(EntryType.CALL_CARD)!!
        assertEquals("card", card.reason)
        assertEquals("MOM", card.detail)
        assertEquals("2026-10-19T16:00:00+02:00", card.until)
        var v = sim.view().call!!
        assertEquals("MOM", v.who)
        assertFalse(v.done)
        assertEquals(rome("2026-10-19T16:00"), v.dueAt)
        assertEquals(3, v.minCallMin)
        assertFalse(sim.view().canTap)
        // DONE before a call does nothing.
        sim.step(Input.StepDone(r.id, 0))
        sim.step(Input.Confirm(r.id))
        assertNotNull(sim.call())
        // A four-minute call: DONE unlocks and the card comes back with it.
        sim.phoneCall("2026-10-19T13:02", "2026-10-19T13:06")
        val called = sim.lastOf(EntryType.CALL)!!
        assertEquals("called", called.reason)
        assertEquals(4, called.minutes)
        assertTrue(sim.call()!!.asking)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == r.id })
        assertEquals("Call done: tap DONE.", sim.lastEffects<Effect.Notify>().single { it.kind == NotifyKind.CALL }.text)
        v = sim.view().call!!
        assertTrue(v.done)
        assertTrue(sim.view().canTap)
        assertEquals(240L, v.longestCallS)
        sim.step(Input.StepDone(r.id, 0))
        assertNull(sim.call())
        val done = sim.lastOf(EntryType.CALL)!!
        assertEquals("done", done.reason)
        assertEquals(4, done.minutes)
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
        assertEquals(StageMemory(1, 1, listOf("clean")), sim.state.stages["call"])
    }

    @Test
    fun onlyTheCallTimeAfterTheCardCounts() {
        val sim = Sim(file = file, times = calls(mom))
        sim.phoneCall("2026-10-19T12:30", "2026-10-19T12:50")
        // On the phone when the card appears: only the minutes after it count.
        sim.at("2026-10-19T12:58", Input.CallActive(true))
        sim.at("2026-10-19T13:00")
        sim.at("2026-10-19T13:02", Input.CallActive(false))
        assertFalse(sim.view().call!!.done)
        assertEquals(120L, sim.call()!!.call!!.callS)
        // Two minutes again: still not enough; the longest call is kept.
        sim.phoneCall("2026-10-19T13:10", "2026-10-19T13:12")
        assertFalse(sim.view().call!!.done)
        assertTrue(sim.of(EntryType.CALL).isEmpty())
        sim.phoneCall("2026-10-19T13:20", "2026-10-19T13:23")
        assertTrue(sim.view().call!!.done)
    }

    @Test
    fun notNowClosesTheCardWhichComesBackOnce() {
        val sim = Sim(file = file, times = calls(mom))
        sim.at("2026-10-19T13:00")
        val id = sim.call()!!.id
        sim.at("2026-10-19T13:01", Input.Dismiss(id))
        assertFalse(sim.call()!!.asking)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals("not_now", sim.lastOf(EntryType.CALL_CARD)!!.reason)
        assertEquals(rome("2026-10-19T13:30"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T13:30")
        assertTrue(sim.call()!!.asking)
        assertTrue(sim.call()!!.returned)
        assertEquals("returned", sim.lastOf(EntryType.CALL_CARD)!!.reason)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == id })
        assertTrue(sim.view().call!!.returned)
        // Once only.
        sim.at("2026-10-19T13:31", Input.Dismiss(id))
        sim.at("2026-10-19T14:05")
        assertFalse(sim.call()!!.asking)
        assertEquals(1, sim.of(EntryType.CALL_CARD).count { it.reason == "returned" })
        // Done later: completed, not clean (the card had to come back).
        sim.phoneCall("2026-10-19T14:10", "2026-10-19T14:15")
        sim.step(Input.Confirm(id))
        assertNull(sim.call())
        assertEquals("done", sim.lastOf(EntryType.COMPLETED)!!.reason)
    }

    @Test
    fun theCardDoesNotComeBackDuringACall() {
        val sim = Sim(file = file, times = calls(mom))
        sim.at("2026-10-19T13:00")
        val id = sim.call()!!.id
        sim.at("2026-10-19T13:01", Input.Dismiss(id))
        sim.at("2026-10-19T13:29", Input.CallActive(true))
        sim.at("2026-10-19T13:30")
        assertFalse(sim.call()!!.asking)
        sim.at("2026-10-19T13:31", Input.CallActive(false))
        assertTrue(sim.call()!!.asking)
        assertEquals("2026-10-19T13:31:00+02:00", sim.lastOf(EntryType.CALL_CARD)!!.ts)
    }

    @Test
    fun aCallGoingOnAtTheDeadlineHoldsIt() {
        // MOM is called at 15:55 and the call lasts past the 16:00 deadline: a call never ends a protocol.
        val sim = Sim(file = file, times = calls(mom))
        sim.at("2026-10-19T13:00")
        sim.at("2026-10-19T15:55", Input.CallActive(true))
        sim.at("2026-10-19T16:05")
        assertNotNull(sim.call())
        assertTrue(sim.of(EntryType.MISSED).isEmpty())
        // It ended at 16:10 (15 minutes): DONE is unlocked and the deadline moved on by the call's length.
        sim.at("2026-10-19T16:10", Input.CallActive(false))
        assertTrue(sim.view().call!!.done)
        assertEquals(rome("2026-10-19T16:15"), sim.call()!!.call!!.dueAt)
        sim.at("2026-10-19T16:12", Input.Confirm(sim.call()!!.id))
        assertNull(sim.call())
        assertEquals("done", sim.lastOf(EntryType.CALL)!!.reason)
        // Not tapped after the call: missed at the moved deadline.
        val late = Sim(file = file, times = calls(mom))
        late.at("2026-10-19T13:00")
        late.phoneCall("2026-10-19T15:58", "2026-10-19T16:02")
        assertNotNull(late.call())
        late.at("2026-10-19T16:07")
        assertNull(late.call())
        assertEquals("2026-10-19T16:04:00+02:00", late.lastOf(EntryType.MISSED)!!.ts)
        // A call that ended before the deadline moves nothing.
        val early = Sim(file = file, times = calls(mom))
        early.at("2026-10-19T13:00")
        early.phoneCall("2026-10-19T14:00", "2026-10-19T14:01")
        assertEquals(rome("2026-10-19T16:00"), early.call()!!.call!!.dueAt)
    }

    @Test
    fun twoRowsAreTwoRunsAndMissedCallsCountForTheReset() {
        val sim = Sim(file = file, times = calls(mom, dad))
        sim.at("2026-10-19T13:00")
        sim.at("2026-10-19T14:00")
        assertEquals(listOf("MOM", "DAD"), sim.state.runs.filter { it.call != null }.map { it.label })
        assertEquals(2, Machine.status(sim.state, sim.env).calls.size)
        // The first card still asks: it owns the screen; DAD's waits for it.
        assertEquals("MOM", Machine.activeRun(sim.state, sim.env)?.label)
        sim.at("2026-10-19T16:00")
        assertTrue(sim.state.runs.none { it.call != null })
        assertEquals(listOf("MOM", "DAD"), sim.of(EntryType.CALL).filter { it.reason == "missed" }.map { it.detail })
        assertEquals(listOf("deadline", "deadline"), sim.of(EntryType.MISSED).map { it.reason })
        assertEquals("2026-10-19T16:00:00+02:00", sim.lastOf(EntryType.MISSED)!!.ts)
        assertEquals(listOf("missed", "missed"), sim.state.stages["call"]!!.recent)
        // Two missed in a row: the reset is offered.
        assertEquals("reset", sim.state.offer?.protocol)
        assertEquals("misses:2", sim.state.offer?.reason)
    }

    @Test
    fun timeToWrapUpComesOnceSilentlyAfterTwentyMinutes() {
        val sim = Sim(file = file, times = calls(mom))
        // A call before any card today: no wrap-up.
        sim.phoneCall("2026-10-19T09:00", "2026-10-19T09:40")
        assertTrue(sim.of(EntryType.WRAP_UP).isEmpty())
        sim.at("2026-10-19T13:00")
        sim.at("2026-10-19T13:05", Input.CallActive(true))
        assertEquals(rome("2026-10-19T13:25"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T13:25")
        val wrap = sim.lastEffects<Effect.Notify>().single()
        assertEquals(Txn.WRAP_NOTICE, wrap.id)
        assertEquals("TIME TO WRAP UP", wrap.title)
        assertTrue(wrap.silent)
        assertEquals(NotifyKind.CARD, wrap.kind)
        assertEquals(20, sim.lastOf(EntryType.WRAP_UP)!!.minutes)
        sim.at("2026-10-19T13:40")
        assertEquals(1, sim.of(EntryType.WRAP_UP).size)
        sim.at("2026-10-19T13:45", Input.CallActive(false))
        assertTrue(sim.lastEffects<Effect.CancelNotify>().any { it.id == Txn.WRAP_NOTICE })
        sim.step(Input.Confirm(sim.call()!!.id))
        // Later the same day, after the card: any call gets its wrap-up.
        sim.at("2026-10-19T17:00", Input.CallActive(true))
        sim.at("2026-10-19T17:21")
        assertEquals(2, sim.of(EntryType.WRAP_UP).size)
        sim.at("2026-10-19T17:30", Input.CallActive(false))
        // A new day: nothing until its first card.
        sim.at("2026-10-19T23:40", Input.GoingToSleep())
        sim.phoneCall("2026-10-20T09:00", "2026-10-20T09:30")
        assertEquals(2, sim.of(EntryType.WRAP_UP).size)
    }

    @Test
    fun theShortVersionIsAMessageAndThereIsNoMinimal() {
        val sim = Sim(file = file, times = calls(mom))
        sim.at("2026-10-19T13:00")
        val id = sim.call()!!.id
        sim.step(Input.NotOkay(id))
        val r = sim.call()!!
        assertEquals(Version.SHORT, r.version)
        assertTrue(r.asking)
        assertEquals("a message instead", sim.lastOf(EntryType.STEPPED_DOWN)!!.detail)
        val v = sim.view().call!!
        assertTrue(v.message)
        assertTrue(v.done)
        assertTrue(sim.lastEffects<Effect.Notify>().single().text.startsWith("Send a message instead"))
        // A further NOT OKAY changes nothing.
        sim.step(Input.NotOkay(id))
        assertEquals(Version.SHORT, sim.call()!!.version)
        assertEquals(1, sim.of(EntryType.STEPPED_DOWN).size)
        sim.step(Input.StepDone(id, 0))
        assertNull(sim.call())
        assertEquals("short", sim.lastOf(EntryType.CALL)!!.version)
        assertEquals("done", sim.lastOf(EntryType.COMPLETED)!!.reason)
    }

    @Test
    fun stagesSuspendAndQuiet() {
        // Stage 3: one notification; CONFIRM completes it.
        val three = Sim(file = file, times = calls(mom))
        three.state = three.state.copy(stages = mapOf("call" to StageMemory(3)))
        three.at("2026-10-19T13:00")
        assertEquals(RunPhase.REMINDER, three.call()!!.phase)
        assertNull(Machine.activeRun(three.state, three.env))
        assertEquals("CALL MOM", three.lastEffects<Effect.Notify>().single().title)
        three.step(Input.Confirm(three.call()!!.id))
        assertNull(three.call())
        assertEquals("done", three.lastOf(EntryType.CALL)!!.reason)
        // Stage 2: the card does not come back.
        val two = Sim(file = file, times = calls(mom))
        two.state = two.state.copy(stages = mapOf("call" to StageMemory(2)))
        two.at("2026-10-19T13:00")
        two.at("2026-10-19T13:01", Input.Dismiss(two.call()!!.id))
        two.at("2026-10-19T13:40")
        assertFalse(two.call()!!.asking)
        // The kill switch drops the card.
        val suspended = Sim(file = file, times = calls(mom))
        suspended.at("2026-10-19T13:00")
        suspended.step(Input.Suspend(60))
        assertNull(suspended.call())
        assertEquals("kill_switch", suspended.lastOf(EntryType.DROPPED)!!.reason)
        // QUIET: the card still shows, silently.
        val quiet = Sim(file = file, times = calls(mom))
        quiet.step(Input.Toggle(dev.vigil.core.protocol.ToggleName.QUIET, true))
        quiet.at("2026-10-19T13:00")
        assertTrue(Machine.isSilent(quiet.state, quiet.call()!!))
        assertEquals(quiet.call()!!.id, Machine.activeRun(quiet.state, quiet.env)?.id)
    }

    @Test
    fun aDeadlineAfterMidnightAndTheDayEnd() {
        val late = Slot(everyDay, "23:40", "00:20", label = "MOM")
        val sim = Sim(file = file, times = calls(late))
        sim.at("2026-10-19T23:40")
        assertNotNull(sim.call())
        sim.at("2026-10-20T00:20")
        assertEquals("2026-10-19", sim.state.day!!.key)
        assertNull(sim.call())
        assertEquals("deadline", sim.lastOf(EntryType.MISSED)!!.reason)
        // A card still open at GOING TO SLEEP is missed with the day.
        val evening = Sim(file = file, times = calls(Slot(everyDay, "22:00", label = "DAD")))
        evening.at("2026-10-19T22:00")
        assertEquals(rome("2026-10-20T16:00"), evening.call("DAD")!!.call!!.dueAt)
        evening.at("2026-10-19T23:40", Input.GoingToSleep())
        assertNull(evening.call("DAD"))
        assertEquals("day_end", evening.lastOf(EntryType.MISSED)!!.reason)
        assertEquals("missed", evening.lastOf(EntryType.CALL)!!.reason)
    }

    @Test
    fun theClockChangeDay() {
        val sim = Sim("2026-10-25T08:00", file = file, times = calls(mom))
        sim.at("2026-10-25T13:00")
        assertEquals("2026-10-25T13:00:00+01:00", sim.lastOf(EntryType.CALL_CARD)!!.ts)
        assertEquals(OffsetDateTime.parse("2026-10-25T16:00+01:00").toInstant(), sim.call()!!.call!!.dueAt)
    }

    @Test
    fun aTestCardCountsForNothing() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T10:00", Input.Manual("call", test = true))
        val r = sim.state.runs.single()
        assertTrue(r.test)
        assertEquals("TEST", r.label)
        assertTrue(r.asking)
        assertEquals(rome("2026-10-19T11:00"), r.call!!.dueAt)
        assertNull(sim.state.day!!.callCardAt)
        sim.at("2026-10-19T11:01")
        assertTrue(sim.state.runs.isEmpty())
        assertTrue(sim.state.stages.isEmpty())
        assertEquals(0, sim.state.missesInARow)
    }

    @Test
    fun theCallSurvivesEncodeDecodeAndTheReportShowsIt() {
        val sim = Sim(file = file, times = calls(mom, dad))
        sim.at("2026-10-19T13:00")
        sim.at("2026-10-19T13:05", Input.CallActive(true))
        val text = sim.state.encode()
        assertEquals(sim.state, MachineState.decode(text))
        sim.at("2026-10-19T13:17", Input.CallActive(false))
        sim.step(Input.Confirm(sim.call()!!.id))
        sim.at("2026-10-19T16:00")
        val md = Report.markdown(sim.entries, ReportMeta(sim.now, ROME))
        assertTrue(md, md.contains("- Calls: 1 done, 1 missed"))
        assertTrue(md, md.contains("- **Calls**: MOM done (12 min call) 13:17; DAD missed 16:00"))
    }
}
