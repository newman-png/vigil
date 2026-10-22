package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.RunPhase
import dev.vigil.core.run.StageMemory
import dev.vigil.core.times.MyTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** M2: the wind-down, sleep shifting, the night lock and tomorrow's to-dos, with the bundled sleep protocol. */
class SleepTest {
    private val sleep = "sleep"

    private fun Sim.night(): dev.vigil.core.run.Run? = state.runs.firstOrNull { it.phase == RunPhase.NIGHT }

    private fun Sim.blocks(): List<BlockReason> = (decision() as? PoolDecision.Blocked)?.blocks?.map { it.reason } ?: emptyList()

    /** Taps through the wind-down up to its GOING TO SLEEP step (prep and hygiene take their 10 minutes each). */
    private fun Sim.windDown(todos: String = "Task one\nTask two\n\nFirst block 09:00") {
        val id = runId(sleep)
        step(Input.StepDone(id, 0, "Good\nTired\nDone"))
        after(1, Input.StepDone(id, 1, todos))
        after(10, Input.StepDone(id, 2))
        after(10, Input.StepDone(id, 3))
        after(1, Input.StepDone(id, 4))
        after(1, Input.StepDone(id, 5))
    }

    @Test
    fun windDownAnHourBeforeTheTargetThenGoingToSleepStartsTheNightLock() {
        val sim = Sim()
        // The first night's target is MY TIMES bedtime_start (00:30): wind-down at 23:30.
        assertEquals(rome("2026-10-20T00:30"), Machine.status(sim.state, sim.env).bedtime)
        assertEquals("start", sim.lastOf(EntryType.SLEEP_TARGET)!!.reason)
        sim.at("2026-10-19T23:30")
        val r = sim.run(sleep)!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == r.id })
        assertEquals(listOf(BlockReason.RUN_LOCK), sim.blocks().take(1))
        sim.windDown()
        val view = Machine.stepView(sim.state, r.id, sim.env)!!
        assertEquals(6, view.index)
        assertTrue(view.goingToSleep)
        assertNull(view.ishaToConfirm)
        sim.after(1, Input.StepDone(r.id, 6))
        // The day ends at GOING TO SLEEP (23:54), the wind-down completes clean, the night lock begins.
        val end = sim.lastOf(EntryType.DAY_END)!!
        assertEquals("sleep_tap", end.reason)
        assertEquals("2026-10-19T23:54:00+02:00", end.ts)
        assertEquals("clean", sim.of(EntryType.COMPLETED).single { it.protocol == sleep }.reason)
        val night = sim.night()!!
        assertEquals(rome("2026-10-20T09:00"), night.lockEndsAt)
        assertEquals(night, Machine.activeRun(sim.state, sim.env))
        assertNull(Machine.stepView(sim.state, night.id, sim.env))
        assertEquals(listOf(BlockReason.NIGHT_LOCK), sim.blocks())
        assertEquals("POOL BLOCKED · NIGHT LOCK", Machine.poolStatus(sim.state, sim.env))
        assertEquals("sleep_tap", sim.lastOf(EntryType.NIGHT_LOCK)!!.reason)
        // Slept 36 minutes before the target: a good night, so tonight's target is 20 minutes earlier.
        val slept = sim.lastOf(EntryType.SLEEP)!!
        assertEquals(-36, slept.minutes)
        assertTrue(sim.of(EntryType.NIGHT_MISSED).isEmpty())
        val target = sim.lastOf(EntryType.SLEEP_TARGET)!!
        assertEquals("shifted", target.reason)
        assertEquals(20, target.minutes)
        assertEquals("2026-10-21T00:10:00+02:00", target.scheduled)
        assertEquals("00:10", sim.state.sleep.target)
        // The tomorrow lines are the new day's to-dos (blank lines dropped).
        val status = Machine.status(sim.state, sim.env)
        assertEquals(listOf("Task one", "Task two", "First block 09:00"), status.todos.map { it.text })
        assertEquals("2026-10-20", sim.lastOf(EntryType.TODO_SET)!!.day)
        assertEquals(rome("2026-10-20T09:00"), status.nightLockUntil)
        assertEquals(rome("2026-10-20T07:00"), status.wakeAlarmAt)
        assertEquals(rome("2026-10-21T00:10"), status.bedtime)
        assertEquals(rome("2026-10-20T07:00"), Machine.nextWake(sim.state, sim.env))
        assertEquals(rome("2026-10-20T07:00"), Machine.wakeAlarmAt(sim.state, sim.env))
    }

    @Test
    fun aHomeTapDuringTheWindDownCompletesItButNotClean() {
        val sim = Sim()
        sim.at("2026-10-19T23:30")
        sim.after(2, Input.GoingToSleep())
        val done = sim.of(EntryType.COMPLETED).single { it.protocol == sleep }
        assertEquals("done", done.reason)
        assertEquals("going_to_sleep", sim.of(EntryType.STEP_DONE).last { it.protocol == sleep }.reason)
        assertNotNull(sim.night())
        // Before the wind-down a tap just ends the day and starts the night lock.
        val early = Sim()
        early.at("2026-10-19T22:45", Input.GoingToSleep())
        assertEquals("sleep_tap", early.lastOf(EntryType.DAY_END)!!.reason)
        assertNull(early.run(sleep).takeIf { it?.phase != RunPhase.NIGHT })
        assertNotNull(early.night())
    }

    @Test
    fun missedNightsHoldTheTargetAndAGoodNightShiftsAgain() {
        val sim = Sim()
        // Night 1 and night 2 end at the fallback without GOING TO SLEEP: missed.
        sim.at("2026-10-20T06:00")
        sim.at("2026-10-21T06:00")
        assertEquals(listOf("no_sleep_tap", "no_sleep_tap"), sim.of(EntryType.NIGHT_MISSED).map { it.reason })
        // Night 3: GOING TO SLEEP before the target.
        sim.at("2026-10-21T23:00", Input.GoingToSleep())
        val targets = sim.of(EntryType.SLEEP_TARGET)
        assertEquals(listOf("start", "shifted", "held", "shifted"), targets.map { it.reason })
        assertEquals(listOf(0, 20, 0, 20), targets.map { it.minutes })
        assertEquals(
            listOf("2026-10-20T00:30:00+02:00", "2026-10-21T00:10:00+02:00", "2026-10-22T00:10:00+02:00", "2026-10-22T23:50:00+02:00"),
            targets.map { it.scheduled },
        )
        assertEquals(SleepState("23:50", 0), sim.state.sleep)
        // The wind-down that never fired (phone asleep) and the alarms that never rang are in the self-check.
        assertTrue(sim.of(EntryType.NOT_FIRED).any { it.protocol == sleep })
        assertTrue(sim.of(EntryType.NOT_FIRED).any { it.reason == "wake_alarm" })
    }

    @Test
    fun theTargetStopsAtTheGoalAndALateNightIsMissed() {
        val sim = Sim()
        sim.state = sim.state.copy(sleep = SleepState("23:10", 0))
        sim.at("2026-10-19T23:00", Input.GoingToSleep())
        assertEquals("23:00", sim.state.sleep.target)
        assertEquals(10, sim.lastOf(EntryType.SLEEP_TARGET)!!.minutes)
        sim.at("2026-10-20T22:30", Input.GoingToSleep())
        assertEquals("goal", sim.lastOf(EntryType.SLEEP_TARGET)!!.reason)
        assertEquals("23:00", sim.state.sleep.target)
        // 30 minutes late is still good; 31 is a missed night.
        val ok = Sim()
        ok.at("2026-10-20T01:00", Input.GoingToSleep())
        assertTrue(ok.of(EntryType.NIGHT_MISSED).isEmpty())
        val late = Sim()
        late.at("2026-10-20T01:01", Input.GoingToSleep())
        val missed = late.lastOf(EntryType.NIGHT_MISSED)!!
        assertEquals("late", missed.reason)
        assertEquals(1, late.state.sleep.missedInARow)
        // One missed night still shifts; the second in a row holds.
        assertEquals("00:10", late.state.sleep.target)
    }

    @Test
    fun aMyTimesChangeKeepsTheTargetInsideTheNewRange() {
        val sim = Sim()
        sim.state = sim.state.copy(sleep = SleepState("00:10", 0))
        sim.step(Input.EditMyTimes(MyTimes(bedtimeGoal = "22:30", bedtimeStart = "23:30")))
        // Today still uses the old times.
        assertEquals(rome("2026-10-20T00:10"), Machine.status(sim.state, sim.env).bedtime)
        sim.at("2026-10-19T23:00", Input.GoingToSleep())
        val target = sim.lastOf(EntryType.SLEEP_TARGET)!!
        assertEquals("clamped", target.reason)
        assertEquals("23:30", sim.state.sleep.target)
        assertEquals(40, target.minutes)
        // A start not later than the goal keeps the target at the start.
        val odd = Sim(times = MyTimes(bedtimeGoal = "23:30", bedtimeStart = "23:00"))
        assertEquals(rome("2026-10-19T23:00"), Machine.status(odd.state, odd.env).bedtime)
    }

    @Test
    fun leavingTheWindDownIsAMissedNight() {
        val sim = Sim()
        sim.at("2026-10-19T23:30")
        sim.step(Input.BailOut(sim.runId(sleep)))
        assertEquals(120, sim.lastOf(EntryType.PENALTY)!!.minutes)
        assertTrue(sim.state.day!!.sleepBailed)
        sim.after(10, Input.GoingToSleep())
        assertEquals("bailed", sim.lastOf(EntryType.NIGHT_MISSED)!!.reason)
        assertNotNull(sim.night())
        assertEquals(1, sim.state.sleep.missedInARow)
    }

    @Test
    fun anIdleEndAfterWindDownStartsTheNightLockWhenThePhoneWasPutDown() {
        val sim = Sim()
        sim.at("2026-10-19T23:30")
        sim.at("2026-10-19T23:35", Input.Screen(on = false))
        sim.at("2026-10-20T01:35")
        assertEquals("idle", sim.lastOf(EntryType.DAY_END)!!.reason)
        assertEquals("day_end", sim.of(EntryType.MISSED).single { it.protocol == sleep }.reason)
        val slept = sim.lastOf(EntryType.SLEEP)!!
        assertEquals("idle", slept.reason)
        assertEquals("2026-10-19T23:35:00+02:00", slept.ts)
        assertEquals("no_sleep_tap", sim.lastOf(EntryType.NIGHT_MISSED)!!.reason)
        val night = sim.night()!!
        assertEquals(rome("2026-10-19T23:35"), night.firedAt)
        assertEquals("idle", sim.lastOf(EntryType.NIGHT_LOCK)!!.reason)
        // Shown to the app (which never wakes the screen for it).
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == night.id })
        // A fallback end without a wind-down starts no night lock.
        val none = Sim()
        none.at("2026-10-20T06:00")
        assertNull(none.night())
    }

    @Test
    fun fajrTakesTheScreenFromTheNightLockWhichComesBackAfter() {
        val sim = Sim()
        sim.state = sim.state.copy(prayers = sim.state.prayers.copy(lastAdhan = mapOf(Prayer.FAJR to rome("2026-10-19T05:40"))))
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        val night = sim.night()!!
        sim.at("2026-10-20T05:41", Input.Adhan(Prayer.FAJR))
        val fajr = sim.run("prayer-fajr")!!
        assertEquals(RunPhase.ACTIVE, fajr.phase)
        assertEquals(fajr, Machine.activeRun(sim.state, sim.env))
        assertEquals("takes the screen from the night lock", sim.lastOf(EntryType.FIRED)!!.detail)
        assertEquals(listOf(BlockReason.RUN_LOCK, BlockReason.NIGHT_LOCK), sim.blocks().take(2))
        sim.step(Input.StepDone(fajr.id, 0))
        sim.at("2026-10-20T05:49", Input.StepDone(fajr.id, 1))
        sim.at("2026-10-20T06:01")
        assertNull(sim.run("prayer-fajr"))
        assertEquals(night.id, Machine.activeRun(sim.state, sim.env)!!.id)
        // The night lock is no day's: the day that began at GOING TO SLEEP goes on.
        assertEquals("2026-10-20", sim.state.day!!.key)
    }

    @Test
    fun theNightLockEndsByItselfAtWakePlusGrace() {
        val sim = Sim()
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        sim.at("2026-10-20T07:00")
        assertTrue(sim.lastEffects<Effect.RingWakeAlarm>().isNotEmpty())
        sim.at("2026-10-20T09:00")
        assertNull(sim.night())
        val end = sim.lastOf(EntryType.NIGHT_LOCK)!!
        assertEquals("timeout", end.reason)
        assertEquals("2026-10-20T09:00:00+02:00", end.ts)
        assertTrue(BlockReason.NIGHT_LOCK !in sim.blocks())
        assertTrue(sim.lastEffects<Effect.Audio>().contains(Effect.Audio(Effect.Audio.STOP, Effect.Audio.WHITE_NOISE)))
    }

    @Test
    fun leavingTheNightLockCostsThePenaltyAndTheKillSwitchEndsIt() {
        val sim = Sim()
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        val night = sim.night()!!
        sim.at("2026-10-20T01:00", Input.BailOut(night.id))
        assertNull(sim.night())
        assertEquals("left", sim.lastOf(EntryType.NIGHT_LOCK)!!.reason)
        assertEquals(sleep, sim.lastOf(EntryType.PENALTY)!!.protocol)
        assertEquals(listOf(BlockReason.PENALTY), sim.blocks())
        // The night lock counts for no stage.
        assertNull(sim.state.stages[sleep]?.recent?.lastOrNull { it == "bailed" })
        val killed = Sim()
        killed.at("2026-10-19T23:30", Input.GoingToSleep())
        killed.after(5, Input.Suspend(60))
        assertNull(killed.night())
        assertEquals("kill_switch", killed.lastOf(EntryType.NIGHT_LOCK)!!.reason)
        assertNull(Machine.activeRun(killed.state, killed.env))
        // Suspended at GOING TO SLEEP: no night lock at all.
        val suspended = Sim()
        suspended.at("2026-10-19T23:00", Input.Suspend(60))
        suspended.after(10, Input.GoingToSleep())
        assertNull(suspended.night())
    }

    @Test
    fun ishaStillOpenAtGoingToSleepIsAskedAboutAndAYesCounts() {
        val sim = Sim()
        sim.at("2026-10-19T19:40", Input.Adhan(Prayer.ISHA))
        sim.at("2026-10-19T20:00")
        val isha = sim.run("prayer-isha")!!
        assertEquals(RunPhase.OPEN, isha.phase)
        assertEquals(isha.id, Machine.status(sim.state, sim.env).ishaToConfirm?.id)
        sim.at("2026-10-19T23:30")
        sim.windDown()
        assertEquals(isha.id, Machine.stepView(sim.state, sim.runId(sleep), sim.env)!!.ishaToConfirm)
        sim.after(1, Input.GoingToSleep(ishaPrayed = true))
        assertEquals("prayer-isha", sim.of(EntryType.CONFIRMED).single().protocol)
        assertTrue(sim.of(EntryType.MISSED).none { it.prayer == "isha" })
        assertTrue(sim.of(EntryType.COMPLETED).any { it.protocol == "prayer-isha" })
        // Even inside its done lock (the adhan 2 minutes ago), a yes at GOING TO SLEEP counts.
        val fresh = Sim()
        fresh.at("2026-10-19T23:20", Input.Adhan(Prayer.ISHA))
        fresh.after(2, Input.GoingToSleep(ishaPrayed = true))
        assertTrue(fresh.of(EntryType.COMPLETED).any { it.protocol == "prayer-isha" })
        // Without the yes it is missed, as before.
        val no = Sim()
        no.at("2026-10-19T19:40", Input.Adhan(Prayer.ISHA))
        no.at("2026-10-19T22:00", Input.GoingToSleep())
        assertEquals("day_end", no.of(EntryType.MISSED).single { it.prayer == "isha" }.reason)
    }

    @Test
    fun goingToSleepBeforeTheWakeTimeIsIgnoredAndHomeSaysWhy() {
        val sim = Sim()
        assertNull(Machine.status(sim.state, sim.env).sleepTapIgnored)
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        sim.at("2026-10-20T03:00", Input.BailOut(sim.night()!!.id))
        val why = Machine.status(sim.state, sim.env).sleepTapIgnored!!
        assertEquals("BEFORE TODAY'S WAKE TIME (07:00): THIS DAY BEGAN AT 23:30 AND GOES ON.", why)
        sim.step(Input.GoingToSleep())
        assertEquals("sleep_tap", sim.lastOf(EntryType.SKIPPED)!!.reason)
        assertEquals("2026-10-20", sim.state.day!!.key)
        assertNull(sim.night())
        // A wind-down started by hand that night still finishes at its last step (the day goes on).
        sim.step(Input.Manual(sleep))
        val id = sim.runId(sleep)
        repeat(4) { sim.step(Input.NotOkay(id)) }
        assertEquals(Version.SINGLE, sim.run(sleep)!!.version)
        sim.step(Input.StepDone(id, 0))
        assertNull(sim.run(sleep))
        assertEquals("2026-10-20", sim.state.day!!.key)
    }

    @Test
    fun goingToSleepJustAfterWakingStartsNoNightLock() {
        val sim = Sim("2026-10-19T06:30")
        sim.at("2026-10-19T07:30", Input.GoingToSleep())
        assertEquals("sleep_tap", sim.lastOf(EntryType.DAY_END)!!.reason)
        assertNull(sim.night())
    }

    @Test
    fun aPrayerPausesTheWindDownAndItGoesOnWhereItWas() {
        val sim = Sim()
        sim.at("2026-10-19T23:30")
        val id = sim.runId(sleep)
        sim.step(Input.StepDone(id, 0, "a\nb\nc"))
        sim.after(1, Input.StepDone(id, 1, "x"))
        // Prep (check, 10 minutes) appeared at 23:31.
        assertEquals(rome("2026-10-19T23:41"), Machine.stepView(sim.state, id, sim.env)!!.lockedUntil)
        sim.at("2026-10-19T23:35", Input.Adhan(Prayer.ISHA))
        assertEquals(RunPhase.PAUSED, sim.run(sleep)!!.phase)
        assertEquals("prayer-isha", Machine.activeRun(sim.state, sim.env)!!.protocol)
        // Paused: nothing of it is timed, and its step cannot be tapped.
        sim.at("2026-10-19T23:45", Input.StepDone(id, 2))
        assertEquals(2, sim.run(sleep)!!.step)
        val isha = sim.runId("prayer-isha")
        sim.step(Input.StepDone(isha, 0))
        sim.step(Input.StepDone(isha, 1))
        sim.at("2026-10-19T23:55")
        assertEquals(RunPhase.ACTIVE, sim.run(sleep)!!.phase)
        assertEquals(sleep, Machine.activeRun(sim.state, sim.env)!!.protocol)
        // 20 minutes paused: prep unlocks at 00:01 instead of 23:41.
        assertEquals(rome("2026-10-20T00:01"), Machine.stepView(sim.state, id, sim.env)!!.lockedUntil)
        assertEquals(20, sim.lastOf(EntryType.RESUMED)!!.minutes)
    }

    @Test
    fun aCallPausesTheWindDownLikeAnyLockAndQuietSilencesIt() {
        val sim = Sim()
        sim.at("2026-10-19T23:30")
        val id = sim.runId(sleep)
        sim.step(Input.StepDone(id, 0, "a\nb\nc"))
        sim.step(Input.StepDone(id, 1, "x"))
        sim.after(2, Input.CallActive(true))
        sim.after(5, Input.CallActive(false))
        assertEquals(rome("2026-10-19T23:45"), Machine.stepView(sim.state, id, sim.env)!!.lockedUntil)
        sim.step(Input.Toggle(ToggleName.QUIET, true))
        assertTrue(Machine.isSilent(sim.state, sim.run(sleep)!!))
    }

    @Test
    fun stagesForTheWindDownAndTheNightLock() {
        val guided = Sim()
        guided.state = guided.state.copy(stages = mapOf(sleep to StageMemory(2)))
        guided.at("2026-10-19T23:30")
        assertEquals(2, guided.run(sleep)!!.stage)
        guided.after(1, Input.GoingToSleep())
        val night = guided.night()!!
        assertEquals(2, night.stage)
        assertFalse(Machine.reasserts(night))
        // Stage 3: one notification; GOING TO SLEEP marks it done; no night lock.
        val reminder = Sim()
        reminder.state = reminder.state.copy(stages = mapOf(sleep to StageMemory(3)))
        reminder.at("2026-10-19T23:30")
        assertEquals(RunPhase.REMINDER, reminder.run(sleep)!!.phase)
        reminder.after(5, Input.GoingToSleep())
        assertEquals("clean", reminder.of(EntryType.COMPLETED).single { it.protocol == sleep }.reason)
        assertNull(reminder.night())
    }

    @Test
    fun theLadderOfTheWindDown() {
        val sim = Sim()
        sim.at("2026-10-19T23:30")
        val id = sim.runId(sleep)
        sim.step(Input.NotOkay(id))
        assertEquals(5, Machine.stepsFor(sim.run(sleep)!!, sim.env).size)
        sim.step(Input.NotOkay(id))
        val minimal = Machine.stepsFor(sim.run(sleep)!!, sim.env)
        assertEquals(4, minimal.size)
        // Minimal: one line about the day, tomorrow's first task (a to-do), phone away, sleep.
        sim.step(Input.StepDone(id, 0, "Fine"))
        sim.step(Input.StepDone(id, 1, "Call the bank"))
        sim.step(Input.NotOkay(id))
        assertTrue(Machine.stepView(sim.state, id, sim.env)!!.minimum)
        assertTrue(Machine.stepView(sim.state, id, sim.env)!!.goingToSleep)
        sim.step(Input.StepDone(id, 0))
        assertEquals("done", sim.of(EntryType.COMPLETED).single { it.protocol == sleep }.reason)
        assertEquals(listOf("Call the bank"), Machine.todos(sim.state, sim.env).map { it.text })
        assertNotNull(sim.night())
    }

    @Test
    fun todosAreTickedAndExpireWithTheirDay() {
        val sim = Sim()
        sim.at("2026-10-19T23:30")
        sim.windDown("One\nTwo")
        sim.after(1, Input.GoingToSleep())
        sim.at("2026-10-20T07:00", Input.WakeAlarm())
        sim.step(Input.TodoDone(1))
        assertEquals(listOf(false, true), Machine.todos(sim.state, sim.env).map { it.done })
        val done = sim.lastOf(EntryType.TODO_DONE)!!
        assertEquals("Two", done.text)
        assertEquals("done", done.reason)
        sim.step(Input.TodoDone(1, done = false))
        assertEquals("undone", sim.lastOf(EntryType.TODO_DONE)!!.reason)
        sim.step(Input.TodoDone(7))
        assertEquals(2, sim.of(EntryType.TODO_DONE).size)
        // Tonight's wind-down writes tomorrow's; at GOING TO SLEEP today's expire.
        sim.at("2026-10-20T23:10")
        sim.windDown("Three")
        sim.after(1, Input.GoingToSleep())
        assertEquals(setOf("2026-10-21"), sim.state.todos.keys)
        assertEquals(listOf("Three"), Machine.todos(sim.state, sim.env).map { it.text })
        // A TEST wind-down writes none.
        val test = Sim()
        test.step(Input.Manual(sleep, test = true))
        test.step(Input.StepDone(test.runId(sleep), 0, "a\nb\nc"))
        test.step(Input.StepDone(test.runId(sleep), 1, "Nope"))
        assertTrue(test.state.todos.isEmpty())
    }

    @Test
    fun theNightLockAcrossTheClockChange() {
        // Saturday 24 October: GOING TO SLEEP at 23:30 CEST; the clocks go back at 03:00; the alarm is at 07:00 CET.
        val sim = Sim("2026-10-24T08:00")
        sim.at("2026-10-24T23:30", Input.GoingToSleep())
        assertEquals(Instant.parse("2026-10-25T08:00:00Z"), sim.night()!!.lockEndsAt)
        assertEquals(Instant.parse("2026-10-25T06:00:00Z"), Machine.wakeAlarmAt(sim.state, sim.env))
        sim.now = Instant.parse("2026-10-25T06:00:00Z")
        sim.step()
        assertEquals(Instant.parse("2026-10-25T06:00:00Z"), sim.lastEffects<Effect.RingWakeAlarm>().single().scheduledAt)
        sim.now = Instant.parse("2026-10-25T06:02:00Z")
        sim.step(Input.WakeAlarm())
        assertNull(sim.night())
        assertEquals(2, sim.lastOf(EntryType.WAKE)!!.minutes)
        assertEquals("2026-10-25T07:02:00+01:00", sim.lastOf(EntryType.WAKE)!!.ts)
        // 23:30 to 07:02 is 7 h 32 min on the clock; the lock counted the real 8 h 32 min.
        assertEquals(512, sim.lastOf(EntryType.NIGHT_LOCK)!!.minutes)
    }

    @Test
    fun stateWithTheNightLockRoundTripsAndOldStateStillReads() {
        val sim = Sim()
        sim.at("2026-10-19T23:30")
        sim.windDown()
        sim.after(1, Input.GoingToSleep())
        sim.at("2026-10-20T07:00")
        val text = sim.state.encode()
        assertEquals(sim.state, MachineState.decode(text))
        assertTrue(text.contains("\"phase\":\"night\""))
        assertTrue(text.contains("\"ringing_for\":\"2026-10-20\""))
        // A state saved before M2/M3 (no sleep, wake alarm, to-dos, woke or paused fields) reads with their defaults.
        val json = MachineState.JSON.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val day = json["day"] as kotlinx.serialization.json.JsonObject
        val old = kotlinx.serialization.json.JsonObject(
            json - "sleep" - "wake_alarm" - "todos" + ("day" to kotlinx.serialization.json.JsonObject(day - "woke" - "sleep_bailed")) +
                ("runs" to kotlinx.serialization.json.JsonArray(emptyList())),
        )
        val decoded = MachineState.decode(old.toString())
        assertEquals(SleepState(), decoded.sleep)
        assertEquals(WakeAlarmState(), decoded.wakeAlarm)
        assertTrue(decoded.todos.isEmpty())
        assertEquals(sim.state.day!!.key, decoded.day!!.key)
        val o = Machine.step(decoded, Input.Tick, sim.env)
        assertEquals("2026-10-20", o.state.day!!.key)
    }
}
