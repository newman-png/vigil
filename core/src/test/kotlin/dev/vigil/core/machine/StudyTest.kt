package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.RunPhase
import dev.vigil.core.run.StageMemory
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol 5: the study block (design M6), with the bundled `study` protocol: rounds that fill the block. */
class StudyTest {
    private val full = BundledProtocols.file

    /** Monday 16:00 to 17:40: a 100-minute block of Analysis. */
    private val times = MyTimes(study = listOf(Slot(listOf("mon"), "16:00", "17:40", label = "Analysis")))

    private fun Sim.study() = run("study")!!

    private fun Sim.view() = Machine.stepView(state, runId("study"), env)!!

    /** The four setup checks (spot, notebook, book, material): none of them has minutes. */
    private fun Sim.setUp() {
        for (i in 0..3) step(Input.StepDone(runId("study"), i))
    }

    private fun Sim.stage(id: String, stage: Int) {
        state = state.copy(stages = state.stages + (id to StageMemory(stage)))
    }

    private fun Sim.minutes() = Machine.stepsFor(study(), env).map { it.type to it.min }

    @Test
    fun aMyTimesRowIsFilledWithRounds() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T16:00")
        val r = sim.study()
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(LockType.SCREEN, r.lock)
        assertEquals("slot:study", r.reason)
        assertEquals(100, r.lengthMin)
        assertEquals("Analysis", r.label)
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        assertEquals(Intensity.SUBTLE, Machine.intensity(r, sim.file))
        assertEquals(BlockReason.RUN_LOCK, (sim.decision() as PoolDecision.Blocked).reason)
        assertEquals(100, sim.lastOf(EntryType.FIRED)!!.minutes)
        // 100 minutes: work 50, break 10, work 40 (the last round cut to fit), then the closing line.
        assertEquals(
            listOf(
                StepType.CHECK to null, StepType.CHECK to null, StepType.CHECK to null, StepType.CHECK to null,
                StepType.TIMER to 50, StepType.TIMER to 10, StepType.TIMER to 40, StepType.TEXT to null,
            ),
            sim.minutes(),
        )
        assertEquals("Analysis", sim.view().label)
        assertNull(sim.view().round)
        sim.setUp()
        var v = sim.view()
        assertEquals(Round(1, 2, false), v.round)
        assertEquals(rome("2026-10-19T16:50"), v.timerEndsAt)
        assertFalse(v.canTap)
        assertTrue(v.step.chime)
        // A forced round cannot be tapped through.
        sim.step(Input.StepDone(r.id, 4))
        assertEquals(4, sim.study().step)
        sim.at("2026-10-19T16:50")
        v = sim.view()
        assertEquals(Round(1, 2, true), v.round)
        assertTrue(v.step.text.contains("drink water"))
        assertEquals(rome("2026-10-19T17:00"), v.timerEndsAt)
        sim.at("2026-10-19T17:00")
        assertEquals(Round(2, 2, false), sim.view().round)
        assertEquals(rome("2026-10-19T17:40"), sim.view().timerEndsAt)
        assertEquals(rome("2026-10-19T17:40"), Machine.nextWake(sim.state, sim.env))
        // The block's length reached: the closing line, then the block is over.
        sim.at("2026-10-19T17:40")
        assertEquals(StepType.TEXT, sim.view().step.type)
        assertNull(sim.view().round)
        assertEquals(r.id, Machine.activeRun(sim.state, sim.env)!!.id)
        sim.step(Input.StepDone(r.id, 7, ""))
        assertNotNull(sim.run("study"))
        sim.after(1, Input.StepDone(r.id, 7, "  Chapter 4, exercises 1-6 "))
        assertNull(sim.run("study"))
        val study = sim.lastOf(EntryType.STUDY)!!
        assertEquals(90, study.minutes)
        assertEquals("Analysis", study.detail)
        assertEquals("Chapter 4, exercises 1-6", study.text)
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
        assertNull(Machine.activeRun(sim.state, sim.env))
    }

    @Test
    fun theReportShowsTheBlock() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T16:00")
        sim.setUp()
        sim.at("2026-10-19T17:40", Input.StepDone(sim.runId("study"), 7, "Chapter 4"))
        val md = dev.vigil.core.journal.Report.markdown(sim.entries, dev.vigil.core.journal.ReportMeta(rome("2026-10-20T09:00"), ROME))
        assertTrue(md, md.contains("- Study: 1 block, 1 h 30 min of work"))
        assertTrue(md, md.contains("- **Study**: 17:40 Analysis, 90 min: \"Chapter 4\""))
        // The closing line has its own line, not a second one under Notes.
        assertFalse(md, md.contains("- **Notes**"))
    }

    @Test
    fun roundsAlwaysFillTheBlockExactly() {
        fun shape(minutes: Int): List<Int?> {
            val sim = Sim(file = full)
            sim.step(Input.Manual("study", minutes = minutes))
            return Machine.stepsFor(sim.study(), sim.env).filter { it.type == StepType.TIMER }.map { it.min }
        }
        assertEquals(listOf(50, 10, 50, 10, 30), shape(150))
        assertEquals(listOf(50), shape(50))
        // Too little left for a break and another round: the round takes it (no break at the end, no tiny round).
        assertEquals(listOf(60), shape(60))
        assertEquals(listOf(65), shape(65))
        assertEquals(listOf(50, 10, 6), shape(66))
        assertEquals(listOf(50, 10, 55), shape(115))
        assertEquals(listOf(20), shape(20))
        for (m in listOf(5, 37, 100, 149, 151, 299, 600)) assertEquals(m, shape(m).sumOf { it ?: 0 })
    }

    @Test
    fun startStudyFromHome() {
        val sim = Sim(file = full)
        val status = Machine.status(sim.state, sim.env)
        assertEquals("study", status.studyProtocol)
        assertEquals(listOf(50, 100, 150), status.studyLengths)
        assertEquals(100, status.studyDefault)
        sim.step(Input.Manual("study", minutes = 150, label = "  Physics: waves  "))
        val r = sim.study()
        assertEquals("manual", r.reason)
        assertEquals(150, r.lengthMin)
        assertEquals("Physics: waves", r.label)
        assertEquals(1, r.stage)
        // One block at a time.
        sim.step(Input.Manual("study", minutes = 50))
        assertEquals("already_running", sim.lastOf(EntryType.SKIPPED)!!.reason)
        // Without a length: default_min; never longer than 600 minutes; a blank label is none.
        val other = Sim(file = full)
        other.step(Input.Manual("study", label = " "))
        assertEquals(100, other.study().lengthMin)
        assertNull(other.study().label)
        val long = Sim(file = full)
        long.step(Input.Manual("study", minutes = 900))
        assertEquals(600, long.study().lengthMin)
        // A row with minutes and no end: its minutes.
        val rowMinutes = Sim(file = full, times = MyTimes(study = listOf(Slot(listOf("mon"), "10:00", minutes = 75, label = "Chem"))))
        rowMinutes.at("2026-10-19T10:00")
        assertEquals(75, rowMinutes.study().lengthMin)
    }

    @Test
    fun aStepDownKeepsWhatIsLeftOfTheBlock() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T16:00")
        sim.setUp()
        sim.at("2026-10-19T16:30", Input.NotOkay(sim.runId("study")))
        var r = sim.study()
        assertEquals(Version.SHORT, r.version)
        assertTrue(r.steppedDown)
        // 30 of the 100 minutes are used: 25-minute rounds with 5-minute breaks fill the 70 left.
        assertEquals(70, r.lengthMin)
        assertEquals(
            listOf(StepType.TIMER to 25, StepType.TIMER to 5, StepType.TIMER to 25, StepType.TIMER to 5, StepType.TIMER to 10, StepType.TEXT to null),
            sim.minutes(),
        )
        assertEquals(Round(1, 3, false), sim.view().round)
        assertEquals(rome("2026-10-19T16:55"), sim.view().timerEndsAt)
        assertEquals("short", sim.lastOf(EntryType.STEPPED_DOWN)!!.version)
        // Minimal: one 25-minute round, nothing else.
        sim.step(Input.NotOkay(r.id))
        r = sim.study()
        assertEquals(Version.MINIMAL, r.version)
        assertEquals(listOf(StepType.TIMER to 25), sim.minutes())
        assertNull(sim.view().round)
        // There is no single version: a third tap changes nothing.
        sim.step(Input.NotOkay(r.id))
        assertEquals(Version.MINIMAL, sim.study().version)
        assertEquals(2, sim.of(EntryType.STEPPED_DOWN).size)
        sim.at("2026-10-19T16:55")
        assertNull(sim.run("study"))
        assertEquals(25, sim.lastOf(EntryType.STUDY)!!.minutes)
        assertEquals("done", sim.lastOf(EntryType.COMPLETED)!!.reason)
        // Stepping down once the rounds are over leaves only the closing line.
        val late = Sim(file = full, times = times)
        late.at("2026-10-19T16:00")
        late.setUp()
        late.at("2026-10-19T17:40", Input.NotOkay(late.runId("study")))
        assertEquals(0, late.study().lengthMin)
        assertEquals(listOf(StepType.TEXT to null), late.minutes())
    }

    @Test
    fun aCallPausesTheRound() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T16:00")
        sim.setUp()
        sim.at("2026-10-19T16:10", Input.CallActive(true))
        sim.at("2026-10-19T16:50")
        assertEquals(Round(1, 2, false), sim.view().round)
        sim.at("2026-10-19T16:55", Input.CallActive(false))
        // The 45 minutes of the call are added: the first round ends at 17:35.
        assertEquals(rome("2026-10-19T17:35"), sim.view().timerEndsAt)
        sim.at("2026-10-19T17:35")
        assertEquals(Round(1, 2, true), sim.view().round)
    }

    @Test
    fun aPrayerPausesTheBlockAndItGoesOnAfter() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T16:00")
        sim.setUp()
        sim.at("2026-10-19T16:20", Input.Adhan(Prayer.ASR))
        assertEquals(RunPhase.PAUSED, sim.study().phase)
        assertEquals("prayer-asr", Machine.activeRun(sim.state, sim.env)!!.protocol)
        sim.at("2026-10-19T16:28", Input.Confirm(sim.runId("prayer-asr")))
        sim.at("2026-10-19T16:40")
        assertNull(sim.run("prayer-asr"))
        assertEquals(RunPhase.ACTIVE, sim.study().phase)
        assertEquals(sim.study(), Machine.activeRun(sim.state, sim.env))
        // Paused for 20 minutes: the round ends 20 minutes later.
        assertEquals(rome("2026-10-19T17:10"), sim.view().timerEndsAt)
    }

    @Test
    fun stageTwoIsAChecklistWhoseRoundsCanBeTicked() {
        val sim = Sim(file = full, times = times)
        sim.stage("study", 2)
        sim.at("2026-10-19T16:00")
        val r = sim.study()
        assertFalse(Machine.reasserts(r))
        sim.setUp()
        assertTrue(sim.view().canTap)
        sim.step(Input.StepDone(r.id, 4))
        assertEquals(Round(1, 2, true), sim.view().round)
    }

    @Test
    fun stageThreeIsOneReminder() {
        val sim = Sim(file = full, times = times)
        sim.stage("study", 3)
        sim.at("2026-10-19T16:00")
        val r = sim.study()
        assertEquals(RunPhase.REMINDER, r.phase)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals(NotifyKind.REMINDER, sim.lastEffects<Effect.Notify>().single().kind)
        sim.step(Input.Confirm(r.id))
        assertNull(sim.run("study"))
        assertEquals("Analysis", sim.lastOf(EntryType.STUDY)!!.detail)
    }

    @Test
    fun leavingIsABailOutAndTheKillSwitchDropsIt() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T16:00")
        sim.step(Input.BailOut(sim.runId("study")))
        assertNull(sim.run("study"))
        assertEquals("study", sim.lastOf(EntryType.BAILED)!!.protocol)
        assertEquals(120, sim.lastOf(EntryType.PENALTY)!!.minutes)
        assertTrue(sim.of(EntryType.STUDY).isEmpty())
        val killed = Sim(file = full, times = times)
        killed.at("2026-10-19T16:00")
        killed.step(Input.Suspend(60))
        assertNull(killed.run("study"))
        assertEquals("kill_switch", killed.lastOf(EntryType.DROPPED)!!.reason)
    }

    @Test
    fun quietOnlySilencesIt() {
        val sim = Sim(file = full, times = times)
        sim.step(Input.Toggle(ToggleName.QUIET, true))
        sim.at("2026-10-19T16:00")
        val r = sim.study()
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertTrue(Machine.isSilent(sim.state, r))
        // Nothing turns QUIET on by itself.
        val other = Sim(file = full, times = times)
        other.at("2026-10-19T16:00")
        assertFalse(other.state.toggles.quiet)
    }

    @Test
    fun aBlockAfterMidnightBelongsToTheDay() {
        val sim = Sim(file = fileWith(full.find("study")!!, base = PRAYERS_ONLY), times = MyTimes(study = listOf(Slot(listOf("mon"), "00:30", minutes = 60, label = "Night"))))
        sim.at("2026-10-20T00:30")
        val r = sim.study()
        assertEquals("2026-10-19", sim.state.day!!.key)
        assertEquals("mon", r.weekday)
        assertEquals(rome("2026-10-20T00:30"), r.planned)
        sim.setUp()
        assertEquals(listOf(60), Machine.stepsFor(r, sim.env).filter { it.type == StepType.TIMER }.map { it.min })
    }

    @Test
    fun aBlockAcrossTheClockChangeEndsOnTheClock() {
        // Saturday's row 01:30-03:30 is the night of the 25th: the clocks go back at 03:00, so the block is 3 hours.
        val night = MyTimes(study = listOf(Slot(listOf("sat"), "01:30", "03:30", label = "Night owl")))
        val sim = Sim("2026-10-24T08:00", file = fileWith(full.find("study")!!, base = PRAYERS_ONLY), times = night)
        sim.at("2026-10-25T01:30")
        assertEquals(180, sim.study().lengthMin)
        sim.setUp()
        assertEquals(listOf(50, 10, 50, 10, 60), Machine.stepsFor(sim.study(), sim.env).filter { it.type == StepType.TIMER }.map { it.min })
        sim.at("2026-10-25T03:29")
        assertEquals(Round(3, 3, false), sim.view().round)
        sim.at("2026-10-25T03:30")
        assertEquals(StepType.TEXT, sim.view().step.type)
    }

    @Test
    fun aRunningBlockSurvivesEncodeDecodeAndOldStateStillReads() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T16:00")
        sim.setUp()
        sim.at("2026-10-19T16:20")
        val text = sim.state.encode()
        assertTrue(text.contains("\"length_min\":100"))
        val decoded = MachineState.decode(text)
        assertEquals(sim.state, decoded)
        val o = Machine.step(decoded, Input.Tick, MachineEnv(rome("2026-10-19T16:50"), ROME, full))
        assertEquals(Round(1, 2, true), Machine.stepView(o.state, sim.runId("study"), MachineEnv(rome("2026-10-19T16:50"), ROME, full))!!.round)
        // A state saved before M6 has no length, label, gym, deferred or memories: they read as their defaults, and a
        // run without a length fills default_min.
        val root = Json.parseToJsonElement(text).jsonObject
        val runs = (root["runs"] as JsonArray).map { JsonObject(it.jsonObject - "length_min" - "label" - "gym") }
        val day = JsonObject(root["day"]!!.jsonObject - "deferred")
        val old = JsonObject(root - "attention" - "gym" + ("runs" to JsonArray(runs)) + ("day" to day)).toString()
        val read = MachineState.decode(old)
        assertNull(read.runs.single().lengthMin)
        assertNull(read.runs.single().label)
        assertEquals(AttentionMemory(), read.attention)
        assertEquals(GymMemory(), read.gym)
        assertEquals(emptyList<Deferred>(), read.day!!.deferred)
        assertEquals(100, Machine.stepsFor(read.runs.single(), sim.env).filter { it.type == StepType.TIMER }.sumOf { it.min ?: 0 })
    }
}
