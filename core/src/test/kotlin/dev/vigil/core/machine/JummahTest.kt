package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.JummahPart
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import dev.vigil.core.run.StageMemory
import dev.vigil.core.timeline.Timeline
import dev.vigil.core.times.DayTimes
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Protocol 4: Jummah on Fridays (design M4), with the bundled `jummah` protocol and MY TIMES. */
class JummahTest {
    /** Classes on Tuesday and Friday 13:00-14:30 (the spec's timetable). */
    private val classes = MyTimes(classes = listOf(Slot(listOf("tue", "fri"), "13:00", "14:30", label = "Analysis")))

    private val p = BundledProtocols.file.find("jummah")!!

    /**
     * Friday 23 October 2026 (the clocks go back on Sunday the 25th): the day starts at 08:00 and the clock jumps to
     * [start] in one step, as when the phone was off until then.
     */
    private fun friday(times: MyTimes = classes, start: String = "2026-10-23T08:00") =
        Sim(start.take(10) + "T08:00", times = times).also { if (!start.endsWith("T08:00")) it.at(start) }

    private fun Sim.jummah(): Run = state.runs.first { it.jummah != null }

    private fun Sim.part(): JummahPart = jummah().jummah!!.part

    private fun Sim.dnd(): List<Boolean> = effectsOf<Effect.SetDnd>().map { it.on }

    private fun dayTimes(date: String, times: MyTimes) = DayTimes(LocalDate.parse(date), ROME, LocalTime.of(5, 0), times)

    @Test
    fun timesComeFromMyTimes() {
        val withClass = Timeline.jummah(p, dayTimes("2026-10-23", classes))!!
        assertEquals(rome("2026-10-23T14:40"), withClass.jummahAt)
        assertEquals(rome("2026-10-23T11:45"), withClass.leaveAt)
        assertEquals(rome("2026-10-23T12:45"), withClass.arriveAt)
        assertEquals(rome("2026-10-23T14:25"), withClass.cardAt)
        assertEquals(rome("2026-10-23T14:35"), withClass.lockAt)
        assertEquals(rome("2026-10-23T15:30"), withClass.missedAt)
        // No Friday class: from the lock (jummah_time - 5 - commute - 15, I'M AT UNI at jummah_time - 20), no card.
        val free = Timeline.jummah(p, dayTimes("2026-10-23", MyTimes()))!!
        assertEquals(rome("2026-10-23T13:20"), free.leaveAt)
        assertEquals(rome("2026-10-23T14:20"), free.arriveAt)
        assertNull(free.cardAt)
        // A class after Jummah is not the target; a longer commute leaves earlier; other days have none.
        val late = MyTimes(classes = listOf(Slot(listOf("fri"), "16:00", "17:30")), commuteMin = 30)
        assertEquals(rome("2026-10-23T13:50"), Timeline.jummah(p, dayTimes("2026-10-23", late))!!.leaveAt)
        assertNull(Timeline.jummah(p, dayTimes("2026-10-22", classes)))
        assertNull(Timeline.jummah(p, dayTimes("2026-10-23", MyTimes(jummahTime = "noon"))))
        // The home timeline shows Jummah at its leave-by time.
        val sim = friday()
        assertEquals(rome("2026-10-23T11:45"), Machine.timeline(sim.state, sim.env).single { it.start.protocolId == "jummah" }.start.at)
    }

