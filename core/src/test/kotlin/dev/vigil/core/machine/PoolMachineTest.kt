package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.FreeWindowConfig
import dev.vigil.core.protocol.Lock
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ToggleName
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
        BundledProtocols.file.copy(pool = BundledProtocols.file.pool.copy(windows = windows.toList()))

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
        val sim = Sim()
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
        // Cooldown: 2 x the 2 minutes used in the last 30.
        assertEquals(rome("2026-10-19T08:16"), sim.state.pool.cooldownUntil)
        assertEquals(3, sim.state.pool.opens)
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
        val sim = Sim(file = fileWith(testProtocol("reset")))
        repeat(4) {
            sim.step(Input.Manual("reset"))
            sim.after(1, Input.BailOut(sim.runId("reset")))
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
    fun dayEndResetsTheCounters() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:30")
        sim.open()
        sim.after(2, Input.Foreground(home, false))
        sim.after(2)
        assertEquals(1, sim.state.pool.opens)
        sim.at("2026-10-19T23:30", Input.GoingToSleep)
        assertEquals(0, sim.state.pool.opens)
        assertEquals(0L, sim.state.pool.usedSeconds)
        assertTrue(sim.state.pool.windowUsed.isEmpty())
    }
}
