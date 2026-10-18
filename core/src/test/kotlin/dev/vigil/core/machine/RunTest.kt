package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.Lock
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.StepVersions
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

class RunTest {
    private val a = testProtocol("alpha")
    private val b = testProtocol("beta")

    private fun Sim.stage(id: String, stage: Int) {
        state = state.copy(stages = state.stages + (id to StageMemory(stage)))
    }

    /** Taps through alpha's three steps (no locks). */
    private fun Sim.finish(id: String) {
        val r = state.runs.first { it.protocol == id }
        for (i in r.step until Machine.stepsFor(r, env).size) step(Input.StepDone(r.id, i))
    }

    @Test
    fun startTakesTheScreen() {
        val sim = Sim(file = fileWith(a))
        sim.step(Input.Manual("alpha"))
        val r = sim.run("alpha")!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(rome("2026-10-19T08:30"), r.lockEndsAt)
        assertEquals(listOf(Effect.ShowTakeover(r.id)), sim.lastEffects<Effect.ShowTakeover>())
        val fired = sim.lastOf(EntryType.FIRED)!!
        assertEquals("manual", fired.reason)
        assertEquals(1, fired.stage)
        assertEquals("full", fired.version)
        assertEquals("run-1", fired.run)
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        assertEquals(rome("2026-10-19T08:00"), sim.state.day!!.started["alpha"])
    }