    @Test
    fun aFridayAsTheSpecSays() {
        val sim = friday()
        sim.at("2026-10-23T11:44")
        assertTrue(sim.state.runs.none { it.jummah != null })
        // Leave-by: a full takeover with a screen lock; Maps is in the APPS button, the pool is blocked.
        sim.at("2026-10-23T11:45")
        var r = sim.jummah()
        assertEquals(JummahPart.LEAVE, sim.part())
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(rome("2026-10-23T12:45"), r.lockEndsAt)
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        assertEquals(listOf(Effect.ShowTakeover(r.id)), sim.lastEffects<Effect.ShowTakeover>())
        assertEquals(Intensity.FULL, Machine.intensity(r, sim.file))
        assertEquals(BlockReason.RUN_LOCK, (sim.decision() as PoolDecision.Blocked).reason)
        assertEquals("slot:jummah", sim.lastOf(EntryType.FIRED)!!.reason)
        assertEquals("jummah", sim.lastOf(EntryType.FIRED)!!.prayer)
        // Shoes, bag, leave, walking on: the walking switch goes on and the screen is free.
        for (i in 0..3) sim.after(1, Input.StepDone(r.id, i))
        assertTrue(sim.state.toggles.walking)
        assertEquals(JummahPart.TRAVEL, sim.part())
        assertEquals(RunPhase.OPEN, sim.jummah().phase)
        assertEquals(4, sim.jummah().step)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals("JUMMAH: ON THE WAY", sim.lastEffects<Effect.Notify>().last().title)
        // I'M AT UNI at 12:45: a full takeover that is not brought back; NOT YET asks again 10 minutes later.
        sim.at("2026-10-23T12:44")
        assertNull(Machine.activeRun(sim.state, sim.env))
        sim.at("2026-10-23T12:45")
        r = sim.jummah()
        assertTrue(r.asking)
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        assertFalse(Machine.reasserts(r))
        assertTrue(Machine.stepView(sim.state, r.id, sim.env)!!.arrival)
        assertEquals(listOf(Effect.ShowTakeover(r.id)), sim.lastEffects<Effect.ShowTakeover>())
        sim.after(1, Input.Dismiss(r.id))
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals("not_yet", sim.lastOf(EntryType.ARRIVAL_CHECK)!!.reason)
        sim.at("2026-10-23T12:54")
        assertFalse(sim.jummah().asking)
        sim.at("2026-10-23T12:55")
        assertTrue(sim.jummah().asking)
        assertEquals(2, sim.of(EntryType.ARRIVAL_CHECK).count { it.reason == null })
        // Confirmed from the notification: the walking switch goes off.
        sim.at("2026-10-23T12:57", Input.Confirm(r.id))
        assertEquals(12, sim.lastOf(EntryType.ARRIVED)!!.minutes)
        assertFalse(sim.state.toggles.walking)
        assertTrue(sim.jummah().jummah!!.arrived)
        assertNull(Machine.activeRun(sim.state, sim.env))
        sim.at("2026-10-23T13:30")
        assertTrue(sim.of(EntryType.ARRIVAL_CHECK).size == 3)
        // 14:25: the silent card, once.
        sim.at("2026-10-23T14:25")
        val card = sim.lastEffects<Effect.Notify>().single()
        assertEquals(NotifyKind.CARD, card.kind)
        assertTrue(card.silent)
        assertEquals("Class ends in 5 minutes. Go straight to the prayer place.", card.text)
        sim.at("2026-10-23T14:26")
        assertEquals(1, sim.of(EntryType.JUMMAH_CARD).size)
        // 14:35: the lock. Do Not Disturb on, the pool blocked, a subtle silent screen; DONE from 14:50.
        sim.at("2026-10-23T14:35")
        r = sim.jummah()
        assertEquals(JummahPart.LOCK, sim.part())
        assertEquals(listOf(true), sim.dnd())
        assertTrue(Machine.dndWanted(sim.state, sim.env))
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        assertTrue(Machine.reasserts(r))
        assertEquals(Intensity.SUBTLE, Machine.intensity(r, sim.file))
        assertTrue(Machine.isSilent(sim.state, r))
        assertEquals(rome("2026-10-23T15:20"), r.lockEndsAt)
        val view = Machine.stepView(sim.state, r.id, sim.env)!!
        assertEquals(5, view.index)
        assertEquals(rome("2026-10-23T14:50"), view.lockedUntil)
        assertEquals(BlockReason.RUN_LOCK, (sim.decision() as PoolDecision.Blocked).reason)
        assertTrue(Machine.status(sim.state, sim.env).dndOn)
        sim.at("2026-10-23T14:45", Input.StepDone(r.id, 5))
        assertNotNull(sim.state.runs.firstOrNull { it.jummah != null })
        // DONE after the prayer: confirmed (prayer jummah), the phone's sound back, the run over.
        sim.at("2026-10-23T14:50", Input.StepDone(r.id, 5))
        assertTrue(sim.state.runs.none { it.jummah != null })
        assertEquals("jummah", sim.lastOf(EntryType.CONFIRMED)!!.prayer)
        assertEquals("jummah", sim.lastOf(EntryType.COMPLETED)!!.protocol)
        assertEquals(listOf(true, false), sim.dnd())
        assertFalse(Machine.dndWanted(sim.state, sim.env))
        assertEquals(DayState.JUMMAH_CONFIRMED, sim.state.day!!.jummah)
        assertEquals(0, sim.state.missesInARow)
    }

