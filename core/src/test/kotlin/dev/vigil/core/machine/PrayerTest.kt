package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
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
import java.time.Instant

class PrayerTest {
    private val dhuhr = "prayer-dhuhr"

    /** Yesterday's adhans, so today's windows and heads-ups have estimates. */
    private fun Sim.seed(vararg adhans: Pair<Prayer, String>) {
        state = state.copy(prayers = state.prayers.copy(lastAdhan = adhans.associate { (p, t) -> p to rome(t) }))
    }

    private fun Sim.stage(id: String, stage: Int) {
        state = state.copy(stages = state.stages + (id to StageMemory(stage)))
    }

    private fun Sim.blockReason(): BlockReason? = (decision() as? PoolDecision.Blocked)?.reason

    // ------------------------------------------------------------------ adhan, lock, done

    @Test
    fun adhanStartsTheTakeover() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val r = sim.run(dhuhr)!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(1, r.stage)
        assertEquals(LockType.SCREEN, r.lock)
        assertEquals(rome("2026-10-19T12:50"), r.lockEndsAt)
        assertEquals(rome("2026-10-19T12:38"), r.doneAfterAt)
        assertEquals(Prayer.DHUHR, r.prayer)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == r.id })
        assertEquals(listOf(EntryType.ADHAN, EntryType.FIRED), sim.last!!.entries.map { it.type })
        assertEquals("adhan", sim.lastOf(EntryType.FIRED)!!.reason)
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        assertTrue(Machine.reasserts(r))
        val d = sim.decision() as PoolDecision.Blocked
        assertEquals(BlockReason.RUN_LOCK, d.reason)
        assertEquals(rome("2026-10-19T12:50"), d.until)
        assertEquals("DHUHR", d.blocks.first().detail)
        assertEquals(rome("2026-10-19T12:30"), sim.state.lastFired[dhuhr])
    }

    @Test
    fun doneUnlocksAfterEightMinutesThenQuietTimeUntilTwenty() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val id = sim.runId(dhuhr)
        sim.step(Input.StepDone(id, 0))
        assertEquals(1, sim.run(dhuhr)!!.step)
        val view = Machine.stepView(sim.state, id, sim.env)!!
        assertEquals(rome("2026-10-19T12:38"), view.lockedUntil)
        assertFalse(view.canTap)
        sim.at("2026-10-19T12:37", Input.StepDone(id, 1))
        assertEquals(1, sim.run(dhuhr)!!.step)
        assertFalse(sim.run(dhuhr)!!.confirmed)
        sim.at("2026-10-19T12:38", Input.StepDone(id, 1))
        val r = sim.run(dhuhr)!!
        assertTrue(r.confirmed)
        assertEquals(2, r.step)
        assertNotNull(sim.lastOf(EntryType.CONFIRMED))
        assertEquals(listOf(Prayer.DHUHR), sim.state.day!!.confirmed)
        // The quiet timer cannot be tapped through and holds the screen until 12:50.
        sim.step(Input.StepDone(id, 2))
        assertEquals(2, sim.run(dhuhr)!!.step)
        assertEquals(rome("2026-10-19T12:50"), Machine.stepView(sim.state, id, sim.env)!!.timerEndsAt)
        sim.at("2026-10-19T12:49")
        assertEquals(RunPhase.ACTIVE, sim.run(dhuhr)!!.phase)
        sim.at("2026-10-19T12:50")
        assertNull(sim.run(dhuhr))
        val done = sim.lastOf(EntryType.COMPLETED)!!
        assertEquals("clean", done.reason)
        assertEquals(20, done.minutes)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertTrue(sim.lastEffects<Effect.CancelNotify>().any { it.id == "prayer-dhuhr" })
        assertEquals(listOf("clean"), sim.state.stages[dhuhr]!!.recent)
    }

    @Test
    fun confirmFromHomeDuringTheLockKeepsTheQuietTimer() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val id = sim.runId(dhuhr)
        sim.at("2026-10-19T12:35", Input.Confirm(id))
        assertFalse("done is still locked", sim.run(dhuhr)!!.confirmed)
        sim.at("2026-10-19T12:40", Input.Confirm(id))
        val r = sim.run(dhuhr)!!
        assertTrue(r.confirmed)
        assertEquals(2, r.step)
        sim.at("2026-10-19T12:50")
        assertNull(sim.run(dhuhr))
        // Noticed late (the phone dozed through the alarm): it still completed when the quiet time ran out.
        val late = Sim()
        late.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        late.at("2026-10-19T12:40", Input.Confirm(late.runId(dhuhr)))
        late.at("2026-10-19T14:00")
        val done = late.lastOf(EntryType.COMPLETED)!!
        assertEquals("2026-10-19T12:50:00+02:00", done.ts)
        assertEquals(20, done.minutes)
        assertEquals(rome("2026-10-19T12:50"), late.state.day!!.completed[dhuhr])
    }

    @Test
    fun unconfirmedLockEndReleasesTheScreenButKeepsThePrayerOpen() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val id = sim.runId(dhuhr)
        sim.at("2026-10-19T12:50")
        val r = sim.run(dhuhr)!!
        assertEquals(RunPhase.OPEN, r.phase)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertNotNull(sim.lastOf(EntryType.LOCK_ENDED))
        val n = sim.lastEffects<Effect.Notify>().single()
        assertEquals("prayer-dhuhr", n.id)
        assertEquals(NotifyKind.CONFIRM, n.kind)
        assertTrue(n.silent)
        assertEquals(listOf(r), Machine.status(sim.state, sim.env).toConfirm)
        assertTrue(sim.decision() !is PoolDecision.Blocked || sim.blockReason() != BlockReason.RUN_LOCK)
        sim.at("2026-10-19T13:30", Input.Confirm(id))
        assertNull(sim.run(dhuhr))
        assertEquals(listOf(EntryType.CONFIRMED, EntryType.COMPLETED), sim.last!!.entries.map { it.type })
    }

    @Test
    fun repeatedAdhanWithinThirtyMinutesIsIgnored() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val id = sim.runId(dhuhr)
        sim.at("2026-10-19T12:38", Input.Confirm(id))
        sim.at("2026-10-19T12:50")
        assertNull(sim.run(dhuhr))
        sim.at("2026-10-19T12:59", Input.Adhan(Prayer.DHUHR))
        assertTrue(sim.last!!.entries.none { it.type == EntryType.ADHAN })
        assertNull(sim.run(dhuhr))
        // From 30 minutes on it counts as an adhan again.
        sim.at("2026-10-19T13:00", Input.Adhan(Prayer.DHUHR))
        assertEquals(2, sim.of(EntryType.ADHAN).size)
        assertNotNull(sim.run(dhuhr))
    }

    @Test
    fun aLaterRepeatOfTheSamePrayerDoesNotMissItsOwnRun() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val id = sim.runId(dhuhr)
        // The adhan app re-posts (or the user starts Dhuhr by hand) an hour later, the prayer still open.
        sim.at("2026-10-19T13:30", Input.Adhan(Prayer.DHUHR))
        sim.step(Input.Manual(dhuhr))
        assertEquals(id, sim.runId(dhuhr))
        assertEquals(1, sim.state.runs.size)
        assertTrue(sim.of(EntryType.MISSED).isEmpty())
        assertEquals(1, sim.of(EntryType.ADHAN).size)
        assertEquals(rome("2026-10-19T12:30"), sim.state.prayers.lastAdhan[Prayer.DHUHR])
        sim.step(Input.Confirm(id))
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
    }

    @Test
    fun anEarlierPrayerStartedByHandDoesNotCloseALaterOne() {
        val sim = Sim()
        sim.at("2026-10-19T15:40", Input.Adhan(Prayer.ASR))
        val asr = sim.runId("prayer-asr")
        sim.at("2026-10-19T16:10", Input.Manual(dhuhr))
        assertEquals(asr, sim.runId("prayer-asr"))
        assertNotNull(sim.run(dhuhr))
        assertTrue(sim.of(EntryType.MISSED).isEmpty())
        // The next prayer's adhan closes both.
        sim.at("2026-10-19T18:00", Input.Adhan(Prayer.MAGHRIB))
        assertEquals(setOf(dhuhr, "prayer-asr"), sim.of(EntryType.MISSED).map { it.protocol }.toSet())
    }

    @Test
    fun anIshaLeftOpenThroughTheNextDayDoesNotSwallowTheNextIsha() {
        val sim = Sim()
        sim.at("2026-10-19T22:30", Input.GoingToSleep())
        // Isha comes after GOING TO SLEEP: it belongs to the 20th and stays open until that day ends.
        sim.at("2026-10-19T22:45", Input.Adhan(Prayer.ISHA))
        val first = sim.runId("prayer-isha")
        assertEquals(rome("2026-10-21T06:00"), View(sim.state, sim.env).windowEnd(sim.run("prayer-isha")!!))
        // A re-post 45 minutes later is the same prayer.
        sim.at("2026-10-19T23:30", Input.Adhan(Prayer.ISHA))
        assertEquals(1, sim.of(EntryType.ADHAN).size)
        // No Fajr adhan arrived to close it; the next evening's Isha is a new prayer, not a repeat of the open one.
        sim.at("2026-10-20T22:40", Input.Adhan(Prayer.ISHA))
        val m = sim.lastOf(EntryType.MISSED)!!
        assertEquals(first, m.run)
        assertEquals("next_adhan", m.reason)
        val second = sim.run("prayer-isha")!!
        assertTrue(second.id != first)
        assertEquals(RunPhase.ACTIVE, second.phase)
        assertEquals(rome("2026-10-20T22:40"), sim.state.prayers.lastAdhan[Prayer.ISHA])
    }

    @Test
    fun fajrClosesLastNightsIsha() {
        val sim = Sim()
        sim.at("2026-10-19T20:10", Input.Adhan(Prayer.ISHA))
        // Still awake at Fajr: the day has not ended, Isha is still open.
        sim.at("2026-10-20T05:40", Input.Adhan(Prayer.FAJR))
        val m = sim.lastOf(EntryType.MISSED)!!
        assertEquals("prayer-isha", m.protocol)
        assertEquals("next_adhan", m.reason)
        assertNotNull(sim.run("prayer-fajr"))
    }

    // ------------------------------------------------------------------ window

    @Test
    fun windowLastsUntilTheNextPrayersEstimatedAdhan() {
        val sim = Sim("2026-10-20T08:00")
        sim.seed(Prayer.ASR to "2026-10-19T15:40")
        sim.at("2026-10-20T12:31", Input.Adhan(Prayer.DHUHR))
        val r = sim.run(dhuhr)!!
        assertEquals(rome("2026-10-20T15:40"), r.windowEndsAt)
        assertTrue(r.windowEstimated)
    }

    @Test
    fun fifteenMinutesBeforeTheWindowClosesTheTakeoverReturns() {
        val sim = Sim("2026-10-20T08:00")
        sim.seed(Prayer.ASR to "2026-10-19T15:40")
        sim.at("2026-10-20T12:31", Input.Adhan(Prayer.DHUHR))
        val id = sim.runId(dhuhr)
        sim.at("2026-10-20T12:51")
        assertEquals(RunPhase.OPEN, sim.run(dhuhr)!!.phase)
        assertEquals(rome("2026-10-20T15:25"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-20T15:24")
        assertEquals(RunPhase.OPEN, sim.run(dhuhr)!!.phase)
        sim.at("2026-10-20T15:25")
        val r = sim.run(dhuhr)!!
        assertEquals(RunPhase.RETURNED, r.phase)
        assertTrue(r.returned)
        assertNotNull(sim.lastOf(EntryType.WINDOW_WARNING))
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == id })
        assertEquals(r, Machine.activeRun(sim.state, sim.env))
        val d = sim.decision() as PoolDecision.Blocked
        assertEquals(BlockReason.RUN_LOCK, d.reason)
        assertEquals(rome("2026-10-20T15:40"), d.until)
        // Done is open now; confirming completes at once (the quiet time ended with the lock) but not clean.
        sim.at("2026-10-20T15:30", Input.StepDone(id, 0))
        sim.step(Input.StepDone(id, 1))
        assertNull(sim.run(dhuhr))
        assertEquals("done", sim.lastOf(EntryType.COMPLETED)!!.reason)
    }

    @Test
    fun aReturningTakeoverTakesTheScreenFromAProtocol() {
        val alpha = testProtocol("alpha", lock = dev.vigil.core.protocol.Lock(LockType.SCREEN, 30, null))
        val sim = Sim("2026-10-20T08:00", file = fileWith(alpha))
        sim.seed(Prayer.ASR to "2026-10-19T15:40")
        sim.at("2026-10-20T12:31", Input.Adhan(Prayer.DHUHR))
        sim.at("2026-10-20T12:51")
        sim.at("2026-10-20T15:20", Input.Manual("alpha"))
        sim.at("2026-10-20T15:25")
        // A prayer is never held back by another protocol: alpha pauses (5 of its 30 minutes used).
        assertEquals(RunPhase.RETURNED, sim.run(dhuhr)!!.phase)
        assertEquals(RunPhase.PAUSED, sim.run("alpha")!!.phase)
        assertEquals(dhuhr, Machine.activeRun(sim.state, sim.env)!!.protocol)
        assertEquals(listOf(Effect.ShowTakeover(sim.runId(dhuhr))), sim.lastEffects<Effect.ShowTakeover>())
        assertEquals("alpha", sim.lastOf(EntryType.PAUSED)!!.protocol)
        // Confirmed at 15:30: alpha goes on where it was, its lock 25 minutes from now.
        sim.at("2026-10-20T15:30", Input.Confirm(sim.runId(dhuhr)))
        val back = sim.run("alpha")!!
        assertEquals(RunPhase.ACTIVE, back.phase)
        assertEquals(rome("2026-10-20T15:55"), back.lockEndsAt)
        assertEquals(5, sim.lastOf(EntryType.RESUMED)!!.minutes)
        assertEquals("alpha", Machine.activeRun(sim.state, sim.env)!!.protocol)
    }

    @Test
    fun windowClosingUnconfirmedIsAMiss() {
        val sim = Sim("2026-10-20T08:00")
        sim.seed(Prayer.ASR to "2026-10-19T15:40")
        sim.at("2026-10-20T12:31", Input.Adhan(Prayer.DHUHR))
        sim.at("2026-10-20T15:39")
        assertNotNull(sim.run(dhuhr))
        sim.at("2026-10-20T15:40")
        assertNull(sim.run(dhuhr))
        val m = sim.lastOf(EntryType.MISSED)!!
        assertEquals("window_closed", m.reason)
        assertEquals("dhuhr", m.prayer)
        assertEquals(listOf(Prayer.DHUHR), sim.state.day!!.missed)
        assertEquals(listOf("missed"), sim.state.stages[dhuhr]!!.recent)
        assertEquals(1, sim.state.missesInARow)
    }

    @Test
    fun theNextAdhanClosesAnUnconfirmedPrayer() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        sim.at("2026-10-19T15:40", Input.Adhan(Prayer.ASR))
        val m = sim.lastOf(EntryType.MISSED)!!
        assertEquals(dhuhr, m.protocol)
        assertEquals("next_adhan", m.reason)
        assertEquals(RunPhase.ACTIVE, sim.run("prayer-asr")!!.phase)
        assertNull(sim.run(dhuhr))
    }

    @Test
    fun withoutAnEstimateTheWindowHasASafetyEndAndNoWarning() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val r = sim.run(dhuhr)!!
        assertEquals(rome("2026-10-20T00:30"), r.windowEndsAt)
        assertFalse(r.windowEstimated)
        sim.at("2026-10-19T12:50")
        sim.at("2026-10-20T00:20")
        assertFalse(sim.run(dhuhr)!!.warned)
    }

    @Test
    fun ishaLastsUntilTheDayEnds() {
        val sim = Sim()
        sim.at("2026-10-19T19:40", Input.Adhan(Prayer.ISHA))
        assertNull(sim.run("prayer-isha")!!.windowEndsAt)
        sim.at("2026-10-19T20:00")
        assertEquals(RunPhase.OPEN, sim.run("prayer-isha")!!.phase)
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        val m = sim.of(EntryType.MISSED).single()
        assertEquals("day_end", m.reason)
        assertEquals("isha", m.prayer)
        assertNull(sim.run("prayer-isha"))
    }

    @Test
    fun ishaWarningBeforeTheFallbackEnd() {
        val sim = Sim()
        sim.at("2026-10-19T19:40", Input.Adhan(Prayer.ISHA))
        sim.at("2026-10-19T20:00")
        sim.at("2026-10-20T05:45")
        assertEquals(RunPhase.RETURNED, sim.run("prayer-isha")!!.phase)
        sim.at("2026-10-20T06:00")
        assertNull(sim.run("prayer-isha"))
        assertEquals("isha", sim.lastOf(EntryType.MISSED)!!.prayer)
    }

    @Test
    fun fajrIsNotClosedByTheDayBoundary() {
        val sim = Sim()
        // Still awake: Fajr comes before the 06:00 fallback ends the 19th.
        sim.at("2026-10-20T05:40", Input.Adhan(Prayer.FAJR))
        sim.at("2026-10-20T06:00")
        assertEquals("2026-10-20", sim.state.day!!.key)
        assertNotNull(sim.run("prayer-fajr"))
        assertTrue(sim.of(EntryType.MISSED).isEmpty())
    }

    // ------------------------------------------------------------------ class rule

    private val classes = MyTimes(classes = listOf(Slot(listOf("tue", "fri"), "13:00", "14:30", label = "Analysis")))

    @Test
    fun classOverlappingTheAdhanShowsASilentCardThenTheTakeoverAfterClass() {
        val sim = Sim("2026-10-20T08:00", times = classes)
        sim.at("2026-10-20T13:10", Input.Adhan(Prayer.DHUHR))
        val r = sim.run(dhuhr)!!
        assertEquals(RunPhase.CARD, r.phase)
        assertTrue(r.silent)
        assertEquals(rome("2026-10-20T14:30"), r.classEndsAt)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().isEmpty())
        val card = sim.lastEffects<Effect.Notify>().single()
        assertEquals(NotifyKind.CLASS_CARD, card.kind)
        assertTrue(card.silent)
        assertNotNull(sim.lastOf(EntryType.CLASS_CARD))
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertTrue(Machine.isSilent(sim.state, r))
        assertEquals(rome("2026-10-20T14:30"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-20T14:30")
        val t = sim.run(dhuhr)!!
        assertEquals(RunPhase.ACTIVE, t.phase)
        assertFalse(t.silent)
        assertEquals(rome("2026-10-20T14:50"), t.lockEndsAt)
        assertEquals(rome("2026-10-20T14:38"), t.doneAfterAt)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == t.id })
    }

    @Test
    fun confirmingDuringClassEndsIt() {
        val sim = Sim("2026-10-20T08:00", times = classes)
        sim.at("2026-10-20T13:10", Input.Adhan(Prayer.DHUHR))
        sim.at("2026-10-20T13:50", Input.Confirm(sim.runId(dhuhr)))
        assertNull(sim.run(dhuhr))
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
        sim.at("2026-10-20T14:30")
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().isEmpty())
    }

    @Test
    fun noClassNoCard() {
        val sim = Sim("2026-10-19T08:00", times = classes)
        sim.at("2026-10-19T13:10", Input.Adhan(Prayer.DHUHR))
        assertEquals(RunPhase.ACTIVE, sim.run(dhuhr)!!.phase)
    }

    @Test
    fun warningDuringClassReturnsSilently() {
        val sim = Sim("2026-10-20T08:00", times = classes)
        sim.seed(Prayer.ASR to "2026-10-19T14:00")
        sim.at("2026-10-20T13:05", Input.Adhan(Prayer.DHUHR))
        assertEquals(RunPhase.CARD, sim.run(dhuhr)!!.phase)
        sim.at("2026-10-20T13:45")
        val r = sim.run(dhuhr)!!
        assertEquals(RunPhase.RETURNED, r.phase)
        assertTrue(r.silent)
        sim.at("2026-10-20T14:00")
        assertEquals("window_closed", sim.lastOf(EntryType.MISSED)!!.reason)
    }

    @Test
    fun stageThreeDuringAClassIsSilentToo() {
        val sim = Sim("2026-10-20T08:00", times = classes)
        sim.stage(dhuhr, 3)
        sim.seed(Prayer.ASR to "2026-10-19T14:00")
        sim.at("2026-10-20T13:10", Input.Adhan(Prayer.DHUHR))
        val n = sim.lastEffects<Effect.Notify>().single()
        assertEquals(NotifyKind.REMINDER, n.kind)
        assertTrue(n.silent)
        assertTrue(Machine.isSilent(sim.state, sim.run(dhuhr)!!))
        // Its window warning, still in class, is silent as well.
        sim.at("2026-10-20T13:45")
        val w = sim.lastEffects<Effect.Notify>().single()
        assertEquals(NotifyKind.WARNING, w.kind)
        assertTrue(w.silent)
        // Outside a class the stage-3 notification sounds (unless QUIET).
        val free = Sim("2026-10-19T08:00", times = classes)
        free.stage(dhuhr, 3)
        free.at("2026-10-19T13:10", Input.Adhan(Prayer.DHUHR))
        assertFalse(free.lastEffects<Effect.Notify>().single().silent)
    }

    // ------------------------------------------------------------------ not okay, bail-out

    @Test
    fun notOkayShortensTheLockToTheEightMinuteMinimum() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val id = sim.runId(dhuhr)
        sim.at("2026-10-19T12:32", Input.NotOkay(id))
        val r = sim.run(dhuhr)!!
        assertTrue(r.shortened)
        assertEquals(rome("2026-10-19T12:38"), r.lockEndsAt)
        assertEquals(LockType.SCREEN, r.lock)
        val e = sim.lastOf(EntryType.STEPPED_DOWN)!!
        assertTrue(e.detail!!.contains("8-minute minimum"))
        // A second tap changes nothing.
        sim.step(Input.NotOkay(id))
        assertEquals(1, sim.of(EntryType.STEPPED_DOWN).size)
        sim.step(Input.StepDone(id, 0))
        sim.at("2026-10-19T12:38", Input.StepDone(id, 1))
        assertNull(sim.run(dhuhr))
        assertEquals("done", sim.lastOf(EntryType.COMPLETED)!!.reason)
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
    }

    @Test
    fun leavingBeforeTheMinimumIsABailOutButThePrayerStaysOpen() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val id = sim.runId(dhuhr)
        sim.at("2026-10-19T12:33", Input.BailOut(id))
        val r = sim.run(dhuhr)!!
        assertEquals(RunPhase.OPEN, r.phase)
        assertTrue(r.bailed)
        assertNotNull(sim.lastOf(EntryType.BAILED))
        val p = sim.lastOf(EntryType.PENALTY)!!
        assertEquals(120, p.minutes)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals(rome("2026-10-19T14:33"), sim.state.pool.penaltyUntil)
        assertEquals(listOf("bailed"), sim.state.stages[dhuhr]!!.recent)
        // Confirming later records the prayer but no second outcome.
        sim.at("2026-10-19T13:00", Input.Confirm(id))
        assertNull(sim.run(dhuhr))
        assertEquals(listOf(Prayer.DHUHR), sim.state.day!!.confirmed)
        assertEquals(listOf("bailed"), sim.state.stages[dhuhr]!!.recent)
    }

    @Test
    fun leavingAfterTheMinimumIsAReleaseWithoutPenalty() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val id = sim.runId(dhuhr)
        sim.at("2026-10-19T12:39", Input.BailOut(id))
        val r = sim.run(dhuhr)!!
        assertEquals(RunPhase.OPEN, r.phase)
        assertTrue(r.released)
        assertNotNull(sim.lastOf(EntryType.RELEASED))
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
        assertTrue(sim.of(EntryType.BAILED).isEmpty())
        sim.step(Input.Confirm(id))
        assertEquals("done", sim.lastOf(EntryType.COMPLETED)!!.reason)
    }

    @Test
    fun missAfterABailOutIsNotCountedTwice() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        sim.at("2026-10-19T12:31", Input.BailOut(sim.runId(dhuhr)))
        sim.at("2026-10-19T15:40", Input.Adhan(Prayer.ASR))
        assertEquals("next_adhan", sim.lastOf(EntryType.MISSED)!!.reason)
        assertEquals(listOf("bailed"), sim.state.stages[dhuhr]!!.recent)
        assertEquals(1, sim.state.missesInARow)
    }

    // ------------------------------------------------------------------ heads-up

    @Test
    fun headsUpTenMinutesBeforeYesterdaysAdhan() {
        val sim = Sim()
        sim.seed(Prayer.DHUHR to "2026-10-18T12:30")
        sim.at("2026-10-19T12:00")
        assertEquals(rome("2026-10-19T12:20"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T12:19")
        assertTrue(sim.of(EntryType.HEADS_UP).isEmpty())
        sim.at("2026-10-19T12:20")
        val h = sim.of(EntryType.HEADS_UP).single()
        assertEquals("dhuhr", h.prayer)
        assertEquals("2026-10-19T12:30:00+02:00", h.scheduled)
        val n = sim.lastEffects<Effect.Notify>().single()
        assertEquals("heads-up-dhuhr", n.id)
        assertEquals(NotifyKind.HEADS_UP, n.kind)
        assertTrue(n.silent)
        assertEquals("DHUHR IN 10 MIN", n.title)
        sim.at("2026-10-19T12:25")
        assertEquals(1, sim.of(EntryType.HEADS_UP).size)
        sim.at("2026-10-19T12:29", Input.Adhan(Prayer.DHUHR))
        assertTrue(sim.lastEffects<Effect.CancelNotify>().any { it.id == "heads-up-dhuhr" })
    }

    @Test
    fun headsUpFollowsTheSunAcrossTheClockChange() {
        val sim = Sim("2026-10-25T08:00")
        sim.seed(Prayer.DHUHR to "2026-10-24T12:40")
        // 12:40 CEST yesterday = 10:40 UTC; today that is 11:40 CET.
        sim.at("2026-10-25T11:29")
        assertTrue(sim.of(EntryType.HEADS_UP).isEmpty())
        sim.at("2026-10-25T11:30")
        assertEquals("2026-10-25T11:40:00+01:00", sim.of(EntryType.HEADS_UP).single().scheduled)
    }

    @Test
    fun windowEstimateAcrossTheClockChange() {
        val sim = Sim("2026-10-25T08:00")
        sim.seed(Prayer.ASR to "2026-10-24T15:30")
        sim.at("2026-10-25T11:45", Input.Adhan(Prayer.DHUHR))
        assertEquals(Instant.parse("2026-10-25T13:30:00Z"), sim.run(dhuhr)!!.windowEndsAt)
    }

    // ------------------------------------------------------------------ stages

    @Test
    fun stageThreeIsOneNotificationOnly() {
        val sim = Sim()
        sim.stage(dhuhr, 3)
        sim.seed(Prayer.DHUHR to "2026-10-18T12:30")
        sim.at("2026-10-19T12:20")
        assertTrue("no heads-up at stage 3", sim.of(EntryType.HEADS_UP).isEmpty())
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val r = sim.run(dhuhr)!!
        assertEquals(RunPhase.REMINDER, r.phase)
        assertEquals(LockType.NONE, r.lock)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().isEmpty())
        val n = sim.lastEffects<Effect.Notify>().single()
        assertEquals(NotifyKind.REMINDER, n.kind)
        assertFalse(n.silent)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertTrue(sim.decision() !is PoolDecision.Blocked || sim.blockReason() != BlockReason.RUN_LOCK)
        sim.at("2026-10-19T13:00", Input.Confirm(r.id))
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
    }

    @Test
    fun stageTwoIsGuided() {
        val sim = Sim()
        sim.stage(dhuhr, 2)
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val r = sim.run(dhuhr)!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertFalse(Machine.reasserts(r))
        assertNull(r.doneAfterAt)
        // The pool is blocked while it runs.
        assertEquals(BlockReason.RUN_LOCK, sim.blockReason())
        // No step min-timers: Done at once, and the quiet timer can be tapped through.
        sim.step(Input.StepDone(r.id, 0))
        sim.step(Input.StepDone(r.id, 1))
        assertTrue(sim.run(dhuhr)!!.confirmed)
        sim.step(Input.StepDone(r.id, 2))
        assertNull(sim.run(dhuhr))
        assertTrue(sim.decision() !is PoolDecision.Blocked || sim.blockReason() != BlockReason.RUN_LOCK)
    }

    @Test
    fun tenCleanPrayersPromote() {
        val sim = Sim()
        var t = rome("2026-10-19T08:00")
        repeat(10) {
            sim.now = t
            sim.step(Input.Adhan(Prayer.DHUHR))
            val id = sim.runId(dhuhr)
            sim.now = t.plusSeconds(8 * 60)
            sim.step(Input.Confirm(id))
            sim.now = t.plusSeconds(20 * 60)
            sim.step()
            t = t.plusSeconds(40 * 60)
        }
        val up = sim.of(EntryType.STAGE_UP).single()
        assertEquals(2, up.stage)
        assertEquals(StageMemory(2), sim.state.stages[dhuhr])
        assertEquals(2, Machine.status(sim.state, sim.env).stages[dhuhr])
    }

    @Test
    fun threeMissesInTheLastTenDemote() {
        val sim = Sim()
        sim.stage(dhuhr, 2)
        // Three days of Dhuhr left unconfirmed until Asr's adhan closes its window.
        for (day in listOf("2026-10-19", "2026-10-20", "2026-10-21")) {
            sim.at("${day}T12:30", Input.Adhan(Prayer.DHUHR))
            assertEquals(2, sim.run(dhuhr)!!.stage)
            sim.at("${day}T15:40", Input.Adhan(Prayer.ASR))
        }
        assertEquals(3, sim.of(EntryType.MISSED).count { it.protocol == dhuhr })
        val down = sim.of(EntryType.STAGE_DOWN).single()
        assertEquals(1, down.stage)
        assertEquals(dhuhr, down.protocol)
        assertEquals(StageMemory(1), sim.state.stages[dhuhr])
    }

    // ------------------------------------------------------------------ call, quiet, suspension, manual

    @Test
    fun aCallPausesThePrayerLock() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val id = sim.runId(dhuhr)
        sim.at("2026-10-19T12:35", Input.CallActive(true))
        assertNotNull(sim.lastOf(EntryType.CALL_PAUSE))
        sim.at("2026-10-19T12:52")
        assertEquals("the call never ends a protocol", RunPhase.ACTIVE, sim.run(dhuhr)!!.phase)
        assertEquals(rome("2026-10-19T13:07"), Machine.stepView(sim.state, id, sim.env)!!.lockEndsAt)
        sim.at("2026-10-19T12:55", Input.CallActive(false))
        val r = sim.run(dhuhr)!!
        assertEquals(rome("2026-10-19T13:10"), r.lockEndsAt)
        assertEquals(rome("2026-10-19T12:58"), r.doneAfterAt)
        assertEquals(20, sim.lastOf(EntryType.CALL_RESUME)!!.minutes)
        sim.at("2026-10-19T13:09")
        assertEquals(RunPhase.ACTIVE, sim.run(dhuhr)!!.phase)
        sim.at("2026-10-19T13:10")
        assertEquals(RunPhase.OPEN, sim.run(dhuhr)!!.phase)
    }

    @Test
    fun quietMakesEveryTakeoverSilent() {
        val sim = Sim()
        sim.step(Input.Toggle(dev.vigil.core.protocol.ToggleName.QUIET, true))
        assertEquals("on", sim.lastOf(EntryType.QUIET)!!.detail)
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        assertTrue(Machine.isSilent(sim.state, sim.run(dhuhr)!!))
        assertTrue(Machine.status(sim.state, sim.env).quiet)
    }

    @Test
    fun suspensionReleasesPrayersAndAdhansStillNeedConfirming() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        sim.at("2026-10-19T12:31", Input.Suspend(60))
        assertEquals(RunPhase.OPEN, sim.run(dhuhr)!!.phase)
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals(PoolDecision.Allowed, sim.decision())
        assertEquals("suspended", sim.lastOf(EntryType.KILL_SWITCH)!!.reason)
        sim.at("2026-10-19T13:00", Input.Adhan(Prayer.ASR))
        val asr = sim.run("prayer-asr")!!
        assertEquals(RunPhase.OPEN, asr.phase)
        assertTrue(sim.lastOf(EntryType.FIRED)!!.detail!!.contains("suspended"))
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().isEmpty())
        sim.at("2026-10-19T13:31")
        assertEquals("resumed", sim.lastOf(EntryType.KILL_SWITCH)!!.reason)
        assertEquals(RunPhase.OPEN, sim.run("prayer-asr")!!.phase)
    }

    @Test
    fun manualStartActsLikeTheAdhan() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Manual(dhuhr))
        assertEquals("manual", sim.lastOf(EntryType.ADHAN)!!.reason)
        assertEquals(RunPhase.ACTIVE, sim.run(dhuhr)!!.phase)
        assertEquals(rome("2026-10-19T12:30"), sim.state.prayers.lastAdhan[Prayer.DHUHR])
    }

    @Test
    fun testRunChangesNoMemory() {
        val sim = Sim()
        sim.at("2026-10-19T12:30", Input.Manual(dhuhr, test = true))
        val r = sim.state.runs.single()
        assertTrue(r.test)
        assertNull(sim.state.prayers.lastAdhan[Prayer.DHUHR])
        assertNull(sim.state.lastFired[dhuhr])
        sim.at("2026-10-19T12:31", Input.BailOut(r.id))
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
        assertTrue(sim.state.stages.isEmpty())
        assertTrue(sim.of(EntryType.BAILED).single().test == true)
    }
}