    @Test
    fun oneScreenRunAtATimeOthersQueue() {
        val sim = Sim(file = fileWith(a, b))
        sim.step(Input.Manual("alpha"))
        sim.after(1, Input.Manual("beta"))
        assertEquals(RunPhase.QUEUED, sim.run("beta")!!.phase)
        assertEquals("waits behind ALPHA", sim.lastOf(EntryType.FIRED)!!.detail)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().isEmpty())
        sim.after(1)
        sim.finish("alpha")
        val beta = sim.run("beta")!!
        assertEquals(RunPhase.ACTIVE, beta.phase)
        assertEquals(sim.now, beta.startedAt)
        assertEquals(sim.now.plusSeconds(1800), beta.lockEndsAt)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == beta.id })
    }

    @Test
    fun prayersTakeTheScreenAndThePausedRunGoesOnFirst() {
        val sim = Sim(file = fileWith(a, b))
        sim.step(Input.Manual("alpha"))
        sim.step(Input.Manual("beta"))
        sim.after(1, Input.Adhan(Prayer.FAJR))
        // The adhan never waits: alpha pauses, Fajr owns the screen, beta still waits.
        assertEquals(RunPhase.ACTIVE, sim.run("prayer-fajr")!!.phase)
        assertEquals(RunPhase.PAUSED, sim.run("alpha")!!.phase)
        assertEquals(RunPhase.QUEUED, sim.run("beta")!!.phase)
        assertEquals("prayer-fajr", Machine.activeRun(sim.state, sim.env)!!.protocol)
        // After the prayer's lock (confirmed), alpha goes on before beta starts.
        val fajr = sim.runId("prayer-fajr")
        sim.step(Input.StepDone(fajr, 0))
        sim.after(8, Input.StepDone(fajr, 1))
        sim.after(12)
        assertNull(sim.run("prayer-fajr"))
        assertEquals(RunPhase.ACTIVE, sim.run("alpha")!!.phase)
        assertEquals(RunPhase.QUEUED, sim.run("beta")!!.phase)
    }

    @Test
    fun checkStepWithMinutesIsLocked() {
        val p = testProtocol("x", steps = listOf(Step("Sun.", StepType.CHECK, min = 5), Step("Go.")))
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("x"))
        val id = sim.runId("x")
        assertEquals(rome("2026-10-19T08:05"), Machine.stepView(sim.state, id, sim.env)!!.lockedUntil)
        sim.after(4, Input.StepDone(id, 0))
        assertEquals(0, sim.run("x")!!.step)
        sim.after(1, Input.StepDone(id, 0))
        assertEquals(1, sim.run("x")!!.step)
        // Stale taps (wrong index) are ignored.
        sim.step(Input.StepDone(id, 0))
        assertEquals(1, sim.run("x")!!.step)
    }

    @Test
    fun timerEndsByItself() {
        val p = testProtocol("x", steps = listOf(Step("Breathe.", StepType.TIMER, min = 3), Step("Go.")))
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("x"))
        val id = sim.runId("x")
        sim.step(Input.StepDone(id, 0))
        assertEquals(0, sim.run("x")!!.step)
        val view = Machine.stepView(sim.state, id, sim.env)!!
        assertFalse(view.canTap)
        assertEquals(rome("2026-10-19T08:03"), view.timerEndsAt)
        assertEquals(rome("2026-10-19T08:03"), Machine.nextWake(sim.state, sim.env))
        sim.after(3)
        assertEquals(1, sim.run("x")!!.step)
        val e = sim.lastOf(EntryType.STEP_DONE)!!
        assertEquals("timer", e.reason)
        assertEquals("Breathe.", e.detail)
    }

    @Test
    fun textStepNeedsItsLines() {
        val p = testProtocol("x", steps = listOf(Step("Three lines on the day.", StepType.TEXT, lines = 2), Step("Go.")))
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("x"))
        val id = sim.runId("x")
        sim.step(Input.StepDone(id, 0, "only one"))
        sim.step(Input.StepDone(id, 0, "one\n\n  "))
        assertEquals(0, sim.run("x")!!.step)
        sim.step(Input.StepDone(id, 0, "one\ntwo\n"))
        val r = sim.run("x")!!
        assertEquals(1, r.step)
        assertEquals("one\ntwo", r.texts["0"])
        assertEquals("one\ntwo", sim.lastOf(EntryType.STEP_DONE)!!.text)
    }

    @Test
    fun doneIsLockedThenUnlocksTheScreen() {
        val p = testProtocol("x", lock = Lock(LockType.SCREEN, 30, 5))
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("x"))
        val id = sim.runId("x")
        sim.step(Input.StepDone(id, 0))
        sim.step(Input.StepDone(id, 1))
        sim.after(4, Input.StepDone(id, 2))
        assertNotNull(sim.run("x"))
        assertEquals(rome("2026-10-19T08:05"), Machine.stepView(sim.state, id, sim.env)!!.lockedUntil)
        sim.after(1, Input.StepDone(id, 2))
        // The done step was last: the run is complete and its lock ends now, 25 minutes early.
        assertNull(sim.run("x"))
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertTrue(sim.decision() is PoolDecision.Wait)
        assertEquals(rome("2026-10-19T08:05"), sim.state.day!!.completed["x"])
    }

    @Test
    fun lockEndWithStepsLeftKeepsTheRunOpen() {
        val p = testProtocol("x", lock = Lock(LockType.SCREEN, 10, null))
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("x"))
        val id = sim.runId("x")
        sim.after(10)
        val r = sim.run("x")!!
        assertEquals(RunPhase.OPEN, r.phase)
        assertEquals("not finished: finish it on home", sim.lastOf(EntryType.LOCK_ENDED)!!.detail)
        val n = sim.lastEffects<Effect.Notify>().single()
        assertEquals(NotifyKind.RUN, n.kind)
        assertEquals("X: FINISH ON VIGIL", n.title)
        assertNull(Machine.activeRun(sim.state, sim.env))
        sim.finish("x")
        assertNull(sim.run("x"))
        assertNotNull(sim.lastOf(EntryType.COMPLETED))
        assertTrue(sim.lastEffects<Effect.CancelNotify>().any { it.id == "run-$id" })
    }

    @Test
    fun stepDownLadder() {
        val p = testProtocol(
            "m", kind = ProtocolKind.MORNING,
            short = listOf(Step("Short 1."), Step("Short 2.")),
            minimal = listOf(Step("Minimal.")),
            single = listOf(Step("Stand in the sun for 5 minutes.")),
        )
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("m"))
        val id = sim.runId("m")
        sim.step(Input.StepDone(id, 0))
        sim.step(Input.NotOkay(id))
        var r = sim.run("m")!!
        assertEquals(Version.SHORT, r.version)
        assertEquals(0, r.step)
        assertEquals("short", sim.lastOf(EntryType.STEPPED_DOWN)!!.version)
        assertEquals("Short 1.", Machine.stepsFor(r, sim.env).first().text)
        sim.step(Input.NotOkay(id))
        assertEquals(Version.MINIMAL, sim.run("m")!!.version)
        sim.step(Input.NotOkay(id))
        r = sim.run("m")!!
        assertEquals(Version.SINGLE, r.version)
        assertTrue(r.isMinimum)
        assertTrue(Machine.stepView(sim.state, id, sim.env)!!.minimum)
        assertEquals("minimum", sim.lastOf(EntryType.STEPPED_DOWN)!!.detail)
        sim.step(Input.NotOkay(id))
        assertEquals(3, sim.of(EntryType.STEPPED_DOWN).size)
        sim.finish("m")
        assertEquals("done", sim.lastOf(EntryType.COMPLETED)!!.reason)
        assertEquals("single", sim.lastOf(EntryType.COMPLETED)!!.version)
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
        assertTrue(sim.of(EntryType.BAILED).isEmpty())
        // Not clean (no promotion credit), not a miss.
        assertEquals(StageMemory(1, 0, listOf("done")), sim.state.stages["m"])
        assertEquals(0, sim.state.missesInARow)
    }

    @Test
    fun ladderSkipsMissingVersions() {
        val p = testProtocol("m", kind = ProtocolKind.MORNING, minimal = listOf(Step("Minimal.")))
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("m"))
        sim.step(Input.NotOkay(sim.runId("m")))
        assertEquals(Version.MINIMAL, sim.run("m")!!.version)
        sim.step(Input.NotOkay(sim.runId("m")))
        assertEquals(Version.MINIMAL, sim.run("m")!!.version)
    }

    @Test
    fun kindsWithoutALadderIgnoreNotOkay() {
        val p = testProtocol("j", kind = ProtocolKind.JUMMAH)
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("j"))
        sim.step(Input.NotOkay(sim.runId("j")))
        assertEquals(Version.FULL, sim.run("j")!!.version)
        assertTrue(sim.of(EntryType.STEPPED_DOWN).isEmpty())
    }

    @Test
    fun weekdayVersions() {
        val p = testProtocol("m", kind = ProtocolKind.MORNING, weekdays = mapOf("thu" to StepVersions(steps = listOf(Step("Thursday only.")))))
        val mon = Sim(file = fileWith(p))
        mon.step(Input.Manual("m"))
        assertEquals(3, Machine.stepsFor(mon.run("m")!!, mon.env).size)
        val thu = Sim("2026-10-22T08:00", file = fileWith(p))
        thu.step(Input.Manual("m"))
        assertEquals("thu", thu.run("m")!!.weekday)
        assertEquals(listOf("Thursday only."), Machine.stepsFor(thu.run("m")!!, thu.env).map { it.text })
    }

    @Test
    fun bailOutEndsTheRunWithAPenalty() {
        val sim = Sim(file = fileWith(a, b))
        sim.step(Input.Manual("alpha"))
        sim.step(Input.Manual("beta"))
        sim.after(2, Input.BailOut(sim.runId("alpha")))
        assertNull(sim.run("alpha"))
        assertNotNull(sim.lastOf(EntryType.BAILED))
        assertEquals(120, sim.lastOf(EntryType.PENALTY)!!.minutes)
        assertEquals(listOf("bailed"), sim.state.stages["alpha"]!!.recent)
        assertEquals(1, sim.state.missesInARow)
        assertEquals(RunPhase.ACTIVE, sim.run("beta")!!.phase)
        // The penalty outlasts the lock of the next run.
        val blocks = (sim.decision() as PoolDecision.Blocked).blocks.map { it.reason }
        assertEquals(listOf(BlockReason.RUN_LOCK, BlockReason.PENALTY), blocks)
    }

    @Test
    fun stageThreeIsOneNotification() {
        val sim = Sim(file = fileWith(a))
        sim.stage("alpha", 3)
        sim.step(Input.Manual("alpha"))
        val r = sim.run("alpha")!!
        assertEquals(RunPhase.REMINDER, r.phase)
        assertEquals(LockType.NONE, r.lock)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().isEmpty())
        val n = sim.lastEffects<Effect.Notify>().single()
        assertEquals(NotifyKind.REMINDER, n.kind)
        assertEquals("ALPHA", n.title)
        assertEquals("One.", n.text)
        assertEquals("stage 3: one notification", sim.lastOf(EntryType.FIRED)!!.detail)
        assertTrue(sim.decision() is PoolDecision.Wait)
        sim.step(Input.Confirm(r.id))
        assertNull(sim.run("alpha"))
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
    }

    @Test
    fun unconfirmedReminderIsMissedAtDayEnd() {
        val sim = Sim(file = fileWith(a))
        sim.stage("alpha", 3)
        sim.step(Input.Manual("alpha"))
        sim.at("2026-10-19T23:00", Input.GoingToSleep())
        val m = sim.lastOf(EntryType.MISSED)!!
        assertEquals("day_end", m.reason)
        assertEquals("reminder not confirmed", m.detail)
        assertEquals(StageMemory(3, 0, listOf("missed")), sim.state.stages["alpha"])
        assertEquals(1, sim.state.missesInARow)
    }

    @Test
    fun ignoredRemindersMoveAProtocolBackFromStageThree() {
        val sim = Sim(file = fileWith(a))
        sim.stage("alpha", 3)
        for (day in listOf("2026-10-19", "2026-10-20", "2026-10-21")) {
            sim.at("${day}T09:00", Input.Manual("alpha"))
            assertEquals(RunPhase.REMINDER, sim.run("alpha")!!.phase)
            sim.at("${day}T23:00", Input.GoingToSleep())
        }
        assertEquals(2, sim.of(EntryType.STAGE_DOWN).single().stage)
        sim.at("2026-10-22T09:00", Input.Manual("alpha"))
        assertEquals(RunPhase.ACTIVE, sim.run("alpha")!!.phase)
    }

    @Test
    fun theNextStartReplacesARunLeftOpen() {
        val meal = testProtocol("meal", trigger = "slot:meal", kind = ProtocolKind.MEAL, lock = Lock(LockType.POOL, 30, null))
        val sim = Sim("2026-10-19T07:00", file = fileWith(meal))
        sim.at("2026-10-19T07:30")
        val breakfast = sim.run("meal")!!
        assertEquals("breakfast", breakfast.slot!!.label)
        sim.at("2026-10-19T08:00")
        assertEquals(RunPhase.OPEN, sim.run("meal")!!.phase)
        // Lunch is not skipped as "already running": breakfast, never finished, is a miss and lunch starts.
        sim.at("2026-10-19T13:00")
        val lunch = sim.run("meal")!!
        assertEquals("lunch", lunch.slot!!.label)
        assertEquals(RunPhase.ACTIVE, lunch.phase)
        val m = sim.lastOf(EntryType.MISSED)!!
        assertEquals(breakfast.id, m.run)
        assertEquals("replaced", m.reason)
        assertTrue(sim.of(EntryType.SKIPPED).isEmpty())
        // A run still in its lock does hold the protocol.
        sim.step(Input.Manual("meal"))
        assertEquals("already_running", sim.lastOf(EntryType.SKIPPED)!!.reason)
        assertEquals(lunch.id, sim.runId("meal"))
    }

    @Test
    fun stageTwoIsLighter() {
        val p = testProtocol("x", steps = listOf(Step("Sun.", StepType.CHECK, min = 5), Step("Breathe.", StepType.TIMER, min = 3)), lock = Lock(LockType.SCREEN, 30, 5))
        val sim = Sim(file = fileWith(p))
        sim.stage("x", 2)
        sim.step(Input.Manual("x"))
        val r = sim.run("x")!!
        assertFalse(Machine.reasserts(r))
        assertNull(r.doneAfterAt)
        assertEquals(BlockReason.RUN_LOCK, (sim.decision() as PoolDecision.Blocked).reason)
        assertNull(Machine.stepView(sim.state, r.id, sim.env)!!.lockedUntil)
        sim.step(Input.StepDone(r.id, 0))
        assertTrue(Machine.stepView(sim.state, r.id, sim.env)!!.canTap)
        sim.step(Input.StepDone(r.id, 1))
        assertNull(sim.run("x"))
    }

    @Test
    fun tenCleanCompletionsPromoteAndThreeFailuresDemote() {
        val sim = Sim(file = fileWith(a))
        repeat(10) {
            sim.step(Input.Manual("alpha"))
            sim.finish("alpha")
        }
        assertEquals(StageMemory(2), sim.state.stages["alpha"])
        assertEquals(2, sim.of(EntryType.STAGE_UP).single().stage)
        // A clean streak broken by a step-down does not promote; failures within the last 10 demote.
        repeat(3) {
            sim.step(Input.Manual("alpha"))
            assertEquals(2, sim.run("alpha")!!.stage)
            sim.step(Input.BailOut(sim.runId("alpha")))
        }
        assertEquals(1, sim.of(EntryType.STAGE_DOWN).single().stage)
        assertEquals(StageMemory(1), sim.state.stages["alpha"])
    }

    @Test
    fun streakNeedsTenInARow() {
        val sim = Sim(file = fileWith(a))
        repeat(9) {
            sim.step(Input.Manual("alpha"))
            sim.finish("alpha")
        }
        sim.step(Input.Manual("alpha"))
        sim.step(Input.BailOut(sim.runId("alpha")))
        repeat(9) {
            sim.step(Input.Manual("alpha"))
            sim.finish("alpha")
        }
        assertEquals(1, sim.state.stages["alpha"]!!.stage)
        assertEquals(9, sim.state.stages["alpha"]!!.cleanStreak)
    }

    @Test
    fun pinnedProtocolsStayAtStageOne() {
        val p = testProtocol("pinned", stages = false)
        val sim = Sim(file = fileWith(p))
        repeat(12) {
            sim.step(Input.Manual("pinned"))
            sim.finish("pinned")
        }
        assertTrue(sim.of(EntryType.STAGE_UP).isEmpty())
        assertEquals(StageMemory(1), sim.state.stages["pinned"])
    }

    @Test
    fun missesInARowStartTheResetOffer() {
        val reset = testProtocol("reset", trigger = "event:misses:2")
        val sim = Sim(file = fileWith(a, reset))
        sim.step(Input.Manual("alpha"))
        sim.step(Input.BailOut(sim.runId("alpha")))
        assertNull(sim.run("reset"))
        sim.step(Input.Manual("alpha"))
        sim.step(Input.BailOut(sim.runId("alpha")))
        assertNotNull(sim.run("reset"))
        assertEquals("misses:2", sim.of(EntryType.FIRED).last().reason)
        assertEquals(0, sim.state.missesInARow)
    }

    @Test
    fun nothingStartsWhileTheDayEnds() {
        val reset = testProtocol("reset", trigger = "event:misses:2")
        val sim = Sim(file = fileWith(a, reset, base = PRAYERS_ONLY))
        sim.step(Input.Manual("alpha"))
        sim.step(Input.BailOut(sim.runId("alpha")))
        sim.at("2026-10-19T22:00", Input.Manual("alpha"))
        // GOING TO SLEEP misses the open alpha: the second miss in a row, but no reset takeover at bedtime.
        sim.at("2026-10-19T23:00", Input.GoingToSleep())
        assertNull(sim.run("reset"))
        val skipped = sim.lastOf(EntryType.SKIPPED)!!
        assertEquals("reset", skipped.protocol)
        assertEquals("day_end", skipped.reason)
        assertEquals(0, sim.state.missesInARow)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().isEmpty())
        assertTrue(sim.state.runs.isEmpty())
        // Nor an `event:after` protocol of a run that the day end completes (Isha confirmed, its quiet time running).
        val next = testProtocol("after-isha", trigger = "event:after:prayer-isha", lock = Lock(LockType.NONE))
        val isha = Sim(file = fileWith(next, base = PRAYERS_ONLY))
        isha.at("2026-10-19T19:40", Input.Adhan(Prayer.ISHA))
        val id = isha.runId("prayer-isha")
        isha.step(Input.StepDone(id, 0))
        isha.at("2026-10-19T19:48", Input.StepDone(id, 1))
        isha.at("2026-10-19T19:50", Input.GoingToSleep())
        assertEquals("prayer-isha", isha.lastOf(EntryType.COMPLETED)!!.protocol)
        assertNull(isha.run("after-isha"))
        assertEquals("day_end", isha.lastOf(EntryType.SKIPPED)!!.reason)
        // The next day starts them as usual.
        isha.at("2026-10-20T19:40", Input.Adhan(Prayer.ISHA))
        isha.at("2026-10-20T20:00")
        assertNotNull(isha.run("after-isha"))
    }

    @Test
    fun aCompletionResetsTheMissCount() {
        val sim = Sim(file = fileWith(a))
        sim.step(Input.Manual("alpha"))
        sim.step(Input.BailOut(sim.runId("alpha")))
        assertEquals(1, sim.state.missesInARow)
        sim.step(Input.Manual("alpha"))
        sim.finish("alpha")
        assertEquals(0, sim.state.missesInARow)
    }

    @Test
    fun afterTriggers() {
        val next = testProtocol("attention", trigger = "event:after:alpha", lock = Lock(LockType.NONE))
        val sim = Sim(file = fileWith(a, next))
        sim.step(Input.Manual("alpha"))
        sim.finish("alpha")
        assertNotNull(sim.run("attention"))
        assertEquals("after:alpha", sim.lastOf(EntryType.FIRED)!!.reason)
        val bailed = Sim(file = fileWith(a, next))
        bailed.step(Input.Manual("alpha"))
        bailed.step(Input.BailOut(bailed.runId("alpha")))
        assertNull(bailed.run("attention"))
        // A lock that ends unfinished also counts as "after".
        val ended = Sim(file = fileWith(a, next))
        ended.step(Input.Manual("alpha"))
        ended.after(30)
        assertNotNull(ended.run("attention"))
        ended.finish("alpha")
        assertEquals(1, ended.of(EntryType.FIRED).count { it.protocol == "attention" })
    }

    @Test
    fun afterThePrayerLock() {
        val next = testProtocol("attention", trigger = "event:after:prayer-dhuhr", lock = Lock(LockType.NONE))
        val sim = Sim(file = fileWith(next))
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        sim.at("2026-10-19T12:50")
        assertNotNull(sim.run("attention"))
    }

    @Test
    fun callPauseExtendsTimersAndLocks() {
        val p = testProtocol("x", steps = listOf(Step("Breathe.", StepType.TIMER, min = 10), Step("Go.")), lock = Lock(LockType.SCREEN, 20, null))
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("x"))
        sim.after(5, Input.CallActive(true))
        sim.after(30)
        val r = sim.run("x")!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(0, r.step)
        assertNull("nothing ends during a call", Machine.nextWake(sim.state, sim.env)?.takeIf { it == r.lockEndsAt })
        sim.step(Input.CallActive(false))
        val after = sim.run("x")!!
        assertEquals(rome("2026-10-19T08:50"), after.lockEndsAt)
        assertEquals(rome("2026-10-19T08:30"), after.stepStartedAt)
        sim.at("2026-10-19T08:40")
        assertEquals(1, sim.run("x")!!.step)
        assertEquals(30, sim.lastOf(EntryType.CALL_RESUME)!!.minutes)
    }

    @Test
    fun aRunThatStartsDuringACallWaitsForTheCallToEnd() {
        val sim = Sim(file = fileWith(a))
        sim.step(Input.CallActive(true))
        sim.after(5, Input.Manual("alpha"))
        sim.after(40)
        val r = sim.run("alpha")!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(rome("2026-10-19T09:15"), Machine.stepView(sim.state, r.id, sim.env)!!.lockEndsAt)
        sim.step(Input.CallActive(false))
        assertEquals(rome("2026-10-19T09:15"), sim.run("alpha")!!.lockEndsAt)
    }

    @Test
    fun timerUntilTheLockEndsIsOverAtOnceWithoutATimedLock() {
        val p = testProtocol("x", lock = Lock(LockType.NONE), steps = listOf(Step("Rest.", StepType.TIMER), Step("Go.")))
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("x"))
        assertEquals(1, sim.run("x")!!.step)
    }

    @Test
    fun suspensionDropsRunsAndSkipsTriggers() {
        val timed = testProtocol("timed", trigger = "time:09:00")
        val sim = Sim(file = fileWith(a, timed))
        sim.step(Input.Manual("alpha"))
        sim.step(Input.Suspend(120))
        assertNull(sim.run("alpha"))
        assertEquals("kill_switch", sim.lastOf(EntryType.DROPPED)!!.reason)
        assertTrue(sim.state.stages.isEmpty())
        sim.step(Input.Manual("alpha"))
        assertEquals("suspended", sim.lastOf(EntryType.SKIPPED)!!.reason)
        sim.at("2026-10-19T09:00")
        assertNull(sim.run("timed"))
        val skipped = sim.lastOf(EntryType.SKIPPED)!!
        assertEquals("timed", skipped.protocol)
        assertEquals("2026-10-19T09:00:00+02:00", skipped.scheduled)
        assertEquals(dev.vigil.core.timeline.StartStatus.SKIPPED, Machine.timeline(sim.state, sim.env).single { it.start.protocolId == "timed" }.status)
    }

    @Test
    fun alreadyRunningIsSkipped() {
        val sim = Sim(file = fileWith(a))
        sim.step(Input.Manual("alpha"))
        sim.step(Input.Manual("alpha"))
        assertEquals("already_running", sim.lastOf(EntryType.SKIPPED)!!.reason)
        assertEquals(1, sim.state.runs.size)
    }

    @Test
    fun wakeAlarmAndToggleTriggers() {
        val morning = testProtocol("morning", trigger = "event:wake_alarm", kind = ProtocolKind.MORNING)
        val walk = testProtocol("walk", trigger = "toggle:walking", kind = ProtocolKind.WALK, lock = Lock(LockType.POOL))
        val sim = Sim(file = fileWith(morning, walk, base = PRAYERS_ONLY))
        sim.at("2026-10-20T07:00", Input.WakeAlarm())
        assertEquals("wake_alarm", sim.lastOf(EntryType.FIRED)!!.reason)
        sim.step(Input.Toggle(ToggleName.WALKING, true))
        assertEquals("toggle:walking", sim.lastOf(EntryType.FIRED)!!.reason)
        assertEquals(RunPhase.ACTIVE, sim.run("walk")!!.phase)
        sim.step(Input.Toggle(ToggleName.WALKING, false))
        assertNull(sim.run("walk"))
        assertEquals("walk", sim.lastOf(EntryType.COMPLETED)!!.protocol)
    }

    @Test
    fun openRunsAreMissedAtDayEndTheWindDownCompletes() {
        val pool = testProtocol("alpha", lock = Lock(LockType.POOL))
        val sim = Sim(file = fileWith(pool))
        sim.step(Input.Manual("alpha"))
        sim.at("2026-10-19T22:00", Input.Manual("sleep"))
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        assertEquals("alpha", sim.lastOf(EntryType.MISSED)!!.protocol)
        assertEquals("day_end", sim.lastOf(EntryType.MISSED)!!.reason)
        // GOING TO SLEEP completes the wind-down; what stays is the night lock, which counts for no day.
        assertEquals("sleep", sim.lastOf(EntryType.COMPLETED)!!.protocol)
        assertEquals(listOf(RunPhase.NIGHT), sim.state.runs.map { it.phase })
    }

    @Test
    fun aCallThatNeverReportsItsEndStopsPausingAfterThreeHours() {
        val p = testProtocol("x", lock = Lock(LockType.SCREEN, 20, null))
        val sim = Sim(file = fileWith(p))
        sim.step(Input.Manual("x"))
        sim.after(5, Input.CallActive(true))
        assertEquals(rome("2026-10-19T11:05"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T11:04")
        assertNotNull(sim.state.callSince)
        assertEquals(RunPhase.ACTIVE, sim.run("x")!!.phase)
        sim.at("2026-10-19T11:30")
        assertNull(sim.state.callSince)
        val resume = sim.lastOf(EntryType.CALL_RESUME)!!
        assertEquals(180, resume.minutes)
        assertEquals("2026-10-19T11:05:00+02:00", resume.ts)
        assertTrue(resume.detail!!.contains("no call end reported"))
        // The lock had 15 minutes left when the call began: it ended at 11:20.
        assertEquals(RunPhase.OPEN, sim.run("x")!!.phase)
        assertEquals("2026-10-19T11:20:00+02:00", sim.lastOf(EntryType.LOCK_ENDED)!!.ts)
        // The late "call ended" report changes nothing.
        sim.step(Input.CallActive(false))
        assertEquals(1, sim.of(EntryType.CALL_RESUME).size)
    }

    @Test
    fun aQueuedRunIsNotShownWhenTheDayEndsOrVigilIsSuspended() {
        val sim = Sim(file = fileWith(a, b, base = PRAYERS_ONLY))
        sim.step(Input.Manual("alpha"))
        sim.step(Input.Manual("beta"))
        sim.at("2026-10-19T23:00", Input.GoingToSleep())
        assertEquals(setOf("alpha", "beta"), sim.of(EntryType.MISSED).map { it.protocol }.toSet())
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().isEmpty())
        assertTrue(sim.state.runs.isEmpty())
        val suspended = Sim(file = fileWith(a, b))
        suspended.step(Input.Manual("alpha"))
        suspended.step(Input.Manual("beta"))
        suspended.step(Input.Suspend(60))
        assertTrue(suspended.lastEffects<Effect.ShowTakeover>().isEmpty())
        assertEquals(listOf("alpha", "beta"), suspended.of(EntryType.DROPPED).map { it.protocol })
        assertTrue(suspended.state.day!!.started["beta"] == null)
    }

    @Test
    fun runsOfRemovedProtocolsAreDropped() {
        val sim = Sim(file = fileWith(a))
        sim.step(Input.Manual("alpha"))
        val without = Sim(file = fileWithOnly(), startDay = false)
        without.now = sim.now
        without.state = sim.state
        without.step()
        assertTrue(without.state.runs.isEmpty())
        assertEquals("protocol_removed", without.lastOf(EntryType.DROPPED)!!.reason)
    }

    @Test
    fun testRunsAreForcedAndUncounted() {
        val sim = Sim(file = fileWith(a))
        sim.stage("alpha", 3)
        sim.step(Input.Manual("alpha", test = true))
        val r = sim.run("alpha")!!
        assertTrue(r.test)
        assertEquals(1, r.stage)
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertTrue(sim.lastOf(EntryType.FIRED)!!.test == true)
        assertEquals("test", sim.lastOf(EntryType.FIRED)!!.reason)
        assertNull(sim.state.lastFired["alpha"])
        sim.finish("alpha")
        assertEquals(StageMemory(3), sim.state.stages["alpha"])
        assertNull(sim.state.day!!.completed["alpha"])
    }
}