    @Test
    fun fridayDhuhrIsReplaced() {
        val sim = friday(start = "2026-10-22T08:00")
        sim.at("2026-10-22T12:30", Input.Adhan(Prayer.DHUHR))
        sim.at("2026-10-22T12:38", Input.Confirm(sim.runId("prayer-dhuhr")))
        sim.at("2026-10-22T23:00", Input.GoingToSleep())
        // Friday: no heads-up for Dhuhr, and its adhan starts no run (journaled as skipped).
        sim.at("2026-10-23T12:20")
        assertTrue(sim.of(EntryType.HEADS_UP).none { it.prayer == "dhuhr" && it.ts.startsWith("2026-10-23") })
        sim.at("2026-10-23T12:30", Input.Adhan(Prayer.DHUHR))
        assertNull(sim.run("prayer-dhuhr"))
        assertEquals("dhuhr", sim.lastOf(EntryType.ADHAN)!!.prayer)
        val skipped = sim.lastOf(EntryType.SKIPPED)!!
        assertEquals("jummah", skipped.reason)
        assertEquals("prayer-dhuhr", skipped.protocol)
        // A Thursday Dhuhr is a normal Dhuhr.
        assertTrue(sim.of(EntryType.FIRED).any { it.protocol == "prayer-dhuhr" && it.ts.startsWith("2026-10-22") })
    }

    @Test
    fun missedJummahBringsDhuhrAndTheResetOffer() {
        val sim = friday(MyTimes())
        sim.at("2026-10-23T13:20")
        assertEquals(JummahPart.LEAVE, sim.part())
        // Nothing done: the leave-by screen ends at the arrival time and I'M AT UNI is asked at once.
        sim.at("2026-10-23T14:20")
        assertEquals(JummahPart.TRAVEL, sim.part())
        assertTrue(sim.jummah().asking)
        assertEquals("the leave-by takeover ended at the arrival time", sim.lastOf(EntryType.LOCK_ENDED)!!.detail)
        sim.at("2026-10-23T14:35")
        assertEquals(JummahPart.LOCK, sim.part())
        assertFalse(sim.jummah().asking)
        // The lock ends unconfirmed: the phone's sound back, CONFIRM until 15:30.
        sim.at("2026-10-23T15:20")
        assertEquals(JummahPart.CONFIRM, sim.part())
        assertEquals(listOf(true, false), sim.dnd())
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals("JUMMAH NOT CONFIRMED", sim.lastEffects<Effect.Notify>().last().title)
        // 15:30: missed; Dhuhr runs instead; the reset is offered.
        sim.at("2026-10-23T15:30")
        assertTrue(sim.state.runs.none { it.jummah != null })
        val missed = sim.lastOf(EntryType.MISSED)!!
        assertEquals("jummah", missed.protocol)
        assertEquals("not_confirmed", missed.reason)
        assertEquals("jummah", missed.prayer)
        val dhuhr = sim.run("prayer-dhuhr")!!
        assertEquals("jummah_missed", dhuhr.reason)
        assertEquals(RunPhase.ACTIVE, dhuhr.phase)
        assertEquals(Offer("reset", rome("2026-10-23T15:30"), "jummah_missed"), sim.state.offer)
        assertEquals(NotifyKind.OFFER, sim.effectsOf<Effect.Notify>().last { it.id == Txn.OFFER_NOTICE }.kind)
        assertEquals(DayState.JUMMAH_MISSED, sim.state.day!!.jummah)
        assertEquals(1, sim.state.missesInARow)
        // Dhuhr confirmed like any prayer.
        sim.at("2026-10-23T15:38", Input.Confirm(dhuhr.id))
        assertEquals(listOf(Prayer.DHUHR), sim.state.day!!.confirmed)
    }

