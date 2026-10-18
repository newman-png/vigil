package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.times.MyTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DayTest {
    @Test
    fun firstStartOpensTheDay() {
        val sim = Sim("2026-10-19T08:00")
        val start = sim.entries.single()
        assertEquals(EntryType.DAY_START, start.type)
        assertEquals("2026-10-19", start.day)
        assertEquals("first_start", start.reason)
        assertEquals(DayState("2026-10-19", rome("2026-10-19T08:00"), "first_start"), sim.state.day)
        // Installed in the small hours: the night still belongs to yesterday.
        assertEquals("2026-10-18", Sim("2026-10-19T03:00").state.day!!.key)
    }

    @Test
    fun goingToSleepEndsTheDay() {
        val sim = Sim()
        sim.at("2026-10-19T23:40", Input.GoingToSleep)
        val end = sim.of(EntryType.DAY_END).single()
        assertEquals("sleep_tap", end.reason)
        assertEquals("2026-10-19", end.day)
        assertEquals("2026-10-19T23:40:00+02:00", end.ts)
        val start = sim.of(EntryType.DAY_START).last()
        assertEquals("2026-10-20", start.day)
        assertEquals("sleep_tap", start.reason)
        assertEquals("2026-10-20", sim.state.day!!.key)
        assertEquals(rome("2026-10-19T23:40"), sim.state.day!!.startedAt)
        // After midnight it is still the day keyed the 20th; the wake alarm does not end it.
        sim.at("2026-10-20T07:00", Input.WakeAlarm)
        assertEquals(1, sim.of(EntryType.DAY_END).size)
    }

    @Test
    fun afterMidnightBelongsToThePreviousDay() {
        val sim = Sim()
        sim.at("2026-10-20T01:30")
        assertEquals("2026-10-19", sim.state.day!!.key)
        sim.step(Input.GoingToSleep)
        assertEquals("2026-10-20", sim.state.day!!.key)
        // The next fallback is the morning of the 21st.
        assertEquals(rome("2026-10-21T06:00"), Machine.status(sim.state, sim.env).dayEndsBy)
    }

    @Test
    fun idleAfterWindDownEndsTheDayWhenTheScreenWentOff() {
        val sim = Sim()
        sim.at("2026-10-19T22:30", Input.Screen(on = false))
        assertEquals(rome("2026-10-20T00:30"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-20T00:29")
        assertTrue(sim.of(EntryType.DAY_END).isEmpty())
        sim.at("2026-10-20T00:30")
        val end = sim.of(EntryType.DAY_END).single()
        assertEquals("idle", end.reason)
        assertEquals("2026-10-19T22:30:00+02:00", end.ts)
        assertEquals("noticed at 00:30", end.detail)
        assertEquals("2026-10-20", sim.state.day!!.key)
        assertEquals(rome("2026-10-19T22:30"), sim.state.day!!.startedAt)
    }

    @Test
    fun screenOffBeforeWindDownCountsFromWindDown() {
        val sim = Sim()
        // bedtime_goal 23:00: wind-down starts at 22:00.
        sim.at("2026-10-19T19:00", Input.Screen(on = false))
        sim.at("2026-10-19T21:59")
        assertTrue(sim.of(EntryType.DAY_END).isEmpty())
        sim.at("2026-10-20T00:00")
        val end = sim.of(EntryType.DAY_END).single()
        assertEquals("idle", end.reason)
        assertEquals("2026-10-19T22:00:00+02:00", end.ts)
    }

    @Test
    fun screenOnStopsTheIdleCount() {
        val sim = Sim()
        sim.at("2026-10-19T22:30", Input.Screen(on = false))
        sim.at("2026-10-19T23:30", Input.Screen(on = true))
        sim.at("2026-10-19T23:31", Input.Screen(on = false))
        sim.at("2026-10-20T01:30")
        assertTrue(sim.of(EntryType.DAY_END).isEmpty())
        sim.at("2026-10-20T01:31")
        assertEquals("2026-10-19T23:31:00+02:00", sim.of(EntryType.DAY_END).single().ts)
    }

    @Test
    fun windDownIsTheSleepRunsStartWhenTheFileHasOne() {
        val sleep = testProtocol("sleep", kind = ProtocolKind.SLEEP, lock = dev.vigil.core.protocol.Lock(dev.vigil.core.protocol.LockType.POOL))
        val sim = Sim(file = fileWith(sleep))
        sim.at("2026-10-19T22:30", Input.Screen(on = false))
        sim.at("2026-10-20T01:00")
        assertTrue("no sleep run yet, so no wind-down", sim.of(EntryType.DAY_END).isEmpty())
        val sim2 = Sim(file = fileWith(sleep))
        sim2.at("2026-10-19T21:00", Input.Manual("sleep"))
        sim2.at("2026-10-19T21:30", Input.Screen(on = false))
        sim2.at("2026-10-19T23:30")
        assertEquals("2026-10-19T21:30:00+02:00", sim2.of(EntryType.DAY_END).single().ts)
    }

    @Test
    fun wakeAlarmTheMorningAfterEndsTheDay() {
        val sim = Sim()
        sim.at("2026-10-20T05:30", Input.WakeAlarm)
        val end = sim.of(EntryType.DAY_END).single()
        assertEquals("wake_alarm", end.reason)
        assertEquals("2026-10-20", sim.state.day!!.key)
    }

    @Test
    fun fallbackAtSixAtTheLatest() {
        val sim = Sim()
        sim.at("2026-10-20T05:59")
        assertEquals("2026-10-19", sim.state.day!!.key)
        assertEquals(rome("2026-10-20T06:00"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-20T06:00")
        assertEquals("fallback", sim.of(EntryType.DAY_END).single().reason)
        assertEquals("2026-10-20", sim.state.day!!.key)
    }

    @Test
    fun aLongGapEndsEachDayInTurn() {
        val sim = Sim()
        sim.at("2026-10-22T10:00")
        assertEquals(listOf("2026-10-19", "2026-10-20", "2026-10-21"), sim.of(EntryType.DAY_END).map { it.day })
        assertTrue(sim.of(EntryType.DAY_END).all { it.reason == "fallback" })
        assertEquals("2026-10-22", sim.state.day!!.key)
        assertEquals(rome("2026-10-22T06:00"), sim.state.day!!.startedAt)
    }

    @Test
    fun clocksGoBack() {
        val sim = Sim("2026-10-24T08:00")
        sim.now = Instant.parse("2026-10-25T04:59:00Z")
        sim.step()
        assertEquals("2026-10-24", sim.state.day!!.key)
        sim.now = Instant.parse("2026-10-25T05:00:00Z") // 06:00 CET
        sim.step()
        val end = sim.of(EntryType.DAY_END).single()
        assertEquals("2026-10-25T06:00:00+01:00", end.ts)
        assertEquals("2026-10-25", sim.state.day!!.key)
        assertEquals(Instant.parse("2026-10-26T05:00:00Z"), Machine.status(sim.state, sim.env).dayEndsBy)
    }

    @Test
    fun sleepTapInTheRepeatedHour() {
        val sim = Sim("2026-10-24T08:00")
        // 02:30 CET on the 25th (the second 02:30), after the clocks went back.
        sim.now = Instant.parse("2026-10-25T01:30:00Z")
        sim.step(Input.GoingToSleep)
        assertEquals("2026-10-25T02:30:00+01:00", sim.of(EntryType.DAY_END).single().ts)
        assertEquals("2026-10-25", sim.state.day!!.key)
    }

    @Test
    fun myTimesApplyFromTheNextDay() {
        val sim = Sim()
        val late = MyTimes(bedtimeGoal = "23:59")
        sim.step(Input.EditMyTimes(late))
        assertEquals("applies from tomorrow", sim.lastOf(EntryType.MY_TIMES)!!.detail)
        assertEquals(MyTimes(), sim.state.times.current)
        assertEquals(late, sim.state.times.pending)
        sim.at("2026-10-19T23:00", Input.GoingToSleep)
        assertEquals(late, sim.state.times.current)
        assertNull(sim.state.times.pending)
        assertEquals("the MY TIMES changes apply from today", sim.of(EntryType.DAY_START).last().detail)
    }

    @Test
    fun theVeryFirstEditMayApplyNow() {
        val sim = Sim()
        val t = MyTimes(bedtimeGoal = "22:30")
        sim.step(Input.EditMyTimes(t, applyNow = true))
        assertEquals("applied now (first setup)", sim.lastOf(EntryType.MY_TIMES)!!.detail)
        assertEquals(t, sim.state.times.current)
        sim.step(Input.EditMyTimes(MyTimes(), applyNow = true))
        assertEquals(t, sim.state.times.current)
        assertEquals("applies from tomorrow", sim.lastOf(EntryType.MY_TIMES)!!.detail)
    }

    @Test
    fun selfCheckLogsWhatNeverFired() {
        val timed = testProtocol("review", trigger = "time:10:00")
        val sim = Sim(file = fileWith(timed))
        sim.state = sim.state.copy(prayers = sim.state.prayers.copy(lastAdhan = mapOf(Prayer.DHUHR to rome("2026-10-18T12:30"))))
        // The phone was off from 09:00 to 11:00: 10:00 is more than 30 minutes late, so it does not fire late.
        sim.at("2026-10-19T11:00")
        assertNull(sim.run("review"))
        sim.at("2026-10-19T23:00", Input.GoingToSleep)
        val notFired = sim.of(EntryType.NOT_FIRED)
        assertEquals(listOf("review", "prayer-dhuhr"), notFired.map { it.protocol })
        assertEquals("2026-10-19T10:00:00+02:00", notFired[0].scheduled)
        assertEquals("time", notFired[0].reason)
        assertEquals("no_adhan", notFired[1].reason)
        assertEquals("dhuhr", notFired[1].prayer)
        assertEquals("no adhan notification arrived (expected about 12:30)", notFired[1].detail)
    }

    @Test
    fun appEventsAreJournaled() {
        val sim = Sim()
        sim.step(Input.AppExit("LOW_MEMORY", rome("2026-10-19T07:12"), "pid 123"))
        val exit = sim.lastOf(EntryType.APP_EXIT)!!
        assertEquals("LOW_MEMORY", exit.reason)
        assertEquals("at 2026-10-19T07:12:00+02:00; pid 123", exit.detail)
        sim.step(Input.SpeedBump("uninstall", "waited 5:00"))
        assertEquals("uninstall", sim.lastOf(EntryType.SPEED_BUMP)!!.app)
        sim.at("2026-10-19T09:02", Input.Alarm(rome("2026-10-19T09:00")))
        val late = sim.lastOf(EntryType.ALARM_LATE)!!
        assertEquals(120L, late.seconds)
        assertEquals("2026-10-19T09:00:00+02:00", late.scheduled)
        sim.at("2026-10-19T09:30", Input.Alarm(rome("2026-10-19T09:29:30")))
        assertEquals(1, sim.of(EntryType.ALARM_LATE).size)
    }
}
