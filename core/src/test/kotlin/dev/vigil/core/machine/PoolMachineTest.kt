package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolRules
import dev.vigil.core.pool.PoolSession
import dev.vigil.core.pool.PoolState
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
import java.time.Instant

/** v2.1: the pool is a daily budget of 90 minutes, a flat 1-minute wait before each open, and a break after 30 minutes in a row. */
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

    /** Uses the pool for [minutes], then leaves it until the session is over. */
    private fun Sim.use(minutes: Long) {
        open()
        after(minutes, Input.Foreground(home, false))
        after(1)
        assertNull(state.pool.session)
    }

    private fun Sim.blocked(): PoolDecision.Blocked = decision() as PoolDecision.Blocked

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
        assertEquals("POOL · 90 MIN LEFT TODAY", Machine.poolStatus(sim.state, sim.env))
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
        assertEquals("open 1 today (90 min left today)", open.detail)
        assertEquals(1, sim.state.pool.opens)
        assertEquals(PoolDecision.Allowed, sim.decision())
        assertEquals("POOL IN USE · 0 MIN · 90 MIN LEFT TODAY", Machine.poolStatus(sim.state, sim.env))
        sim.after(12)
        assertEquals("POOL IN USE · 12 MIN · 78 MIN LEFT TODAY", Machine.poolStatus(sim.state, sim.env))
    }

    @Test
    fun sessionSurvivesAppSwitchesAndShortGapsAndNoCooldownFollows() {
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
        assertEquals(360L, sim.state.pool.usedSeconds)
        // No cooldown: the next open only waits its minute.
        assertTrue(sim.of(EntryType.COOLDOWN).isEmpty())
        sim.step(Input.Screen(on = true))
        assertEquals(PoolDecision.Wait(1), sim.decision())
        assertEquals("POOL · 84 MIN LEFT TODAY", Machine.poolStatus(sim.state, sim.env))
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
    fun thePoolOpensAtAnyTimeNothingBlocksIt() {
        val sim = Sim(file = PRAYERS_ONLY)
        for (t in listOf("2026-10-19T08:10", "2026-10-19T13:00", "2026-10-19T17:45", "2026-10-19T21:30")) {
            sim.at(t)
            assertEquals(t, PoolDecision.Wait(1), sim.decision())
        }
        // Every open waits the same minute, however many came before.
        sim.now = rome("2026-10-19T14:00")
        repeat(4) { sim.use(2) }
        assertEquals(4, sim.state.pool.opens)
        assertEquals(PoolDecision.Wait(1), sim.decision())
    }

    @Test
    fun theDailyBudgetRunsOutAndClosesTheApp() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:30")
        repeat(3) { sim.use(25) }
        assertEquals(75 * 60L, sim.state.pool.usedSeconds)
        assertEquals("POOL · 15 MIN LEFT TODAY", Machine.poolStatus(sim.state, sim.env))
        sim.open() // 09:51, wait until 09:52
        assertEquals(rome("2026-10-19T10:07"), Machine.nextWake(sim.state, sim.env))
        sim.after(14)
        assertNotNull(sim.state.pool.session)
        sim.after(1)
        assertNull(sim.state.pool.session)
        assertEquals(listOf(Effect.Kick(ig, BlockReason.BUDGET)), sim.lastEffects<Effect.Kick>())
        val b = sim.lastOf(EntryType.POOL_BLOCKED)!!
        assertEquals("budget", b.reason)
        assertEquals("session ended: TODAY'S 90 MINUTES ARE USED", b.detail)
        assertEquals(900L, sim.lastOf(EntryType.POOL_SESSION)!!.seconds)
        assertEquals(90 * 60L, sim.state.pool.usedSeconds)
        assertEquals(BlockReason.BUDGET, sim.blocked().reason)
        assertNull(sim.blocked().until)
        assertEquals("POOL BLOCKED · TODAY'S 90 MINUTES ARE USED", Machine.poolStatus(sim.state, sim.env))
        // Coming back is refused at once, all day.
        sim.after(3, Input.Foreground(home, false))
        sim.step(Input.Foreground(ig, true))
        assertEquals(BlockReason.BUDGET, (sim.lastEffects<Effect.PoolGate>().single().decision as PoolDecision.Blocked).reason)
        sim.at("2026-10-19T21:00")
        assertEquals(BlockReason.BUDGET, sim.blocked().reason)
        // A new day has its 90 minutes again.
        sim.at("2026-10-19T23:40", Input.GoingToSleep())
        assertEquals(0L, sim.state.pool.usedSeconds)
        assertEquals(90 * 60L, PoolRules.leftSeconds(sim.file.pool, sim.state.pool, sim.now))
        assertTrue(sim.blocked().blocks.none { it.reason == BlockReason.BUDGET })
    }

    @Test
    fun thirtyContinuousMinutesIsEnoughForNowAndABreak() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:30")
        sim.open()
        assertEquals(rome("2026-10-19T09:01"), Machine.nextWake(sim.state, sim.env))
        sim.after(29)
        assertNotNull(sim.state.pool.session)
        sim.after(1)
        assertNull(sim.state.pool.session)
        val h = sim.lastOf(EntryType.HEAVY_USE)!!
        assertEquals("continuous", h.reason)
        assertEquals(30, h.minutes)
        assertEquals(listOf(Effect.Kick(ig, BlockReason.HEAVY_USE)), sim.lastEffects<Effect.Kick>())
        val rest = sim.lastOf(EntryType.COOLDOWN)!!
        assertEquals("heavy_use", rest.reason)
        assertEquals(15, rest.minutes)
        val b = sim.blocked()
        assertEquals(BlockReason.HEAVY_USE, b.reason)
        assertEquals(rome("2026-10-19T09:16"), b.until)
        assertEquals("POOL BLOCKED · ENOUGH FOR NOW UNTIL 09:16", Machine.poolStatus(sim.state, sim.env))
        assertEquals(30 * 60L, sim.state.pool.usedSeconds)
        sim.at("2026-10-19T09:16")
        assertEquals(PoolDecision.Wait(1), sim.decision())
        assertEquals("POOL · 60 MIN LEFT TODAY", Machine.poolStatus(sim.state, sim.env))
    }

    @Test
    fun shortGapsCountAsContinuous() {
        val sim = Sim()
        sim.now = rome("2026-10-19T08:30")
        sim.open()
        sim.after(15, Input.Foreground(home, false))
        sim.afterSeconds(30, Input.Foreground(ig, true))
        sim.after(15)
        assertEquals("continuous", sim.lastOf(EntryType.HEAVY_USE)!!.reason)
        // Many short sessions are not heavy use: only the budget counts them.
        val many = Sim()
        many.now = rome("2026-10-19T08:30")
        repeat(5) { many.use(10) }
        assertTrue(many.of(EntryType.HEAVY_USE).isEmpty())
        assertEquals(50 * 60L, many.state.pool.usedSeconds)
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
    fun aCallNeverOpensAPoolThatWasBlocked() {
        // A pool lock of 10 minutes from 08:00; a call from 08:05 to 08:25 pauses it (it then ends at 08:30).
        val sim = Sim(file = fileWith(testProtocol("study", lock = Lock(LockType.POOL, 10, null))))
        sim.step(Input.Manual("study"))
        sim.at("2026-10-19T08:05", Input.CallActive(true))
        assertEquals(BlockReason.RUN_LOCK, sim.blocked().reason)
        // Past the lock's own end, still in the call: still blocked, and a pool app is refused.
        sim.at("2026-10-19T08:20")
        assertEquals(BlockReason.RUN_LOCK, sim.blocked().reason)
        sim.step(Input.Foreground(ig, true))
        assertEquals(BlockReason.RUN_LOCK, (sim.lastEffects<Effect.PoolGate>().single().decision as PoolDecision.Blocked).reason)
        assertEquals("run_lock", sim.lastOf(EntryType.POOL_BLOCKED)!!.reason)
        sim.step(Input.Foreground(home, false))
        sim.at("2026-10-19T08:25", Input.CallActive(false))
        sim.at("2026-10-19T08:29")
        assertEquals(BlockReason.RUN_LOCK, sim.blocked().reason)
        sim.at("2026-10-19T08:31")
        assertEquals(PoolDecision.Wait(1), sim.decision())

        // The switches, a penalty and a used-up budget hold through a call just the same.
        val friends = Sim()
        friends.step(Input.Toggle(ToggleName.FRIENDS, true))
        friends.after(1, Input.CallActive(true))
        friends.after(30)
        assertEquals(BlockReason.FRIENDS, friends.blocked().reason)
        val bailed = Sim(file = fileWith(testProtocol("alpha")))
        bailed.step(Input.Manual("alpha"))
        bailed.after(1, Input.BailOut(bailed.runId("alpha")))
        bailed.after(1, Input.CallActive(true))
        bailed.after(20)
        assertEquals(BlockReason.PENALTY, bailed.blocked().reason)
        val spent = Sim()
        spent.state = spent.state.copy(pool = spent.state.pool.copy(usedSeconds = 90 * 60L))
        spent.after(1, Input.CallActive(true))
        assertEquals(BlockReason.BUDGET, spent.blocked().reason)

        // A lock that starts during a call closes a live session as usual.
        val live = Sim(file = fileWith(testProtocol("study", lock = Lock(LockType.POOL, 10, null))))
        live.now = rome("2026-10-19T08:30")
        live.open()
        live.after(2, Input.CallActive(true))
        live.after(1, Input.Manual("study"))
        assertNull(live.state.pool.session)
        assertEquals(listOf(Effect.Kick(ig, BlockReason.RUN_LOCK)), live.lastEffects<Effect.Kick>())
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
        assertEquals("POOL BLOCKED · BAIL-OUT PENALTY UNTIL 14:01", Machine.poolStatus(sim.state, sim.env))
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
        val sim = Sim()
        sim.now = rome("2026-10-19T22:00")
        sim.open() // from 22:01
        sim.after(4, Input.Foreground("dev.vigil", false)) // 4 minutes used; the 60-second gap starts at 22:05
        sim.afterSeconds(20, Input.GoingToSleep())
        assertNull(sim.state.pool.session)
        val types = sim.types()
        assertTrue(types.lastIndexOf(EntryType.POOL_SESSION) < types.lastIndexOf(EntryType.DAY_END))
        assertEquals(240L, sim.lastOf(EntryType.POOL_SESSION)!!.seconds)
        // The new day starts with its whole budget.
        sim.after(1)
        assertEquals(0L, sim.state.pool.usedSeconds)
    }

    @Test
    fun aSessionInTheAppAtTheDayEndGoesOnIntoTheNewDay() {
        val sim = Sim()
        sim.now = rome("2026-10-20T05:50")
        sim.open() // from 05:51
        sim.at("2026-10-20T06:00")
        assertEquals("2026-10-20", sim.state.day!!.key)
        // Its 9 minutes count for the day that ended; the session itself goes on, counted for the new day.
        val ended = sim.lastOf(EntryType.POOL_SESSION)!!
        assertEquals(540L, ended.seconds)
        assertEquals("the day ended; the session goes on", ended.detail)
        assertTrue(sim.types().lastIndexOf(EntryType.POOL_SESSION) < sim.types().lastIndexOf(EntryType.DAY_END))
        assertTrue(sim.lastEffects<Effect.Kick>().isEmpty())
        assertNotNull(sim.state.pool.session)
        assertEquals(0L, sim.state.pool.usedSeconds)
        sim.after(5)
        assertEquals(300L, sim.state.pool.usedToday(sim.now))
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
        // Scrolling on while suspended counts for nothing ...
        sim.after(1, Input.Foreground(ig, true))
        assertTrue(sim.last!!.effects.isEmpty())
        // ... and is not heavy use when the hour is up: the app in front just gets the next wait.
        sim.at("2026-10-19T09:39")
        assertTrue(sim.of(EntryType.HEAVY_USE).isEmpty())
        assertTrue(sim.lastEffects<Effect.Kick>().isEmpty())
        assertEquals(listOf(Effect.PoolGate(ig, PoolDecision.Wait(1))), sim.lastEffects<Effect.PoolGate>())
        assertEquals(480L, sim.state.pool.usedSeconds)
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
        assertEquals(120L, sim.state.pool.usedSeconds)
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        assertEquals(0, sim.state.pool.opens)
        assertEquals(0L, sim.state.pool.usedSeconds)
    }

    @Test
    fun aPoolStateSavedByV20StillReads() {
        // v2.0 kept free-window minutes, open times, recent use and cooldowns; v2.1 reads what it still needs.
        val old = """
            {"version":1,"pool":{"opens":2,"openTimes":["2026-10-19T06:30:00Z"],"usedSeconds":1200,
            "windowUsed":{"morning":1200},"recentUse":[{"at":"2026-10-19T06:31:00Z","seconds":600}],
            "penaltyMinutesToday":120,"penaltyUntil":"2026-10-19T09:00:00Z","cooldownUntil":"2026-10-19T07:00:00Z",
            "cooldownCause":"session","wait":null,"session":{"startedAt":"2026-10-19T06:40:00Z","windowId":"morning",
            "app":"com.instagram.android","usedSeconds":120,"inSince":null,"leftAt":"2026-10-19T06:42:00Z"}}}
        """.trimIndent()
        val pool = MachineState.decode(old).pool
        assertEquals(
            PoolState(
                opens = 2, usedSeconds = 1200, penaltyMinutesToday = 120, penaltyUntil = Instant.parse("2026-10-19T09:00:00Z"),
                session = PoolSession(
                    Instant.parse("2026-10-19T06:40:00Z"), "com.instagram.android", usedSeconds = 120, inSince = null,
                    leftAt = Instant.parse("2026-10-19T06:42:00Z"),
                ),
            ),
            pool,
        )
        // It keeps working: the old session ends after its gap with its 2 minutes.
        val o = Machine.step(MachineState.decode(old), Input.Tick, MachineEnv(rome("2026-10-19T08:50"), ROME, M5_FILE))
        assertNull(o.state.pool.session)
    }
}