    @Test
    fun noDhuhrOnceAsrHasCome() {
        val sim = friday(MyTimes())
        sim.at("2026-10-23T14:35")
        // Asr comes during the Jummah lock: it waits behind it, and takes the screen when the lock ends.
        sim.at("2026-10-23T15:00", Input.Adhan(Prayer.ASR))
        assertEquals(RunPhase.QUEUED, sim.run("prayer-asr")!!.phase)
        assertEquals(JummahPart.LOCK, sim.part())
        assertEquals(sim.jummah(), Machine.activeRun(sim.state, sim.env))
        sim.at("2026-10-23T15:20")
        assertEquals(RunPhase.ACTIVE, sim.run("prayer-asr")!!.phase)
        sim.at("2026-10-23T15:30")
        assertNull(sim.run("prayer-dhuhr"))
        assertEquals("window_closed", sim.lastOf(EntryType.SKIPPED)!!.reason)
        assertEquals("reset", sim.state.offer!!.protocol)
        // The offer does not ask for a Dhuhr that does not come.
        assertEquals(
            "Jummah was missed. RESET brings the day back. Or NOT NOW: it costs nothing.",
            sim.lastEffects<Effect.Notify>().single { it.kind == NotifyKind.OFFER }.text,
        )
    }

    @Test
    fun aLateStartJoinsThePartThatIsDue() {
        // The phone was off at leave-by: on at 13:00, I'M AT UNI is asked at once.
        val travel = friday(start = "2026-10-23T13:00")
        assertEquals(JummahPart.TRAVEL, travel.part())
        assertTrue(travel.jummah().asking)
        assertEquals("fired", travel.state.day!!.handled.values.single())
        // On at 14:40: straight into the lock.
        val lock = friday(start = "2026-10-23T14:40")
        assertEquals(JummahPart.LOCK, lock.part())
        assertEquals(listOf(true), lock.dnd())
        assertEquals(rome("2026-10-23T15:20"), lock.jummah().lockEndsAt)
        // On after the missed time: nothing starts, and the self-check logs it.
        val over = friday(start = "2026-10-23T15:31")
        assertTrue(over.state.runs.none { it.jummah != null })
        over.at("2026-10-23T23:00", Input.GoingToSleep())
        assertEquals("jummah", over.of(EntryType.NOT_FIRED).single { it.protocol == "jummah" }.protocol)
        // Not on other days, even by hand.
        val monday = Sim()
        monday.step(Input.Manual("jummah"))
        assertTrue(monday.state.runs.isEmpty())
        assertEquals("not_friday", monday.lastOf(EntryType.SKIPPED)!!.reason)
    }

    @Test
    fun thursdayWindDownAddsThePrepToFriday() {
        val sim = Sim("2026-10-22T08:00")
        // Tonight's wind-down at bedtime - 60 (00:30 target): Friday's to-dos get the prep at once.
        sim.at("2026-10-22T23:30")
        val wind = sim.run("sleep")!!
        assertEquals(listOf(Todo("Jummah prep: clean clothes, bag packed.")), sim.state.todos["2026-10-23"])
        // The wind-down's own to-dos keep it.
        sim.step(Input.StepDone(wind.id, 0, "Good\nOkay\nFine"))
        sim.step(Input.StepDone(wind.id, 1, "Analysis\nPhysics"))
        assertEquals(
            listOf("Analysis", "Physics", "Jummah prep: clean clothes, bag packed."),
            sim.state.todos["2026-10-23"]!!.map { it.text },
        )
        sim.at("2026-10-23T00:20", Input.GoingToSleep())
        assertEquals(3, Machine.todos(sim.state, sim.env).size)
        // Not on other nights.
        val wed = Sim("2026-10-21T08:00")
        wed.at("2026-10-21T23:30")
        assertNull(wed.state.todos["2026-10-22"])
    }

    @Test
    fun theWindDownAfterMidnightStillAddsThePrep() {
        // Bedtime start 01:30: Thursday's wind-down starts at 00:30 on Friday's date, still Thursday's day.
        val sim = Sim("2026-10-22T08:00", times = MyTimes(bedtimeStart = "01:30"))
        sim.at("2026-10-23T00:30")
        assertEquals("2026-10-22", sim.state.day!!.key)
        assertNotNull(sim.run("sleep"))
        assertEquals(1, sim.state.todos["2026-10-23"]!!.size)
    }

    @Test
    fun aCallExtendsTheLock() {
        val sim = friday(MyTimes(), start = "2026-10-23T14:35")
        sim.at("2026-10-23T14:40", Input.CallActive(true))
        sim.at("2026-10-23T14:50", Input.CallActive(false))
        assertEquals(rome("2026-10-23T15:30"), sim.jummah().lockEndsAt)
        assertEquals(rome("2026-10-23T15:00"), sim.jummah().doneAfterAt)
        sim.at("2026-10-23T15:25")
        assertEquals(JummahPart.LOCK, sim.part())
        // The lock that ran past the missed time ends first; then Jummah is missed.
        sim.at("2026-10-23T15:31")
        assertTrue(sim.state.runs.none { it.jummah != null })
        assertEquals(listOf(true, false), sim.dnd())
        assertEquals("jummah", sim.lastOf(EntryType.MISSED)!!.protocol)
    }

