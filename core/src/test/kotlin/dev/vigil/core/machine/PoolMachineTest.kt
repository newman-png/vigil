package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.FreeWindowConfig
import dev.vigil.core.protocol.Lock
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.ToggleName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolMachineTest {
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val home = "com.nothing.launcher"

    /** Opens a session for [app]: enter, wait the full wait, done. */
    private fun Sim.open(app: String = ig) {
        step(Input.Foreground(app, true))
        val wait = (decision() as PoolDecision.Wait).minutes
        step(Input.PoolWaitStarted(app))
        afterSeconds(wait * 60L, Input.PoolWaitDone(app))
    }

    private fun Sim.blocked(): PoolDecision.Blocked = decision() as PoolDecision.Blocked

    private fun windowsFile(vararg windows: FreeWindowConfig) =
        M5_FILE.copy(pool = M5_FILE.pool.copy(windows = windows.toList()))

    @Test
    fun enteringThePoolAsksForTheWait() {
        val sim = Sim()
        sim.at("2026-10-19T08:30", Input.Foreground(ig, true))
        assertEquals(listOf(Effect.PoolGate(ig, PoolDecision.Wait(1))), sim.last!!.effects)
        assertTrue(sim.last!!.entries.isEmpty())
        // Repeated reports of the same app do not ask again.
        sim.step(Input.Foreground(ig, true))
        assertTrue(sim.last!!.effects.isEmpty())
        // A non-pool app is always allowed.
        assertEquals(PoolDecision.Allowed, sim.decision(isPool = false))
    }

    @Test
    fun aCompletedWaitStartsASession() {
        val sim = Sim()
        sim.at("2026-10-19T08:30", Input.Foreground(ig, true))
        sim.step(Input.PoolWaitStarted(ig))
        assertEquals(1, sim.lastOf(EntryType.POOL_WAIT)!!.minutes)
        assertNotNull(sim.state.pool.wait)
        sim.afterSeconds(30, Input.PoolWaitDone(ig))
        assertNull("too early", sim.state.pool.session)
        sim.afterSeconds(28, Input.PoolWaitDone(ig))
        val open = sim.lastOf(EntryType.POOL_OPEN)!!
        assertEquals(ig, open.app)
        assertEquals(1, open.minutes)
        assertEquals("open 1 today (morning window)", open.detail)
        assertEquals(1, sim.state.pool.opens)
        assertEquals("morning", sim.state.pool.session!!.windowId)
        assertEquals(PoolDecision.Allowed, sim.decision())
        assertEquals("POOL IN USE · 0 MIN · MORNING 30 MIN LEFT", Machine.poolStatus(sim.state, sim.env))
    }

    @Test
    fun sessionSurvivesAppSwitchesAndShortGapsThenEndsWithACooldown() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:30")
        sim.open()
        sim.after(2, Input.Foreground(yt, true))
        assertEquals(yt, sim.state.pool.session!!.app)
        sim.after(3, Input.Foreground(home, false))
        assertNotNull(sim.state.pool.session!!.leftAt)
        sim.afterSeconds(59, Input.Foreground(ig, true))
        val s = sim.state.pool.session!!
        assertNull(s.leftAt)
        assertEquals(300, s.usedSeconds)
        sim.after(1, Input.Screen(on = false))
        sim.afterSeconds(59)
        assertNotNull(sim.state.pool.session)
        sim.afterSeconds(1)
        assertNull(sim.state.pool.session)
        val session = sim.lastOf(EntryType.POOL_SESSION)!!
        assertEquals(360L, session.seconds)
        assertEquals(6, session.minutes)
        assertEquals(ig, session.app)
        val cool = sim.lastOf(EntryType.COOLDOWN)!!
        assertEquals(12, cool.minutes)
        assertEquals("session", cool.reason)
        // The cooldown counts from the moment the pool was left.
        val left = rome("2026-10-19T08:37").plusSeconds(59)
        assertEquals(left.plusSeconds(720), sim.state.pool.cooldownUntil)
        assertEquals(360L, sim.state.pool.windowUsed["morning"])
        assertEquals(360L, sim.state.pool.usedSeconds)
        assertEquals(BlockReason.COOLDOWN, sim.blocked().reason)
        assertEquals("POOL BLOCKED · COOLDOWN UNTIL 08:49", Machine.poolStatus(sim.state, sim.env))
        sim.at("2026-10-19T08:50", Input.Screen(on = true))
        assertEquals(PoolDecision.Wait(2), sim.decision())
        assertEquals("POOL OPEN · MORNING 24 MIN LEFT · WAIT 2 MIN", Machine.poolStatus(sim.state, sim.env))
    }

    @Test
    fun abandonedWaitKeepsTheSameWait() {
        val sim = Sim()
        sim.at("2026-10-19T08:30", Input.Foreground(ig, true))
        sim.step(Input.PoolWaitStarted(ig))
        sim.afterSeconds(20, Input.PoolWaitAbandoned(ig))
        val e = sim.lastOf(EntryType.POOL_WAIT_ABANDONED)!!
        assertEquals(1, e.minutes)
        assertEquals(20L, e.seconds)
        assertNull(sim.state.pool.wait)
        assertEquals(PoolDecision.Wait(1), sim.decision())
        // A wait done without a wait in progress does nothing.
        sim.step(Input.PoolWaitDone(ig))
        assertNull(sim.state.pool.session)
    }

    @Test
    fun outsideEveryWindowIsBlockedAndLogged() {
        val sim = Sim(file = PRAYERS_ONLY)
        // Yesterday's Dhuhr puts today's estimate at 12:30, where the morning window closes ("next").
        sim.state = sim.state.copy(prayers = sim.state.prayers.copy(lastAdhan = mapOf(Prayer.DHUHR to rome("2026-10-18T12:30"))))
        val windows = Machine.windows(sim.state, sim.env)
        assertEquals(rome("2026-10-19T08:00"), windows[0].opensAt)
        assertEquals(rome("2026-10-19T12:30"), windows[0].closesAt)
        assertEquals(rome("2026-10-19T20:15"), windows[1].opensAt)
        assertEquals(rome("2026-10-19T22:00"), windows[1].closesAt)
        sim.at("2026-10-19T13:00", Input.Foreground(ig, true))
        val gate = sim.lastEffects<Effect.PoolGate>().single()
        assertEquals(BlockReason.OUTSIDE_WINDOW, (gate.decision as PoolDecision.Blocked).reason)
        val e = sim.lastOf(EntryType.POOL_BLOCKED)!!
        assertEquals("outside_window", e.reason)
        assertEquals("2026-10-19T20:15:00+02:00", e.until)
        assertEquals(ig, e.app)
        assertEquals("POOL CLOSED · NO FREE WINDOW · NEXT WINDOW 20:15", Machine.poolStatus(sim.state, sim.env))
        sim.at("2026-10-19T22:00")
        assertEquals("POOL CLOSED · NO FREE WINDOW · NO MORE WINDOWS TODAY", Machine.poolStatus(sim.state, sim.env))
    }

    @Test
    fun windowCapReachedMidSessionKicks() {
        val sim = Sim(file = windowsFile(FreeWindowConfig("w", "08:00", "12:00", 5)))
        sim.now = rome("2026-10-19T08:30")
        sim.open()
        assertEquals(rome("2026-10-19T08:36"), Machine.nextWake(sim.state, sim.env))
        sim.after(4)
        assertNotNull(sim.state.pool.session)
        sim.after(1)
        assertNull(sim.state.pool.session)
        assertEquals(listOf(Effect.Kick(ig, BlockReason.WINDOW_CAP)), sim.lastEffects<Effect.Kick>())
        val b = sim.lastOf(EntryType.POOL_BLOCKED)!!
        assertEquals("window_cap", b.reason)
        assertTrue(b.detail!!.startsWith("session ended"))
        assertEquals(300L, sim.lastOf(EntryType.POOL_SESSION)!!.seconds)
        assertEquals(BlockReason.WINDOW_CAP, sim.blocked().reason)
    }

    @Test
    fun windowClosingMidSessionKicks() {
        val sim = Sim(file = windowsFile(FreeWindowConfig("w", "08:00", "09:00", 30)))
        sim.now = rome("2026-10-19T08:54")
        sim.open()
        sim.at("2026-10-19T09:00")
        assertEquals(listOf(Effect.Kick(ig, BlockReason.OUTSIDE_WINDOW)), sim.lastEffects<Effect.Kick>())
        assertEquals(10, sim.lastOf(EntryType.COOLDOWN)!!.minutes)
    }

    @Test
    fun noWaitThatWouldOutlastTheWindow() {
        val sim = Sim(file = windowsFile(FreeWindowConfig("w", "08:00", "09:00", 30)))
        sim.state = sim.state.copy(pool = sim.state.pool.copy(opens = 4))
        // A 5-minute wait from 08:50 ends at 08:55, inside the window: it opens, even though another 5-minute wait
        // would no longer fit when it ends.
        sim.at("2026-10-19T08:50", Input.Foreground(ig, true))
        assertEquals(listOf(Effect.PoolGate(ig, PoolDecision.Wait(5))), sim.lastEffects<Effect.PoolGate>())
        sim.step(Input.PoolWaitStarted(ig))
        sim.after(5, Input.PoolWaitDone(ig))
        assertNotNull(sim.state.pool.session)
        assertEquals("open 5 today (w window)", sim.lastOf(EntryType.POOL_OPEN)!!.detail)
        sim.after(1, Input.Foreground(home, false)) // a minute used: cooldown until 08:58
        // At 08:58 the next wait (6 minutes) would end after 09:00: refused at once instead of waited for in vain.
        sim.at("2026-10-19T08:58", Input.Foreground(ig, true))
        val gate = sim.lastEffects<Effect.PoolGate>().single().decision as PoolDecision.Blocked
        assertEquals(BlockReason.OUTSIDE_WINDOW, gate.reason)
        assertEquals("POOL CLOSED · NO FREE WINDOW · NO MORE WINDOWS TODAY", Machine.poolStatus(sim.state, sim.env))
    }

    @Test
    fun tenContinuousMinutesIsHeavyUse() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:30")
        sim.open()
        assertEquals(rome("2026-10-19T08:41"), Machine.nextWake(sim.state, sim.env))
        sim.after(9)
        assertNotNull(sim.state.pool.session)
        sim.after(1)
        assertNull(sim.state.pool.session)
        val h = sim.lastOf(EntryType.HEAVY_USE)!!
        assertEquals("continuous", h.reason)
        assertEquals(10, h.minutes)
        assertEquals(listOf(Effect.Kick(ig, BlockReason.HEAVY_USE)), sim.lastEffects<Effect.Kick>())
        val cool = sim.lastOf(EntryType.COOLDOWN)!!
        assertEquals("heavy_use", cool.reason)
        assertEquals(20, cool.minutes)
        assertEquals(1, sim.of(EntryType.COOLDOWN).size)
        val b = sim.blocked()
        assertEquals(BlockReason.HEAVY_USE, b.reason)
        assertEquals(rome("2026-10-19T09:01"), b.until)
        assertEquals("POOL BLOCKED · HEAVY USE UNTIL 09:01", Machine.poolStatus(sim.state, sim.env))
    }

    @Test
    fun shortGapsCountAsContinuous() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:30")
        sim.open()
        sim.after(5, Input.Foreground(home, false))
        sim.afterSeconds(30, Input.Foreground(ig, true))
        sim.after(5)
        assertEquals("continuous", sim.lastOf(EntryType.HEAVY_USE)!!.reason)
    }

    @Test
    fun thirdOpenWithinThirtyMinutesIsHeavyUse() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:00")
        sim.open() // opens at 08:01
        sim.after(1, Input.Foreground(home, false))
        sim.after(1) // gap over: session ended at 08:02, cooldown 2 min
        assertEquals(rome("2026-10-19T08:04"), sim.state.pool.cooldownUntil)
        sim.at("2026-10-19T08:04")
        sim.step(Input.Foreground(home, false))
        sim.open() // 2-minute wait: opens at 08:06
        sim.after(1, Input.Foreground(home, false))
        sim.after(1)
        sim.at("2026-10-19T08:09")
        sim.open() // 3-minute wait: opens at 08:12, the third open in 11 minutes
        val h = sim.lastOf(EntryType.HEAVY_USE)!!
        assertEquals("opens", h.reason)
        assertEquals(2, h.minutes)
        assertNull(sim.state.pool.session)
        assertEquals(listOf(Effect.Kick(ig, BlockReason.HEAVY_USE)), sim.lastEffects<Effect.Kick>())
        // 2 x the 2 minutes used in the last 30 would end at 08:16, but any open before 08:36 (when the 08:06 open
        // leaves the span) would be the third within 30 minutes again: kicked the moment its wait ended. So the pool
        // stays shut, as HEAVY USE, until 08:36.
        assertEquals(rome("2026-10-19T08:36"), sim.state.pool.cooldownUntil)
        assertEquals(3, sim.state.pool.opens)
        sim.at("2026-10-19T08:20")
        val b = sim.blocked()
        assertEquals(BlockReason.HEAVY_USE, b.reason)
        assertEquals(rome("2026-10-19T08:36"), b.until)
        sim.at("2026-10-19T08:36")
        sim.open() // 4-minute wait: opens at 08:40, only the second open within 30 minutes
        assertNotNull(sim.state.pool.session)
        assertEquals(1, sim.of(EntryType.HEAVY_USE).size)
    }

    @Test
    fun aRunLockEndsTheSessionAndBlocks() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:30")
        sim.open()
        sim.at("2026-10-19T08:35", Input.Adhan(Prayer.FAJR))
        assertEquals(listOf(Effect.Kick(ig, BlockReason.RUN_LOCK)), sim.lastEffects<Effect.Kick>())
        assertEquals("run_lock", sim.lastOf(EntryType.POOL_BLOCKED)!!.reason)
        val b = sim.blocked()
        assertEquals(BlockReason.RUN_LOCK, b.reason)
        assertEquals(rome("2026-10-19T08:55"), b.until)
        assertEquals("POOL BLOCKED · FAJR UNTIL 08:55", Machine.poolStatus(sim.state, sim.env))
    }

    @Test
    fun aWaitThatEndsInsideALockDoesNotOpen() {
        val sim = Sim()
        sim.at("2026-10-19T08:30", Input.Foreground(ig, true))
        sim.step(Input.PoolWaitStarted(ig))
        sim.afterSeconds(30, Input.Adhan(Prayer.FAJR))
        sim.afterSeconds(30, Input.PoolWaitDone(ig))
        assertNull(sim.state.pool.session)
        assertEquals(BlockReason.RUN_LOCK, (sim.lastEffects<Effect.PoolGate>().single().decision as PoolDecision.Blocked).reason)
        assertEquals(0, sim.state.pool.opens)
    }

    @Test
    fun poolLockRunsBlockToo() {
        val sim = Sim(file = fileWith(testProtocol("study", lock = Lock(LockType.POOL, 50, null))))
        sim.step(Input.Manual("study"))
        assertNull(Machine.activeRun(sim.state, sim.env))
        assertEquals(BlockReason.RUN_LOCK, sim.blocked().reason)
        assertEquals(NotifyKind.RUN, sim.lastEffects<Effect.Notify>().single().kind)
        val none = Sim(file = fileWith(testProtocol("free", lock = Lock(LockType.NONE))))
        none.step(Input.Manual("free"))
        assertTrue(none.decision() is PoolDecision.Wait)
    }

    @Test
    fun friendsAndWalkingBlock() {
        val sim = Sim()
        sim.step(Input.Toggle(ToggleName.FRIENDS, true))
        assertEquals(BlockReason.FRIENDS, sim.blocked().reason)
        assertEquals("POOL BLOCKED · YOU'RE WITH FRIENDS", Machine.poolStatus(sim.state, sim.env))
        sim.step(Input.Toggle(ToggleName.WALKING, true))
        assertEquals(listOf(BlockReason.FRIENDS, BlockReason.WALKING), sim.blocked().blocks.map { it.reason })
        sim.step(Input.Toggle(ToggleName.FRIENDS, false))
        sim.step(Input.Toggle(ToggleName.WALKING, false))
        assertTrue(sim.decision() is PoolDecision.Wait)
        assertEquals(listOf("on", "on", "off", "off"), sim.of(EntryType.TOGGLE).map { it.detail })
    }

    @Test
    fun bailOutPenaltyStacksWithADailyCap() {
        val sim = Sim(file = fileWith(testProtocol("alpha")))
        repeat(4) {
            sim.step(Input.Manual("alpha"))
            sim.after(1, Input.BailOut(sim.runId("alpha")))
        }
        assertEquals(listOf(120, 120, 120, 0), sim.of(EntryType.PENALTY).map { it.minutes })
        assertEquals(rome("2026-10-19T14:01"), sim.state.pool.penaltyUntil)
        assertEquals(BlockReason.PENALTY, sim.blocked().reason)
        assertEquals(4, sim.of(EntryType.BAILED).size)
    }

    @Test
    fun suspendedPoolIsOpen() {
        val sim = Sim()
        sim.step(Input.Toggle(ToggleName.FRIENDS, true))
        sim.step(Input.Suspend(60))
        assertEquals(PoolDecision.Allowed, sim.decision())
        sim.step(Input.Foreground(ig, true))
        assertTrue(sim.last!!.effects.isEmpty())
        assertEquals("POOL OPEN · VIGIL SUSPENDED UNTIL 09:00", Machine.poolStatus(sim.state, sim.env))
        sim.at("2026-10-19T08:30", Input.Resume)
        assertEquals("ended early", sim.lastOf(EntryType.KILL_SWITCH)!!.detail)
        assertEquals(BlockReason.FRIENDS, sim.blocked().reason)
    }

    @Test
    fun aSessionAliveAtGoingToSleepCountsForThatDay() {
        val sim = Sim(file = windowsFile(FreeWindowConfig("evening", "20:00", "23:00", 30)))
        sim.now = rome("2026-10-19T22:00")
        sim.open() // from 22:01
        sim.after(4, Input.Foreground("dev.vigil", false)) // 4 minutes used; the 60-second gap starts at 22:05
        sim.afterSeconds(20, Input.GoingToSleep())
        assertNull(sim.state.pool.session)
        val types = sim.types()
        assertTrue(types.lastIndexOf(EntryType.POOL_SESSION) < types.lastIndexOf(EntryType.DAY_END))
        assertEquals(240L, sim.lastOf(EntryType.POOL_SESSION)!!.seconds)
        // The new day's evening window starts untouched; the cooldown (from 22:05) still runs.
        sim.after(1)
        assertTrue(sim.state.pool.windowUsed.isEmpty())
        assertEquals(0L, sim.state.pool.usedSeconds)
        assertEquals(rome("2026-10-19T22:13"), sim.state.pool.cooldownUntil)
    }

    @Test
    fun aSessionInTheAppAtTheDayEndIsClosed() {
        // A window that is open until the day's latest end (04:00 is the night after the day).
        val sim = Sim(file = windowsFile(FreeWindowConfig("night", "04:00", "next", 30)))
        sim.now = rome("2026-10-20T05:50")
        sim.open() // from 05:51
        sim.at("2026-10-20T06:00")
        assertEquals("2026-10-20", sim.state.day!!.key)
        assertNull(sim.state.pool.session)
        assertEquals(listOf(Effect.Kick(ig, BlockReason.OUTSIDE_WINDOW)), sim.lastEffects<Effect.Kick>())
        assertEquals("session ended: the day ended", sim.lastOf(EntryType.POOL_BLOCKED)!!.detail)
        assertEquals(540L, sim.lastOf(EntryType.POOL_SESSION)!!.seconds)
        assertTrue(sim.types().lastIndexOf(EntryType.POOL_SESSION) < sim.types().lastIndexOf(EntryType.DAY_END))
        assertTrue(sim.state.pool.windowUsed.isEmpty())
    }

    @Test
    fun aPoolAppOpenedWhileSuspendedIsGatedWhenTheSuspensionEnds() {
        val sim = Sim()
        sim.at("2026-10-19T09:00", Input.Suspend(60))
        sim.step(Input.Foreground(ig, true))
        assertTrue(sim.last!!.effects.isEmpty())
        // Still scrolling when the hour is up: no session, so the wait screen comes up.
        sim.at("2026-10-19T10:00")
        assertEquals(listOf(Effect.PoolGate(ig, PoolDecision.Wait(1))), sim.lastEffects<Effect.PoolGate>())
        // Resuming early does the same, once.
        val early = Sim()
        early.at("2026-10-19T09:00", Input.Suspend(60))
        early.step(Input.Foreground(yt, true))
        early.after(10, Input.Resume)
        assertEquals(listOf(Effect.PoolGate(yt, PoolDecision.Wait(1))), early.lastEffects<Effect.PoolGate>())
        // Out of the pool when it ends: nothing to gate.
        val left = Sim()
        left.at("2026-10-19T09:00", Input.Suspend(60))
        left.step(Input.Foreground(ig, true))
        left.after(5, Input.Foreground(home, false))
        left.at("2026-10-19T10:00")
        assertTrue(left.lastEffects<Effect.PoolGate>().isEmpty())
    }

    @Test
    fun suspendingEndsTheLiveSession() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:30")
        sim.open() // from 08:31
        sim.after(8, Input.Suspend(60))
        assertNull(sim.state.pool.session)
        assertEquals(480L, sim.lastOf(EntryType.POOL_SESSION)!!.seconds)
        assertEquals(rome("2026-10-19T08:55"), sim.state.pool.cooldownUntil)
        // Scrolling on while suspended counts for nothing ...
        sim.after(1, Input.Foreground(ig, true))
        assertTrue(sim.last!!.effects.isEmpty())
        // ... and is not heavy use when the hour is up: the app in front just gets the next wait.
        sim.at("2026-10-19T09:39")
        assertTrue(sim.of(EntryType.HEAVY_USE).isEmpty())
        assertTrue(sim.lastEffects<Effect.Kick>().isEmpty())
        assertEquals(listOf(Effect.PoolGate(ig, PoolDecision.Wait(2))), sim.lastEffects<Effect.PoolGate>())
        assertEquals(480L, sim.state.pool.windowUsed["morning"])
    }

    @Test
    fun friendsModeEndsAfterItsCap() {
        val sim = Sim()
        sim.at("2026-10-19T14:00", Input.Toggle(ToggleName.FRIENDS, true))
        assertEquals(rome("2026-10-19T18:00"), Machine.nextWake(sim.state, sim.env))
        sim.at("2026-10-19T17:59")
        assertEquals(BlockReason.FRIENDS, sim.blocked().reason)
        sim.at("2026-10-19T18:30")
        assertTrue(!sim.state.toggles.friends)
        assertNull(sim.state.toggles.friendsSince)
        val off = sim.lastOf(EntryType.TOGGLE)!!
        assertEquals("off (the 240-minute cap)", off.detail)
        assertEquals("2026-10-19T18:00:00+02:00", off.ts)
        assertTrue(sim.decision() is PoolDecision.Wait)
        // A friends protocol's cap_min sets the cap, and its run completes with the switch.
        val friends = testProtocol("friends", trigger = "toggle:friends", kind = ProtocolKind.FRIENDS, lock = Lock(LockType.POOL))
            .copy(params = JsonObject(mapOf("cap_min" to JsonPrimitive(60))))
        val withProtocol = Sim(file = fileWith(friends))
        withProtocol.at("2026-10-19T14:00", Input.Toggle(ToggleName.FRIENDS, true))
        assertNotNull(withProtocol.run("friends"))
        withProtocol.at("2026-10-19T15:00")
        assertNull(withProtocol.run("friends"))
        assertEquals("friends", withProtocol.lastOf(EntryType.COMPLETED)!!.protocol)
        assertEquals("off (the 60-minute cap)", withProtocol.lastOf(EntryType.TOGGLE)!!.detail)
    }

    @Test
    fun dayEndResetsTheCounters() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:30")
        sim.open()
        sim.after(2, Input.Foreground(home, false))
        sim.after(2)
        assertEquals(1, sim.state.pool.opens)
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        assertEquals(0, sim.state.pool.opens)
        assertEquals(0L, sim.state.pool.usedSeconds)
        assertTrue(sim.state.pool.windowUsed.isEmpty())
    }
}
