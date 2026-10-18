package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.Lock
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.Step
import dev.vigil.core.timeline.StartSource
import dev.vigil.core.timeline.StartStatus
import dev.vigil.core.timeline.Timeline
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The M1 timeline on its own: the bundled prayers (no wind-down or morning) plus each test's protocols. */
class TimelineTest {
    private fun fileWith(vararg extra: dev.vigil.core.protocol.Protocol) = dev.vigil.core.machine.fileWith(*extra, base = PRAYERS_ONLY)

    private val review = testProtocol("review", trigger = "time:21:30", lock = Lock(LockType.NONE))
    private val study = testProtocol("study", trigger = "slot:study", kind = ProtocolKind.STUDY, lock = Lock(LockType.POOL))
    private val times = MyTimes(study = listOf(Slot(listOf("mon"), "16:00", minutes = 100, label = "Analysis ch. 4")))

    private fun Sim.seed(vararg adhans: Pair<Prayer, String>) {
        state = state.copy(prayers = state.prayers.copy(lastAdhan = adhans.associate { (p, t) -> p to rome(t) }))
    }

    @Test
    fun theDaysPlannedStarts() {
        val sim = Sim(file = fileWith(review, study), times = times)
        sim.seed(Prayer.DHUHR to "2026-10-18T12:30", Prayer.ASR to "2026-10-18T15:40")
        val items = Machine.timeline(sim.state, sim.env)
        assertEquals(listOf("prayer-dhuhr", "prayer-asr", "study", "review"), items.map { it.start.protocolId })
        assertEquals(listOf(StartSource.ADHAN, StartSource.ADHAN, StartSource.SLOT, StartSource.TIME), items.map { it.start.source })
        assertEquals(rome("2026-10-19T12:30"), items[0].start.at)
        assertTrue(items[0].start.estimated)
        assertEquals("Analysis ch. 4", items[2].start.slot!!.label)
        assertTrue(items.all { it.status == StartStatus.PLANNED })
        assertEquals(rome("2026-10-19T12:30"), Machine.status(sim.state, sim.env).nextStart!!.start.at)
        assertEquals(rome("2026-10-19T12:20"), Machine.nextWake(sim.state, sim.env))
    }

    @Test
    fun timeTriggersFireOnTimeAndShowLastFired() {
        val sim = Sim(file = fileWith(review))
        assertEquals(rome("2026-10-19T21:30"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T21:30")
        val r = sim.run("review")!!
        assertEquals("time", r.reason)
        assertEquals(rome("2026-10-19T21:30"), r.planned)
        val fired = sim.lastOf(EntryType.FIRED)!!
        assertEquals("2026-10-19T21:30:00+02:00", fired.scheduled)
        val item = Machine.timeline(sim.state, sim.env).single()
        assertEquals(StartStatus.FIRED, item.status)
        assertEquals(rome("2026-10-19T21:30"), item.lastFired)
        // It fires once.
        sim.at("2026-10-19T21:40")
        assertEquals(1, sim.of(EntryType.FIRED).size)
    }

    @Test
    fun aLittleLateStillFiresTooLateDoesNot() {
        val late = Sim(file = fileWith(review))
        late.at("2026-10-19T21:59")
        assertNotNull(late.run("review"))
        val tooLate = Sim(file = fileWith(review))
        tooLate.at("2026-10-19T22:01")
        assertNull(tooLate.run("review"))
        assertEquals(StartStatus.NOT_FIRED, Machine.timeline(tooLate.state, tooLate.env).single().status)
    }

    @Test
    fun slotStartsCarryTheirRow() {
        val sim = Sim(file = fileWith(study), times = times)
        sim.at("2026-10-19T16:00")
        val r = sim.run("study")!!
        assertEquals("slot:study", r.reason)
        assertEquals("Analysis ch. 4", r.slot!!.label)
        assertEquals("study", r.slot!!.list)
        assertEquals(rome("2026-10-19T17:40"), r.slot!!.end)
        assertEquals(100, r.slot!!.minutes)
    }

    @Test
    fun startsBeforeTheDayBeganAreNotPlanned() {
        val sim = Sim("2026-10-19T17:00", file = fileWith(study, review), times = times)
        assertEquals(listOf("review"), Machine.timeline(sim.state, sim.env).map { it.start.protocolId })
    }

    @Test
    fun repeatedHourFiresOnce() {
        // 02:30 is before the night edge: the night after the 24th, in the hour that happens twice.
        val night = testProtocol("night", trigger = "time:02:30", lock = Lock(LockType.NONE))
        val sim = Sim("2026-10-24T08:00", file = fileWith(night))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), Machine.timeline(sim.state, sim.env).single().start.at)
        sim.now = Instant.parse("2026-10-25T00:30:00Z")
        sim.step()
        assertNotNull(sim.run("night"))
        sim.step(Input.BailOut(sim.runId("night")))
        sim.now = Instant.parse("2026-10-25T01:30:00Z")
        sim.step()
        assertEquals(1, sim.of(EntryType.FIRED).size)
    }