    @Test
    fun quietSilencesTheLoudParts() {
        val sim = friday()
        sim.step(Input.Toggle(ToggleName.QUIET, true))
        sim.at("2026-10-23T11:45")
        assertTrue(Machine.isSilent(sim.state, sim.jummah()))
        sim.step(Input.Toggle(ToggleName.QUIET, false))
        assertFalse(Machine.isSilent(sim.state, sim.jummah()))
    }

    @Test
    fun theKillSwitchGivesTheSoundBackAndJummahGoesOnAfter() {
        val sim = friday(MyTimes(), start = "2026-10-23T14:35")
        sim.at("2026-10-23T14:40", Input.Suspend(60))
        assertEquals(listOf(true, false), sim.dnd())
        assertEquals(JummahPart.CONFIRM, sim.part())
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertFalse(Machine.dndWanted(sim.state, sim.env))
        // CONFIRM still counts.
        sim.at("2026-10-23T15:10", Input.Confirm(sim.jummah().id))
        assertEquals(DayState.JUMMAH_CONFIRMED, sim.state.day!!.jummah)
        // Suspended at leave-by: Jummah starts when the suspension ends, and the lock comes as usual.
        val early = friday(MyTimes(), start = "2026-10-23T13:00")
        early.step(Input.Suspend(30))
        early.at("2026-10-23T13:20")
        assertTrue(early.state.runs.none { it.jummah != null })
        early.at("2026-10-23T13:31")
        assertEquals(JummahPart.LEAVE, early.part())
        // Suspended in the leave-by takeover: the screen is free; the lock still comes.
        early.at("2026-10-23T13:40", Input.Suspend(10))
        assertEquals(JummahPart.TRAVEL, early.part())
        assertNull(Machine.activeRun(early.state, early.env))
        early.at("2026-10-23T14:35")
        assertEquals(JummahPart.LOCK, early.part())
    }

    @Test
    fun alwaysStageOneAndNoStepDown() {
        val sim = friday()
        sim.state = sim.state.copy(stages = mapOf("jummah" to StageMemory(3)))
        sim.at("2026-10-23T11:45")
        val r = sim.jummah()
        assertEquals(1, r.stage)
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(1, Machine.status(sim.state, sim.env).stages["jummah"])
        sim.step(Input.NotOkay(r.id))
        assertEquals(Version.FULL, sim.jummah().version)
        assertTrue(sim.of(EntryType.STEPPED_DOWN).isEmpty())
    }

    @Test
    fun leavingIsABailOutAndJummahGoesOn() {
        val sim = friday(MyTimes())
        sim.at("2026-10-23T13:20")
        sim.after(1, Input.BailOut(sim.jummah().id))
        assertEquals(JummahPart.TRAVEL, sim.part())
        assertEquals(120, sim.lastOf(EntryType.PENALTY)!!.minutes)
        assertEquals(1, sim.state.missesInARow)
        // LEAVE on the I'M AT UNI ask only closes it.
        sim.at("2026-10-23T14:20")
        sim.step(Input.BailOut(sim.jummah().id))
        assertFalse(sim.jummah().asking)
        assertEquals(1, sim.of(EntryType.PENALTY).size)
        // LEAVE in the lock: the sound comes back, the usual penalty, but the outcome counts once.
        sim.at("2026-10-23T14:36", Input.BailOut(sim.jummah().id))
        assertEquals(JummahPart.CONFIRM, sim.part())
        assertEquals(listOf(true, false), sim.dnd())
        assertEquals(2, sim.of(EntryType.PENALTY).size)
        assertEquals(1, sim.state.missesInARow)
        assertEquals(StageMemory(1), sim.state.stages["jummah"])
        // CONFIRM still counts after leaving.
        sim.at("2026-10-23T15:00", Input.Confirm(sim.jummah().id))
        assertEquals("jummah", sim.lastOf(EntryType.CONFIRMED)!!.prayer)
    }

