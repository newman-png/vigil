package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.journal.Report
import dev.vigil.core.journal.ReportMeta
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.run.Run
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Design M8 (extras): wellness nudges, at times that feel random, and the movement nudge. */
class NudgeTest {
    /**
     * The bundled nudge protocol with its v2.0 numbers: the bundled file has the nudges off since v2.1 (per_day 0, no
     * move_nudges), but the rules stay tested.
     */
    private val nudge: Protocol = BundledProtocols.file.find("nudge")!!.let {
        it.copy(
            params = JsonObject(
                it.params + ("per_day" to JsonPrimitive(4)) +
                    ("move_nudges" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("Stand up and walk for a minute.")))),
            ),
        )
    }
    private val file = fileWith(nudge)

    /** The nudge protocol without timed nudges (the movement tests). */
    private val moveOnly = fileWith(nudge.copy(params = JsonObject(nudge.params + ("per_day" to JsonPrimitive(0)))))

    private fun Sim.nudge(): Run? = state.runs.firstOrNull { it.protocol == "nudge" }

    private fun Sim.times(): List<Pair<Instant, String>> = View(state, env).nudgeTimes(file.find("nudge")!!)

    private fun Sim.reach(at: Instant, input: Input = Input.Tick) {
        now = at
        step(input)
    }

    @Test
    fun theDaysNudgesAreSpreadAndFixedByTheDay() {
        val sim = Sim(file = file)
        val times = sim.times()
        assertEquals(4, times.size)
        // From wake + 90 (08:30) to the wind-down (tonight's target 00:30 - 60 = 23:30), at least 90 minutes apart.
        assertTrue(!times.first().first.isBefore(rome("2026-10-19T08:30")))
        assertTrue(times.last().first.isBefore(rome("2026-10-19T23:30")))
        times.zipWithNext().forEach { (a, b) -> assertTrue(Duration.between(a.first, b.first) >= Duration.ofMinutes(90)) }
        // The lines take turns.
        assertEquals(3, times.map { it.second }.distinct().size)
        assertTrue(times.all { it.second in nudge.textListParam("nudges") })
        // A restart (the state read back, later) does not reshuffle them; another day has other times.
        val later = MachineEnv(rome("2026-10-19T15:00"), ROME, file)
        assertEquals(times, View(MachineState.decode(sim.state.encode()), later).nudgeTimes(nudge))
        sim.at("2026-10-19T23:40", Input.GoingToSleep())
        assertNotEquals(times.map { Duration.between(rome("2026-10-19T00:00"), it.first) }, sim.times().map { Duration.between(rome("2026-10-20T00:00"), it.first) })
        assertEquals(rome("2026-10-20T08:30").isAfter(sim.times().first().first), false)
    }

    @Test
    fun aNudgeHoldsTheScreenForItsSecondsAndEndsByItself() {
        val sim = Sim(file = file)
        val (at, line) = sim.times()[0]
        assertEquals(at, Machine.nextWake(sim.state, sim.env))
        sim.reach(at)
        val r = sim.nudge()!!
        assertEquals(line, r.label)
        assertEquals(LockType.SCREEN, r.lock)
        assertEquals(r.id, Machine.activeRun(sim.state, sim.env)?.id)
        assertTrue(sim.lastEffects<Effect.ShowTakeover>().any { it.runId == r.id })
        assertEquals(Intensity.FULL, Machine.intensity(r, sim.file))
        val shown = sim.lastOf(EntryType.NUDGE_SHOWN)!!
        assertEquals("timed", shown.reason)
        assertEquals(line, shown.detail)
        assertEquals(45L, shown.seconds)
        assertEquals(at.plusSeconds(45), Machine.stepView(sim.state, r.id, sim.env)!!.lockEndsAt)
        // A nudge never blocks the pool, and is never in the protocol counts.
        assertTrue(sim.of(EntryType.FIRED).isEmpty())
        assertEquals(at.plusSeconds(45), Machine.nextWake(sim.state, sim.env))
        sim.afterSeconds(45)
        assertNull(sim.nudge())
        val done = sim.lastOf(EntryType.NUDGE_DONE)!!
        assertEquals("time", done.reason)
        assertEquals(45L, done.seconds)
        assertTrue(sim.of(EntryType.COMPLETED).isEmpty())
        assertTrue(sim.state.stages.isEmpty())
        assertEquals(0, sim.state.missesInARow)
        assertEquals(sim.times()[1].first, Machine.nextWake(sim.state, sim.env))
    }

    @Test
    fun aNudgeIsSkippedNotQueuedWhenSomethingElseIsGoingOn() {
        val probe = Sim(file = file)
        val times = probe.times()
        // A class over the third nudge (MY TIMES classes do not move the nudges).
        val local = times[2].first.atZone(ROME).toLocalTime()
        val classes = MyTimes(classes = listOf(Slot(listOf("mon"), local.minusMinutes(5).toString(), local.plusMinutes(30).toString(), label = "Analysis")))
        val sim = Sim(file = file, times = classes)
        assertEquals(times, sim.times())
        sim.reach(times[0].first.minusSeconds(60), Input.Toggle(ToggleName.QUIET, true))
        sim.reach(times[0].first)
        assertNull(sim.nudge())
        assertEquals("quiet", sim.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
        // Not queued: QUIET off does not bring it back.
        sim.after(1, Input.Toggle(ToggleName.QUIET, false))
        assertNull(sim.nudge())
        sim.reach(times[1].first.minusSeconds(60), Input.Screen(false))
        sim.reach(times[1].first)
        assertEquals("screen_off", sim.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
        sim.reach(times[2].first.minusSeconds(60), Input.Screen(true, unlocked = true))
        sim.reach(times[2].first)
        assertEquals("class", sim.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
        sim.reach(times[3].first.minusSeconds(60), Input.CallActive(true))
        sim.reach(times[3].first)
        assertEquals("call", sim.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
        assertEquals(4, sim.of(EntryType.NUDGE_SKIPPED).size)
        assertTrue(sim.of(EntryType.NUDGE_SHOWN).isEmpty())
        // The phone off at a nudge's time and back on more than 10 minutes later: late, skipped.
        val off = Sim(file = file)
        off.reach(times[0].first.plus(Duration.ofMinutes(11)))
        assertEquals("late", off.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
        assertEquals(times[0].first, Instant.parse(java.time.OffsetDateTime.parse(off.lastOf(EntryType.NUDGE_SKIPPED)!!.scheduled!!).toInstant().toString()))
    }

    @Test
    fun walkingFriendsAnotherRunAndTheGapSkipANudge() {
        val times = Sim(file = file).times()
        val walk = Sim(file = file)
        walk.reach(times[0].first.minusSeconds(60), Input.Toggle(ToggleName.WALKING, true))
        walk.reach(times[0].first)
        assertEquals("walking", walk.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
        val friends = Sim(file = file)
        friends.reach(times[0].first.minusSeconds(60), Input.Toggle(ToggleName.FRIENDS, true))
        friends.reach(times[0].first)
        assertEquals("friends", friends.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
        val busy = Sim(file = file)
        busy.reach(times[0].first.minusSeconds(60), Input.Manual("reset"))
        busy.reach(times[0].first)
        assertEquals("busy", busy.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
        // Another nudge shown less than min_gap_min ago (the movement nudge): skipped.
        val gap = Sim(file = file)
        gap.reach(times[0].first.minus(Duration.ofMinutes(30)))
        gap.state = gap.state.copy(day = gap.state.day!!.copy(nudgedAt = gap.now))
        gap.reach(times[0].first)
        assertEquals("gap", gap.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
    }

    @Test
    fun notOkayLeaveAProtocolAndACallEndANudgeAtNoCost() {
        val times = Sim(file = file).times()
        val sim = Sim(file = file)
        sim.reach(times[0].first)
        sim.step(Input.NotOkay(sim.nudge()!!.id))
        assertNull(sim.nudge())
        assertEquals("not_okay", sim.lastOf(EntryType.NUDGE_DONE)!!.reason)
        sim.reach(times[1].first)
        sim.afterSeconds(5, Input.BailOut(sim.nudge()!!.id))
        assertNull(sim.nudge())
        assertEquals("left", sim.lastOf(EntryType.NUDGE_DONE)!!.reason)
        assertEquals(5L, sim.lastOf(EntryType.NUDGE_DONE)!!.seconds)
        assertTrue(sim.of(EntryType.PENALTY).isEmpty())
        assertTrue(sim.of(EntryType.BAILED).isEmpty())
        assertTrue(sim.of(EntryType.STEPPED_DOWN).isEmpty())
        // Any protocol that needs the screen: the nudge gives way.
        sim.reach(times[2].first)
        sim.afterSeconds(10, Input.Manual("reset"))
        assertNull(sim.nudge())
        assertEquals("gave_way", sim.lastOf(EntryType.NUDGE_DONE)!!.reason)
        assertEquals("reset", Machine.activeRun(sim.state, sim.env)?.protocol)
        sim.step(Input.BailOut(sim.runId("reset")))
        // A call: the nudge ends.
        val call = Sim(file = file)
        call.reach(times[0].first)
        call.afterSeconds(10, Input.CallActive(true))
        assertNull(call.nudge())
        assertEquals("call", call.lastOf(EntryType.NUDGE_DONE)!!.reason)
        // The kill switch: it ends, and nudges are skipped while suspended.
        val kill = Sim(file = file)
        kill.reach(times[0].first)
        kill.afterSeconds(10, Input.Suspend(600))
        assertEquals("kill_switch", kill.lastOf(EntryType.NUDGE_DONE)!!.reason)
        kill.reach(times[1].first)
        assertEquals("suspended", kill.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
        // A prayer's adhan: the nudge ends and the prayer takes the screen.
        val prayer = Sim(file = file)
        prayer.reach(times[0].first)
        prayer.afterSeconds(10, Input.Adhan(dev.vigil.core.protocol.Prayer.DHUHR))
        assertEquals("gave_way", prayer.lastOf(EntryType.NUDGE_DONE)!!.reason)
        assertEquals("prayer-dhuhr", Machine.activeRun(prayer.state, prayer.env)?.protocol)
    }

    @Test
    fun theMovementNudgeComesAfterNinetyMinutesWithoutStepsWhileThePhoneIsInUse() {
        val sim = Sim(file = moveOnly)
        sim.step(Input.Screen(true, unlocked = true))
        sim.at("2026-10-19T09:00", Input.Steps(1000))
        assertEquals(rome("2026-10-19T10:30"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T10:30")
        val r = sim.nudge()!!
        assertEquals("Stand up and walk for a minute.", r.label)
        assertEquals("still", sim.lastOf(EntryType.NUDGE_SHOWN)!!.reason)
        assertEquals(60L, sim.lastOf(EntryType.NUDGE_SHOWN)!!.seconds)
        sim.afterSeconds(60)
        assertNull(sim.nudge())
        // A step moves the next one on; a locked phone gets none; unlocked again it comes a minute later.
        sim.at("2026-10-19T11:00", Input.Steps(1200))
        sim.at("2026-10-19T12:20", Input.Screen(true, unlocked = false))
        sim.at("2026-10-19T12:31")
        assertNull(sim.nudge())
        sim.at("2026-10-19T13:00", Input.Screen(true, unlocked = true))
        assertEquals(rome("2026-10-19T13:01"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T13:01")
        assertTrue(sim.nudge() != null)
        // Without a step counter there is no movement nudge.
        val none = Sim(file = moveOnly)
        none.step(Input.Screen(true, unlocked = true))
        none.at("2026-10-19T09:00", Input.Steps(null))
        none.at("2026-10-19T13:00")
        assertTrue(none.of(EntryType.NUDGE_SHOWN).isEmpty())
    }

    @Test
    fun aTestNudgeAndTheReport() {
        val sim = Sim(file = file)
        sim.at("2026-10-19T08:10", Input.Manual("nudge", test = true))
        val r = sim.nudge()!!
        assertTrue(r.test)
        assertEquals("test", sim.lastOf(EntryType.NUDGE_SHOWN)!!.reason)
        assertNull(sim.state.day!!.nudgedAt)
        sim.afterSeconds(45)
        // Behind a protocol on the screen it is skipped.
        sim.step(Input.Manual("reset"))
        sim.step(Input.Manual("nudge", test = true))
        assertEquals("busy", sim.lastOf(EntryType.NUDGE_SKIPPED)!!.reason)
        sim.step(Input.BailOut(sim.runId("reset")))
        val times = sim.times()
        sim.reach(times[0].first)
        sim.afterSeconds(45)
        val md = Report.markdown(sim.entries, ReportMeta(sim.now, ROME))
        assertTrue(md, md.contains("- Nudges: 1 nudge shown"))
    }

    @Test
    fun theClockChangeDayStillSpreadsItsNudges() {
        val sim = Sim("2026-10-25T07:30", file = file)
        val times = sim.times()
        assertEquals(4, times.size)
        assertTrue(!times.first().first.isBefore(java.time.OffsetDateTime.parse("2026-10-25T08:30+01:00").toInstant()))
        times.zipWithNext().forEach { (a, b) -> assertTrue(Duration.between(a.first, b.first) >= Duration.ofMinutes(90)) }
        sim.reach(times[0].first)
        assertTrue(sim.nudge() != null)
    }

    @Test
    fun theNudgeStateSurvivesEncodeDecode() {
        val sim = Sim(file = file)
        sim.reach(sim.times()[0].first)
        assertEquals(sim.state, MachineState.decode(sim.state.encode()))
        assertEquals(1, sim.state.day!!.nudgesDue)
    }
}
