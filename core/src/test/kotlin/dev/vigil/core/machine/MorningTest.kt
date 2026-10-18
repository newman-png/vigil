package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.journal.Report
import dev.vigil.core.journal.ReportMeta
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.Lock
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.RunPhase
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3: VIGIL's wake alarm and the morning routine, plus the early-start and walking fixes. */
class MorningTest {
    private val morning = "morning"

    private fun Sim.night() = state.runs.firstOrNull { it.phase == RunPhase.NIGHT }

    private fun Sim.blocks(): List<BlockReason> = (decision() as? PoolDecision.Blocked)?.blocks?.map { it.reason } ?: emptyList()

    /** Asleep at 23:30 on Monday 19 October (the night lock until the Tuesday alarm). */
    private fun asleep(times: MyTimes = MyTimes()): Sim {
        val sim = Sim(times = times)
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        return sim
    }

    @Test
    fun theAlarmRingsAtWakeAndItsDismissalStartsTheMorning() {
        val sim = asleep()
        sim.at("2026-10-20T07:00")
        val ring = sim.lastEffects<Effect.RingWakeAlarm>().single()
        assertEquals(Effect.RingWakeAlarm(rome("2026-10-20T07:00"), 1, rome("2026-10-20T07:10")), ring)
        assertEquals("ring 1 of 3", sim.lastOf(EntryType.WAKE_ALARM)!!.detail)
        assertTrue(Machine.status(sim.state, sim.env).wakeAlarmRinging)
        assertNull(Machine.wakeAlarmAt(sim.state, sim.env))
        assertEquals(rome("2026-10-20T07:10"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-20T07:03", Input.WakeAlarm())
        // The day began at GOING TO SLEEP, so it goes on; the night lock ends; the morning starts.
        assertEquals(1, sim.of(EntryType.DAY_END).size)
        assertNull(sim.night())
        assertEquals("wake", sim.lastOf(EntryType.NIGHT_LOCK)!!.reason)
        val wake = sim.lastOf(EntryType.WAKE)!!
        assertEquals("dismissed", wake.reason)
        assertEquals(3, wake.minutes)
        assertEquals(rome("2026-10-20T07:03"), sim.state.day!!.woke)
        assertTrue(sim.lastEffects<Effect.StopWakeAlarm>().isNotEmpty())
        val r = sim.run(morning)!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals(LockType.POOL, r.lock)
        assertEquals("wake_alarm", r.reason)
        // Pool lock: the screen is free; the card shows the step.
        assertNull(Machine.activeRun(sim.state, sim.env))
        val card = sim.lastEffects<Effect.Notify>().single { it.runId == r.id }
        assertEquals(NotifyKind.RUN, card.kind)
        assertEquals("Get up: feet on the floor, walk to the alarm.", card.text)
        assertEquals(listOf(BlockReason.RUN_LOCK), sim.blocks().take(1))
        // Tomorrow's alarm is next.
        assertEquals(rome("2026-10-21T07:00"), Machine.wakeAlarmAt(sim.state, sim.env))
    }

    @Test
    fun theMorningStepsThenTheWindowOpensAfterAnHour() {
        val sim = asleep()
        sim.at("2026-10-20T07:00", Input.WakeAlarm())
        val id = sim.runId(morning)
        assertEquals(7, Machine.stepsFor(sim.run(morning)!!, sim.env).size)
        sim.after(1, Input.StepDone(id, 0))
        // Sun in your eyes: a 5-minute timer that ends by itself.
        assertEquals(rome("2026-10-20T07:06"), Machine.stepView(sim.state, id, sim.env)!!.timerEndsAt)
        sim.after(5)
        assertEquals(2, sim.run(morning)!!.step)
        assertEquals("Bathroom and brush.", sim.lastEffects<Effect.Notify>().last { it.runId == id }.text)
        sim.after(5, Input.StepDone(id, 2))
        sim.after(15, Input.StepDone(id, 3))
        sim.after(4, Input.StepDone(id, 4))
        assertEquals(listOf(BlockReason.RUN_LOCK), sim.blocks().take(1))
        // Chores 20, walk 15: done at 08:05. The window opens then (more than an hour after waking at 07:00).
        sim.after(20)
        sim.after(15)
        assertNull(sim.run(morning))
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
        assertEquals(rome("2026-10-20T08:05"), Machine.windows(sim.state, sim.env).first().opensAt)
        assertEquals(PoolDecision.Wait(1), sim.decision())
    }

    @Test
    fun aQuickMorningStillWaitsForAnHourAfterWaking() {
        val sim = asleep()
        sim.at("2026-10-20T07:10", Input.WakeAlarm())
        val id = sim.runId(morning)
        // Down the ladder to the single step: stand in the sun for 5 minutes, marked minimum.
        repeat(3) { sim.step(Input.NotOkay(id)) }
        assertEquals(Version.SINGLE, sim.run(morning)!!.version)
        assertTrue(Machine.stepView(sim.state, id, sim.env)!!.minimum)
        sim.after(5)
        assertNull(sim.run(morning))
        assertEquals("done", sim.lastOf(EntryType.COMPLETED)!!.reason)
        // Done at 07:15, woke at 07:10: the window opens at 08:10.
        assertEquals(rome("2026-10-20T08:10"), Machine.windows(sim.state, sim.env).first().opensAt)
        sim.at("2026-10-20T08:00")
        assertEquals(BlockReason.OUTSIDE_WINDOW, (sim.decision() as PoolDecision.Blocked).reason)
        sim.at("2026-10-20T08:10")
        assertEquals(PoolDecision.Wait(1), sim.decision())
    }

    @Test
    fun leavingTheMorningCostsThePenaltyAndTheWindowOpensAnHourAfterWaking() {
        val sim = asleep()
        sim.at("2026-10-20T07:00", Input.WakeAlarm())
        sim.after(5, Input.BailOut(sim.runId(morning)))
        assertEquals(120, sim.lastOf(EntryType.PENALTY)!!.minutes)
        assertEquals(rome("2026-10-20T08:00"), Machine.windows(sim.state, sim.env).first().opensAt)
        sim.at("2026-10-20T08:30")
        assertEquals(listOf(BlockReason.PENALTY), sim.blocks())
        sim.at("2026-10-20T09:05")
        assertEquals(PoolDecision.Wait(1), sim.decision())
    }

    @Test
    fun anUnansweredAlarmRingsAgainThenGivesUpAndTheMorningStartsAtUnlock() {
        val sim = asleep()
        sim.at("2026-10-20T07:00")
        // The app stopped it after 10 minutes and said so.
        sim.at("2026-10-20T07:10", Input.WakeAlarmUnanswered())
        assertTrue(sim.lastEffects<Effect.StopWakeAlarm>().isNotEmpty())
        assertEquals(rome("2026-10-20T07:15"), Machine.wakeAlarmAt(sim.state, sim.env))
        sim.at("2026-10-20T07:15")
        assertEquals(2, sim.lastEffects<Effect.RingWakeAlarm>().single().ring)
        // The engine ends a ring by itself too, should the app not report it.
        sim.at("2026-10-20T07:25")
        sim.at("2026-10-20T07:30")
        assertEquals(3, sim.lastEffects<Effect.RingWakeAlarm>().single().ring)
        sim.at("2026-10-20T07:40")
        assertEquals(1, sim.of(EntryType.WAKE_ALARM_UNANSWERED).size)
        assertEquals(3, sim.of(EntryType.WAKE_ALARM).size)
        assertNull(sim.run(morning))
        assertNotNull("the night lock goes on until wake + 120 or the first unlock", sim.night())
        assertEquals(rome("2026-10-21T07:00"), Machine.wakeAlarmAt(sim.state, sim.env))
        // A lock-screen glance is not waking up; unlocking is.
        sim.at("2026-10-20T07:50", Input.Screen(on = true, unlocked = false))
        assertNull(sim.run(morning))
        sim.after(1, Input.Screen(on = true, unlocked = true))
        assertNotNull(sim.run(morning))
        assertNull(sim.night())
        assertEquals("unlock", sim.lastOf(EntryType.NIGHT_LOCK)!!.reason)
        val wake = sim.lastOf(EntryType.WAKE)!!
        assertEquals("unlock", wake.reason)
        assertEquals(51, wake.minutes)
        // Only once.
        sim.after(5, Input.Screen(on = true, unlocked = true))
        assertEquals(1, sim.of(EntryType.WAKE).size)
    }

    @Test
    fun aWakeTimeBeforeTheFallbackEndsTheDayWithoutGoingToSleep() {
        // Wake 05:30, before the 06:00 fallback: without GOING TO SLEEP the day of the 19th still runs at 05:30.
        val sim = Sim(times = MyTimes(wake = MyTimes().wake.mapValues { "05:30" }))
        assertEquals(rome("2026-10-20T05:30"), Machine.wakeAlarmAt(sim.state, sim.env))
        sim.at("2026-10-20T05:30")
        assertEquals(1, sim.lastEffects<Effect.RingWakeAlarm>().size)
        assertEquals("2026-10-19", sim.state.day!!.key)
        sim.at("2026-10-20T05:32", Input.WakeAlarm())
        val end = sim.lastOf(EntryType.DAY_END)!!
        assertEquals("wake_alarm", end.reason)
        assertEquals("2026-10-20", sim.state.day!!.key)
        assertNotNull(sim.run(morning))
        assertEquals("no_sleep_tap", sim.lastOf(EntryType.NIGHT_MISSED)!!.reason)
        // The earliest allowed wake (04:00) works the same way.
        val early = Sim(times = MyTimes(wake = MyTimes().wake.mapValues { "04:00" }))
        early.at("2026-10-20T04:00")
        assertEquals(rome("2026-10-20T04:00"), early.lastEffects<Effect.RingWakeAlarm>().single().scheduledAt)
    }

    @Test
    fun earlyTimeStartsBelongToTheDayRunningThen() {
        // Before the fix a 05:30 start (between the 05:00 night edge and the 06:00 fallback) belonged to no day.
        val dawn = testProtocol("dawn", trigger = "time:05:30", lock = Lock(LockType.NONE), steps = listOf(dev.vigil.core.protocol.Step("Up.")))
        val sim = Sim(file = fileWith(dawn, base = PRAYERS_ONLY))
        assertTrue(Machine.timeline(sim.state, sim.env).any { it.start.protocolId == "dawn" && it.start.at == rome("2026-10-20T05:30") })
        sim.at("2026-10-20T05:30")
        assertEquals("2026-10-19", sim.state.day!!.key)
        assertNotNull(sim.run("dawn"))
        // After GOING TO SLEEP it is the new day's own morning start: once either way.
        val slept = Sim(file = fileWith(dawn, base = PRAYERS_ONLY))
        slept.at("2026-10-19T23:30", Input.GoingToSleep())
        slept.at("2026-10-20T05:30")
        assertEquals(1, slept.of(EntryType.FIRED).count { it.protocol == "dawn" })
        slept.at("2026-10-20T06:30")
        assertEquals(1, slept.of(EntryType.FIRED).count { it.protocol == "dawn" })
        // Phone off over it: the self-check still finds it.
        val missed = Sim(file = fileWith(dawn, base = PRAYERS_ONLY))
        missed.at("2026-10-20T06:30")
        assertTrue(missed.of(EntryType.NOT_FIRED).any { it.protocol == "dawn" })
    }

    @Test
    fun onAClassDayTheCommuteIsTheWalk() {
        val classes = MyTimes(classes = listOf(Slot(listOf("tue"), "13:00", "14:30", label = "Analysis")))
        val sim = asleep(classes)
        sim.at("2026-10-20T07:00", Input.WakeAlarm())
        val r = sim.run(morning)!!
        assertTrue(r.classDay)
        val steps = Machine.stepsFor(r, sim.env)
        assertEquals(6, steps.size)
        assertFalse(steps.any { it.skipOnClassDays })
        // A commute row counts the same.
        val commute = asleep(MyTimes(commute = listOf(Slot(listOf("tue"), "11:30", label = "uni"))))
        commute.at("2026-10-20T07:00", Input.WakeAlarm())
        assertEquals(6, Machine.stepsFor(commute.run(morning)!!, commute.env).size)
    }

    @Test
    fun thursdayIsShorterAndFridayHasTheJummahShower() {
        val thu = Sim("2026-10-22T06:30")
        thu.at("2026-10-22T07:00", Input.WakeAlarm())
        val steps = Machine.stepsFor(thu.run(morning)!!, thu.env)
        assertEquals(listOf("Get up: feet on the floor, walk to the alarm.", "Sun in your eyes: stand at a window or outside.", "Bathroom and brush.", "Shower.", "Breakfast: cook, eat, clean."), steps.map { it.text })
        val fri = Sim("2026-10-23T06:30")
        fri.at("2026-10-23T07:00", Input.WakeAlarm())
        assertTrue(Machine.stepsFor(fri.run(morning)!!, fri.env).any { it.text.startsWith("Shower for Jummah") })
    }

    @Test
    fun aTestRingPreviewsTheMorningAndChangesNothingElse() {
        val sim = Sim()
        sim.step(Input.RingWakeAlarmTest)
        val ring = sim.lastEffects<Effect.RingWakeAlarm>().single()
        assertTrue(ring.test)
        assertEquals(sim.now.plusSeconds(600), ring.stopAt)
        sim.after(1, Input.WakeAlarm(test = true))
        val r = sim.run(morning)!!
        assertTrue(r.test)
        assertEquals(1, sim.of(EntryType.DAY_START).size)
        assertEquals(WakeAlarmState(), sim.state.wakeAlarm)
        assertNull(sim.state.day!!.woke)
        assertTrue(sim.lastOf(EntryType.WAKE)!!.test == true)
        sim.step(Input.WakeAlarmUnanswered(test = true))
        assertEquals(WakeAlarmState(), sim.state.wakeAlarm)
    }

    @Test
    fun anAlarmThePhoneSleptThroughDoesNotRingLateAndIsLogged() {
        val sim = asleep()
        sim.at("2026-10-20T08:00")
        assertTrue(sim.lastEffects<Effect.RingWakeAlarm>().isEmpty())
        val nf = sim.lastOf(EntryType.NOT_FIRED)!!
        assertEquals("wake_alarm", nf.reason)
        assertEquals("2026-10-20T07:00:00+02:00", nf.scheduled)
        assertEquals(rome("2026-10-21T07:00"), Machine.wakeAlarmAt(sim.state, sim.env))
        // A little late still rings.
        val late = asleep()
        late.at("2026-10-20T07:20")
        assertEquals(rome("2026-10-20T07:00"), late.lastEffects<Effect.RingWakeAlarm>().single().scheduledAt)
    }

    @Test
    fun tomorrowsAlarmUsesTomorrowsTimes() {
        val sim = Sim()
        sim.step(Input.EditMyTimes(MyTimes(wake = MyTimes().wake.mapValues { "06:30" })))
        assertEquals(rome("2026-10-20T06:30"), Machine.status(sim.state, sim.env).wakeAlarmAt)
        // Without a sleep or wake_alarm protocol VIGIL rings no alarm.
        val none = Sim(file = PRAYERS_ONLY)
        assertNull(Machine.wakeAlarmAt(none.state, none.env))
        assertNull(Machine.status(none.state, none.env).wakeAlarmAt)
        none.at("2026-10-20T07:00")
        assertTrue(none.effectsOf<Effect.RingWakeAlarm>().isEmpty())
    }

    @Test
    fun wakeTimesOutsideFourToElevenAreRejected() {
        val bad = MyTimes(wake = MyTimes().wake + ("mon" to "03:30") + ("tue" to "11:30"))
        val problems = bad.problems()
        assertTrue(problems.any { it.startsWith("Wake MON: 03:30 is outside 04:00 to 11:00") })
        assertTrue(problems.any { it.startsWith("Wake TUE: 11:30") })
        assertTrue(MyTimes(wake = MyTimes().wake.mapValues { "11:00" }).problems().isEmpty())
    }

    @Test
    fun theWalkingSwitchEndsByItselfAfterThreeHours() {
        val walk = testProtocol("walk", trigger = "toggle:walking", kind = ProtocolKind.WALK, lock = Lock(LockType.POOL))
        val sim = Sim(file = fileWith(walk, base = PRAYERS_ONLY))
        sim.at("2026-10-19T10:00", Input.Toggle(ToggleName.WALKING, true))
        assertEquals(rome("2026-10-19T13:00"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T12:59")
        assertTrue(sim.state.toggles.walking)
        sim.at("2026-10-19T13:30")
        assertFalse(sim.state.toggles.walking)
        val off = sim.lastOf(EntryType.TOGGLE)!!
        assertEquals("off (by itself after 3 hours)", off.detail)
        assertEquals("2026-10-19T13:00:00+02:00", off.ts)
        assertEquals("walk", sim.lastOf(EntryType.COMPLETED)!!.protocol)
    }

    @Test
    fun theReportShowsSleepWakeTargetsAndMissedNights() {
        val sim = Sim()
        sim.at("2026-10-19T23:30")
        sim.at("2026-10-19T23:40", Input.GoingToSleep())
        sim.at("2026-10-20T07:04", Input.WakeAlarm())
        sim.at("2026-10-21T06:00")
        val days = Report.days(sim.entries)
        assertEquals("2026-10-20T00:30:00+02:00", days[0].sleepTarget)
        assertEquals(-50, days[0].slept!!.minutes)
        assertEquals(4, days[1].woke!!.minutes)
        assertEquals("no_sleep_tap", days[1].nightMissed)
        val md = Report.markdown(sim.entries, ReportMeta(rome("2026-10-21T08:00"), ROME))
        assertTrue(md, md.contains("- **Sleep** target 00:30 · slept 23:40 (50 min before the target)"))
        assertTrue(md, md.contains("- **Sleep** target 00:10 · woke 07:04 (4 min after the alarm) · missed night: no GOING TO SLEEP"))
        assertTrue(md, md.contains("- Sleep: 1 night logged, 1 missed (no GOING TO SLEEP 1); woke 1 time, on average 4 min after the alarm; 0 alarms unanswered"))
        val csv = Report.csv(sim.entries)
        assertTrue(csv.contains(",sleep,sleep,"))
        assertTrue(csv.contains(",wake,morning,"))
        assertTrue(csv.contains(",sleep_target,sleep,"))
        assertTrue(csv.contains(",night_missed,sleep,"))
    }
}