    @Test
    fun skippedGapTimeFiresAtTheEndOfTheGap() {
        val night = testProtocol("night", trigger = "time:02:30", lock = Lock(LockType.NONE))
        val sim = Sim("2026-03-28T08:00", file = fileWith(night))
        // 29 March: 02:00 -> 03:00, so 02:30 happens at 03:00 CEST.
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), Machine.timeline(sim.state, sim.env).single().start.at)
    }

    @Test
    fun nextClosesTheWindowAtTheNextStart() {
        val early = testProtocol("early", trigger = "time:10:00", lock = Lock(LockType.NONE))
        val sim = Sim(file = fileWith(early))
        val morning = Machine.windows(sim.state, sim.env).first()
        assertEquals(rome("2026-10-19T08:00"), morning.opensAt)
        assertEquals(rome("2026-10-19T10:00"), morning.closesAt)
        // Nothing planned after the window opens: it lasts until the day's latest end.
        val bare = Sim(file = PRAYERS_ONLY)
        assertEquals(rome("2026-10-20T06:00"), Machine.windows(bare.state, bare.env).first().closesAt)
    }

    @Test
    fun theMorningWindowNeedsTheMorningDoneAndAnHourSinceWaking() {
        // max(done:morning|woke+60,woke+60): the later of the morning's completion and an hour after waking.
        val morning = testProtocol("morning", kind = ProtocolKind.MORNING, lock = Lock(LockType.NONE), steps = listOf(Step("Up.")))
        val sim = Sim("2026-10-19T07:00", file = fileWith(morning))
        assertEquals(rome("2026-10-19T08:00"), Machine.windows(sim.state, sim.env).first().opensAt)
        sim.at("2026-10-19T07:30", Input.Manual("morning"))
        sim.at("2026-10-19T07:40", Input.StepDone(sim.runId("morning"), 0))
        // Done at 07:40, but not yet an hour after waking (no alarm dismissed: MY TIMES wake 07:00).
        assertEquals(rome("2026-10-19T08:00"), Machine.windows(sim.state, sim.env).first().opensAt)
        sim.at("2026-10-19T07:45")
        assertTrue(sim.decision() is PoolDecision.Blocked)
        sim.at("2026-10-19T08:00")
        assertEquals(PoolDecision.Wait(1), sim.decision())
        // Done later than an hour after waking: the window opens at the completion.
        val slow = Sim("2026-10-19T07:00", file = fileWith(morning))
        slow.at("2026-10-19T07:30", Input.Manual("morning"))
        slow.at("2026-10-19T08:20", Input.StepDone(slow.runId("morning"), 0))
        assertEquals(rome("2026-10-19T08:20"), Machine.windows(slow.state, slow.env).first().opensAt)
    }

    @Test
    fun startAnchorClosesTheEveningWindowWhenSleepStarts() {
        val sleep = testProtocol("sleep", kind = ProtocolKind.SLEEP, lock = Lock(LockType.POOL))
        val sim = Sim(file = fileWith(sleep))
        // With a sleep protocol, bedtime is tonight's target: the first night MY TIMES bedtime_start (00:30).
        assertEquals(rome("2026-10-19T23:30"), Machine.windows(sim.state, sim.env)[1].closesAt)
        sim.at("2026-10-19T21:00", Input.Manual("sleep"))
        assertEquals(rome("2026-10-19T21:00"), Machine.windows(sim.state, sim.env)[1].closesAt)
    }

    @Test
    fun anAnchorThatDoesNotExistTodayKeepsTheWindowShut() {
        val noDinner = MyTimes(meals = listOf(Slot(listOf("tue"), "19:30", label = "dinner")))
        val sim = Sim(file = PRAYERS_ONLY, times = noDinner)
        val evening = Machine.windows(sim.state, sim.env)[1]
        assertNull(evening.opensAt)
        sim.at("2026-10-19T21:00")
        // The morning window is still open (nothing planned closes it): only that one counts.
        assertTrue(sim.decision() is PoolDecision.Wait)
    }

    @Test
    fun prayerEstimatesAreWholeDaysAfterTheLastAdhan() {
        assertEquals(rome("2026-10-19T12:30"), Timeline.estimate(rome("2026-10-18T12:30"), rome("2026-10-19T06:00")))
        assertEquals(rome("2026-10-19T12:30"), Timeline.estimate(rome("2026-10-15T12:30"), rome("2026-10-19T06:00")))
        assertEquals(rome("2026-10-19T12:30"), Timeline.estimate(rome("2026-10-19T12:30"), rome("2026-10-19T06:00")))
        // Across the clock change the estimate keeps the sun's time, not the wall clock's.
        assertEquals(Instant.parse("2026-10-25T10:40:00Z"), Timeline.estimate(rome("2026-10-24T12:40"), rome("2026-10-25T06:00")))
    }

    @Test
    fun adhanItemsTurnFiredWhenTheAdhanComes() {
        val sim = Sim(file = PRAYERS_ONLY)
        sim.seed(Prayer.DHUHR to "2026-10-18T12:30")
        sim.at("2026-10-19T12:33", Input.Adhan(Prayer.DHUHR))
        val item = Machine.timeline(sim.state, sim.env).single()
        assertEquals(StartStatus.FIRED, item.status)
        assertEquals(rome("2026-10-19T12:33"), item.start.at)
        assertTrue(!item.start.estimated)
        assertEquals(rome("2026-10-19T12:33"), item.lastFired)
        // Without an adhan, half an hour after the estimate it shows as not fired.
        val quiet = Sim(file = PRAYERS_ONLY)
        quiet.seed(Prayer.DHUHR to "2026-10-18T12:30")
        quiet.at("2026-10-19T13:01")
        assertEquals(StartStatus.NOT_FIRED, Machine.timeline(quiet.state, quiet.env).single().status)
    }
}