    @Test
    fun theDayEndNeverLeavesDoNotDisturbOn() {
        val sim = friday(MyTimes(), start = "2026-10-23T14:35")
        sim.at("2026-10-23T14:40", Input.GoingToSleep())
        assertTrue(sim.state.runs.none { it.jummah != null })
        assertEquals(listOf(true, false), sim.dnd())
        assertEquals("day_end", sim.of(EntryType.MISSED).single { it.protocol == "jummah" }.reason)
    }

    @Test
    fun afterTheClockChange() {
        // Friday 30 October, after the clocks went back: the same local times.
        val sim = Sim("2026-10-30T08:00", times = classes)
        sim.at("2026-10-30T11:45")
        assertEquals(rome("2026-10-30T12:45"), sim.jummah().jummah!!.arriveAt)
        assertEquals(rome("2026-10-30T14:35"), sim.jummah().jummah!!.lockAt)
        sim.at("2026-10-30T14:35")
        assertEquals(JummahPart.LOCK, sim.part())
    }

    @Test
    fun testPartsCountForNothing() {
        val sim = Sim()
        sim.step(Input.TestJummah(JummahTestPart.LEAVE))
        val leave = sim.jummah()
        assertTrue(leave.test)
        assertEquals(JummahPart.LEAVE, sim.part())
        for (i in 0..3) sim.step(Input.StepDone(leave.id, i))
        // The test ends with its part; the walking switch is left alone.
        assertTrue(sim.state.runs.none { it.jummah != null })
        assertFalse(sim.state.toggles.walking)
        assertEquals("completed", sim.entries.last { it.run == leave.id }.type)
        sim.step(Input.TestJummah(JummahTestPart.ARRIVAL))
        val arrival = sim.jummah()
        assertTrue(arrival.asking)
        sim.step(Input.Confirm(arrival.id))
        assertTrue(sim.state.runs.none { it.jummah != null })
        sim.step(Input.TestJummah(JummahTestPart.CARD))
        assertEquals(NotifyKind.CARD, sim.lastEffects<Effect.Notify>().single().kind)
        assertEquals(true, sim.lastOf(EntryType.JUMMAH_CARD)!!.test)
        sim.step(Input.TestJummah(JummahTestPart.LOCK))
        val lock = sim.jummah()
        assertEquals(JummahPart.LOCK, sim.part())
        assertEquals(listOf(true), sim.dnd())
        sim.after(1, Input.BailOut(lock.id))
        assertEquals(listOf(true, false), sim.dnd())
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
        assertEquals(0, sim.state.missesInARow)
        // The TEST lock ends with the lock (LEAVE, or its end): no CONFIRM afterwards, nothing "missed".
        assertTrue(sim.state.runs.none { it.jummah != null })
        sim.step(Input.TestJummah(JummahTestPart.LOCK))
        val full = sim.jummah()
        sim.after(45)
        assertTrue(sim.state.runs.none { it.jummah != null })
        assertEquals(listOf(true, false, true, false), sim.dnd())
        sim.after(30)
        assertTrue(sim.of(EntryType.MISSED).isEmpty())
        assertTrue(sim.entries.filter { it.run in setOf(leave.id, arrival.id, lock.id, full.id) }.all { it.test == true })
    }

    @Test
    fun noDoNotDisturbAccessIsJournaled() {
        val sim = friday(MyTimes(), start = "2026-10-23T14:35")
        sim.step(Input.DndNoAccess)
        assertEquals("no_access", sim.lastOf(EntryType.DND)!!.reason)
        // The lock holds all the same.
        assertEquals(JummahPart.LOCK, sim.part())
        assertEquals(sim.jummah(), Machine.activeRun(sim.state, sim.env))
    }

    @Test
    fun stateRoundTripAndOldState() {
        val sim = friday(start = "2026-10-23T12:46")
        sim.at("2026-10-23T14:35")
        val text = sim.state.encode()
        assertTrue(text.contains("\"part\":\"lock\""))
        assertEquals(sim.state, MachineState.decode(text))
        // A state saved before M4 (no jummah, asking, promise or offer fields) still reads.
        val old = Sim()
        val oldText = old.state.encode()
            .replace(",\"offer\":null", "").replace(",\"jummah\":null", "")
        assertFalse(oldText.contains("\"offer\""))
        assertEquals(old.state, MachineState.decode(oldText))
    }
}
