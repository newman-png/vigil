package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.journal.Report
import dev.vigil.core.journal.ReportMeta
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.GymSet
import dev.vigil.core.run.RunPhase
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol 7: the gym (design M7), with the bundled `gym` protocol: logging, the rest timer, the pre-fill. */
class GymTest {
    private val full = M7_FILE

    /** Monday and Thursday at 18:00, a 60-minute workout. */
    private val times = MyTimes(gym = listOf(Slot(listOf("mon", "thu"), "18:00", minutes = 60)))

    private fun Sim.gym() = run("gym")!!

    private fun Sim.workout() = Machine.stepView(state, runId("gym"), env)!!.workout!!

    private fun Sim.exercise(name: String) = workout().exercises.single { it.name == name }

    /** Changes, packs and leaves: the workout step is next. */
    private fun Sim.toTheGym() {
        for (i in 0..2) step(Input.StepDone(runId("gym"), i))
    }

    private fun Sim.log(name: String, reps: Int, weight: Double, rest: Int? = null) =
        step(Input.LogSet(runId("gym"), name, reps, weight, rest))

    @Test
    fun aWorkoutAsTheSpecSays() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T18:00")
        val r = sim.gym()
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(LockType.POOL, r.lock)
        assertEquals(60, r.lengthMin)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals("GYM · POOL LOCKED", sim.lastEffects<Effect.Notify>().single { it.runId == r.id }.title)
        assertTrue((sim.decision() as PoolDecision.Blocked).blocks.any { it.reason == BlockReason.RUN_LOCK })
        assertEquals(
            listOf(StepType.CHECK to null, StepType.CHECK to null, StepType.CHECK to null, StepType.WORKOUT to 60, StepType.CHECK to null),
            Machine.stepsFor(r, sim.env).map { it.type to it.min },
        )
        sim.at("2026-10-19T18:10")
        sim.toTheGym()
        var w = sim.workout()
        assertEquals(listOf("Squat", "Bench press", "Barbell row", "Overhead press", "Romanian deadlift", "Lat pulldown"), w.exercises.map { it.name })
        assertEquals(60, w.plannedMin)
        assertEquals(rome("2026-10-19T19:10"), w.plannedEnd)
        assertNull(w.restUntil)
        assertNull(sim.exercise("Squat").reps)
        assertEquals(90, sim.exercise("Squat").restS)
        assertTrue(Machine.stepView(sim.state, r.id, sim.env)!!.canTap)
        // The first set: journaled, the pre-fill and the best move on, the rest timer starts (rest_s).
        sim.log("Squat", 5, 60.0)
        val first = sim.lastOf(EntryType.GYM_SET)!!
        assertEquals("Squat", first.exercise)
        assertEquals(5, first.reps)
        assertEquals(60.0, first.weight!!, 0.0)
        assertEquals("first time", first.detail)
        w = sim.workout()
        assertEquals(rome("2026-10-19T18:11:30"), w.restUntil)
        assertEquals("Squat", w.lastExercise)
        assertEquals(5, sim.exercise("Squat").reps)
        assertEquals(60.0, sim.exercise("Squat").weight!!, 0.0)
        // Another case is the same exercise; a rest set for it is kept for it.
        sim.after(2)
        sim.log("  squat ", 5, 62.5, rest = 120)
        assertEquals("Squat", sim.lastOf(EntryType.GYM_SET)!!.exercise)
        assertEquals("new best (was 5 x 60 kg)", sim.lastOf(EntryType.GYM_SET)!!.detail)
        assertEquals(sim.now.plusSeconds(120), sim.workout().restUntil)
        assertEquals(120, sim.exercise("Squat").restS)
        sim.after(3)
        sim.log("Squat", 4, 62.5)
        assertEquals("best 5 x 62.5 kg", sim.lastOf(EntryType.GYM_SET)!!.detail)
        assertEquals(sim.now.plusSeconds(120), sim.workout().restUntil)
        // Any name can be added; it joins the list.
        sim.after(3)
        sim.log("Farmer carry", 1, 40.0)
        assertEquals("Farmer carry", sim.workout().exercises.last().name)
        assertEquals(3, sim.exercise("Squat").sets.size)
        assertEquals(GymSet("Squat", 5, 62.5, rome("2026-10-19T18:12")), sim.exercise("Squat").best)
        // Nonsense is ignored.
        sim.log("Squat", 0, 60.0)
        sim.log("Squat", 5, -1.0)
        sim.log("   ", 5, 60.0)
        assertEquals(4, sim.of(EntryType.GYM_SET).size)
        sim.after(40, Input.FinishWorkout(r.id))
        val workout = sim.lastOf(EntryType.WORKOUT)!!
        assertEquals("4 sets of 2 exercises", workout.detail)
        assertEquals(48, workout.minutes)
        assertEquals(4, sim.gym().step)
        assertNull(Machine.stepView(sim.state, r.id, sim.env)!!.workout)
        // Sets are only logged on the workout.
        sim.log("Squat", 5, 60.0)
        assertEquals(4, sim.of(EntryType.GYM_SET).size)
        sim.after(20, Input.StepDone(r.id, 4))
        assertNull(sim.run("gym"))
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
        // Thursday: pre-filled with the last set, the rest it was given, the best so far.
        sim.at("2026-10-22T18:00")
        sim.toTheGym()
        assertEquals(4, sim.exercise("Squat").reps)
        assertEquals(62.5, sim.exercise("Squat").weight!!, 0.0)
        assertEquals(120, sim.exercise("Squat").restS)
        assertEquals(1, sim.exercise("Farmer carry").reps)
        assertTrue(sim.exercise("Squat").sets.isEmpty())
    }

    @Test
    fun aForgottenFinishEndsItAnHourAfterThePlannedMinutes() {
        // Without dinner at 19:30, the workout's own end is the next thing to wake for.
        val sim = Sim(file = fileWith(full.find("gym")!!, base = PRAYERS_ONLY), times = times)
        sim.at("2026-10-19T18:00")
        sim.toTheGym()
        sim.log("Squat", 5, 60.0)
        assertEquals(rome("2026-10-19T20:00"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T19:59")
        assertEquals(3, sim.gym().step)
        sim.at("2026-10-19T20:00")
        assertEquals(4, sim.gym().step)
        val w = sim.lastOf(EntryType.WORKOUT)!!
        assertEquals("auto", w.reason)
        assertEquals("2026-10-19T20:00:00+02:00", w.ts)
        // A call moves that end by its length.
        val call = Sim(file = full, times = times)
        call.at("2026-10-19T18:00")
        call.toTheGym()
        call.at("2026-10-19T18:30", Input.CallActive(true))
        call.at("2026-10-19T19:00", Input.CallActive(false))
        assertEquals(rome("2026-10-19T19:30"), call.workout().plannedEnd)
        call.at("2026-10-19T20:00")
        assertEquals(3, call.gym().step)
        call.at("2026-10-19T20:30")
        assertEquals(4, call.gym().step)
    }

    @Test
    fun tappingTheWorkoutStepFinishesIt() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T18:00")
        sim.toTheGym()
        sim.step(Input.StepDone(sim.runId("gym"), 3))
        assertEquals(4, sim.gym().step)
        assertEquals("0 sets of 0 exercises", sim.lastOf(EntryType.WORKOUT)!!.detail)
    }

    @Test
    fun stepDownsKeepTheSets() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T18:00")
        sim.toTheGym()
        sim.log("Squat", 5, 60.0)
        sim.step(Input.NotOkay(sim.runId("gym")))
        val r = sim.gym()
        assertEquals(Version.SHORT, r.version)
        assertEquals(0, r.step)
        sim.step(Input.StepDone(r.id, 0))
        // Short: a 20-minute session; the set logged before is still this workout's.
        assertEquals(20, sim.workout().plannedMin)
        assertEquals(1, sim.exercise("Squat").sets.size)
        sim.step(Input.NotOkay(r.id))
        assertEquals(Version.MINIMAL, sim.gym().version)
        assertEquals(listOf(StepType.TIMER to 10), Machine.stepsFor(sim.gym(), sim.env).map { it.type to it.min })
        sim.after(10)
        assertNull(sim.run("gym"))
    }

    @Test
    fun aTestRemembersNothingAndStartsWithoutARow() {
        val sim = Sim(file = full)
        sim.step(Input.Manual("gym", test = true))
        sim.toTheGym()
        assertEquals(60, sim.workout().plannedMin)
        sim.log("Squat", 5, 100.0, rest = 200)
        assertEquals(GymMemory(), sim.state.gym)
        assertEquals(5, sim.exercise("Squat").reps)
        // A row with an end instead of minutes: start to end.
        val row = Sim(file = full, times = MyTimes(gym = listOf(Slot(listOf("mon"), "17:00", "18:30"))))
        row.at("2026-10-19T17:00")
        assertEquals(90, row.gym().lengthMin)
    }

    @Test
    fun stagesSuspendAndTheClockChange() {
        // Stage 3: one reminder, no lock; DONE completes it.
        val reminder = Sim(file = full, times = times)
        reminder.state = reminder.state.copy(stages = reminder.state.stages + ("gym" to dev.vigil.core.run.StageMemory(3)))
        reminder.at("2026-10-19T18:00")
        assertEquals(RunPhase.REMINDER, reminder.gym().phase)
        assertEquals(LockType.NONE, reminder.gym().lock)
        reminder.step(Input.Confirm(reminder.runId("gym")))
        assertNull(reminder.run("gym"))
        // Stage 2: the workout is finished by ticking it in the checklist too.
        val guided = Sim(file = full, times = times)
        guided.state = guided.state.copy(stages = guided.state.stages + ("gym" to dev.vigil.core.run.StageMemory(2)))
        guided.at("2026-10-19T18:00")
        guided.toTheGym()
        guided.step(Input.StepDone(guided.runId("gym"), 3))
        assertEquals(4, guided.gym().step)
        // Suspended at gym time: nothing starts.
        val off = Sim(file = full, times = times)
        off.at("2026-10-19T17:50", Input.Suspend(60))
        off.at("2026-10-19T18:00")
        assertNull(off.run("gym"))
        // Sunday 25 October (the clocks went back at 03:00): the session at 10:00 CET, planned 45 minutes.
        val sunday = Sim("2026-10-25T08:00", file = full, times = MyTimes(gym = listOf(Slot(listOf("sun"), "10:00", minutes = 45))))
        sunday.at("2026-10-25T10:00")
        sunday.toTheGym()
        assertEquals(rome("2026-10-25T10:45"), sunday.workout().plannedEnd)
        sunday.at("2026-10-25T11:45")
        assertEquals("auto", sunday.lastOf(EntryType.WORKOUT)!!.reason)
    }

    @Test
    fun leavingIsABailOut() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T18:00")
        sim.step(Input.BailOut(sim.runId("gym")))
        assertNull(sim.run("gym"))
        assertEquals(120, sim.lastOf(EntryType.PENALTY)!!.minutes)
    }

    @Test
    fun aWorkoutAfterMidnightAndAcrossEncodeDecode() {
        val late = MyTimes(gym = listOf(Slot(listOf("mon"), "23:00", minutes = 45)))
        val sim = Sim(file = fileWith(full.find("gym")!!, base = PRAYERS_ONLY), times = late)
        sim.at("2026-10-19T23:00")
        sim.toTheGym()
        sim.at("2026-10-20T00:10")
        sim.log("Bench press", 8, 50.0)
        val text = sim.state.encode()
        val decoded = MachineState.decode(text)
        assertEquals(sim.state, decoded)
        assertEquals("2026-10-19", decoded.day!!.key)
        assertEquals(GymSet("Bench press", 8, 50.0, rome("2026-10-20T00:10")), decoded.gym.last["Bench press"])
        assertEquals(1, decoded.runs.single().gym!!.sets.size)
    }

    @Test
    fun theReportShowsSetsAndProgress() {
        val sim = Sim(file = full, times = times)
        sim.at("2026-10-19T18:00")
        sim.toTheGym()
        sim.log("Squat", 5, 60.0)
        sim.log("Squat", 5, 60.0)
        sim.log("Pull-up", 8, 0.0)
        sim.step(Input.FinishWorkout(sim.runId("gym")))
        sim.at("2026-10-22T18:00")
        sim.toTheGym()
        sim.log("Squat", 5, 65.0)
        sim.log("Pull-up", 7, 0.0)
        sim.step(Input.FinishWorkout(sim.runId("gym")))
        val md = Report.markdown(sim.entries, ReportMeta(rome("2026-10-23T09:00"), ROME))
        assertTrue(md, md.contains("- Gym: 2 workouts, 5 sets"))
        assertTrue(md, md.contains("## Gym progress"))
        assertTrue(md, md.contains("- Squat: 5 x 65 kg on 2026-10-22 (previous best 5 x 60 kg: better)"))
        assertTrue(md, md.contains("- Pull-up: 7 reps on 2026-10-22 (previous best 8 reps)"))
        assertTrue(md, md.contains("- **Gym**: Squat 5 x 60 kg, 5 x 60 kg; Pull-up 8 reps"))
        assertTrue(Report.csv(sim.entries).contains(",Squat,5,65\r\n"))
        assertFalse(md.contains("Farmer"))
        assertNotNull(sim.lastOf(EntryType.WORKOUT))
    }
}
