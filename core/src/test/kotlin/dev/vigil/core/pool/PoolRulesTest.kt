package dev.vigil.core.pool

import dev.vigil.core.machine.ROME
import dev.vigil.core.machine.rome
import dev.vigil.core.protocol.BailOutConfig
import dev.vigil.core.protocol.PoolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PoolRulesTest {
    private val cfg = PoolConfig()
    private val now = rome("2026-10-19T09:00")
    private val morning = WindowSpan("morning", rome("2026-10-19T08:00"), rome("2026-10-19T12:00"), 30)
    private val evening = WindowSpan("evening", rome("2026-10-19T20:15"), rome("2026-10-19T22:00"), 30)
    private val windows = listOf(morning, evening)

    private fun blocked(d: PoolDecision): PoolDecision.Blocked = d as PoolDecision.Blocked

    @Test
    fun waitGrowsByOneMinutePerOpenUpToTheCap() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10), (0..11).map { PoolRules.waitMinutes(cfg, it) })
        assertEquals(6, PoolRules.waitMinutes(PoolConfig(waitStepMin = 2, waitCapMin = 6), 5))
    }

    @Test
    fun cooldownIsTwiceTheMinutesUsedCappedAtAnHour() {
        assertEquals(0, PoolRules.cooldownSeconds(cfg, 0))
        assertEquals(600, PoolRules.cooldownSeconds(cfg, 300))
        assertEquals(3600, PoolRules.cooldownSeconds(cfg, 1800))
        assertEquals(3600, PoolRules.cooldownSeconds(cfg, 7200))
        assertEquals(450, PoolRules.cooldownSeconds(PoolConfig(cooldownFactor = 1.5), 300))
    }

    @Test
    fun noSessionMeansWait() {
        assertEquals(PoolDecision.Wait(1), PoolRules.decide(cfg, PoolState(), now, windows, emptyList(), false))
        assertEquals(PoolDecision.Wait(4), PoolRules.decide(cfg, PoolState(opens = 3), now, windows, emptyList(), false))
    }

    @Test
    fun sessionMeansAllowed() {
        val pool = PoolState(session = PoolSession(now.minusSeconds(60), "morning", "com.instagram.android"))
        assertEquals(PoolDecision.Allowed, PoolRules.decide(cfg, pool, now, windows, emptyList(), false))
    }

    @Test
    fun suspendedMeansEverythingOpen() {
        val pool = PoolState(cooldownUntil = now.plusSeconds(600))
        assertEquals(PoolDecision.Allowed, PoolRules.decide(cfg, pool, rome("2026-10-19T03:00"), windows, listOf(Block(BlockReason.RUN_LOCK, null)), true))
    }

    @Test
    fun outsideEveryWindowUntilTheNextOne() {
        val d = blocked(PoolRules.decide(cfg, PoolState(), rome("2026-10-19T13:00"), windows, emptyList(), false))
        assertEquals(BlockReason.OUTSIDE_WINDOW, d.reason)
        assertEquals(evening.opensAt, d.until)
        val late = blocked(PoolRules.decide(cfg, PoolState(), rome("2026-10-19T22:00"), windows, emptyList(), false))
        assertNull(late.until)
        // Exactly at the opening it is open; exactly at the closing it is closed.
        assertTrue(PoolRules.decide(cfg, PoolState(), rome("2026-10-19T08:00"), windows, emptyList(), false) is PoolDecision.Wait)
        assertTrue(PoolRules.decide(cfg, PoolState(), rome("2026-10-19T12:00"), windows, emptyList(), false) is PoolDecision.Blocked)
    }

    @Test
    fun windowNotToday() {
        val none = listOf(WindowSpan("evening", null, null, 30))
        assertEquals(BlockReason.OUTSIDE_WINDOW, blocked(PoolRules.decide(cfg, PoolState(), now, none, emptyList(), false)).reason)
        // A window that would close before it opens never opens.
        val inverted = listOf(WindowSpan("x", rome("2026-10-19T10:00"), rome("2026-10-19T09:00"), 30))
        assertEquals(BlockReason.OUTSIDE_WINDOW, blocked(PoolRules.decide(cfg, PoolState(), rome("2026-10-19T09:30"), inverted, emptyList(), false)).reason)
        assertNull(PoolRules.nextOpening(inverted, PoolState(), rome("2026-10-19T08:00")))
    }

    @Test
    fun windowCapUsedUntilTheNextWindow() {
        val pool = PoolState(windowUsed = mapOf("morning" to 1800))
        val d = blocked(PoolRules.decide(cfg, pool, now, windows, emptyList(), false))
        assertEquals(BlockReason.WINDOW_CAP, d.reason)
        assertEquals(evening.opensAt, d.until)
        assertEquals("morning", d.blocks.first().detail)
        // 29 minutes used: still one minute left.
        assertTrue(PoolRules.decide(cfg, PoolState(windowUsed = mapOf("morning" to 1740)), now, windows, emptyList(), false) is PoolDecision.Wait)
    }

    @Test
    fun liveSessionCountsAgainstItsWindow() {
        val s = PoolSession(now.minusSeconds(600), "morning", "com.instagram.android", usedSeconds = 0, inSince = now.minusSeconds(600))
        val pool = PoolState(windowUsed = mapOf("morning" to 1200), session = s)
        assertEquals(1800, PoolRules.windowUsedSeconds("morning", pool, now))
        assertEquals(BlockReason.WINDOW_CAP, blocked(PoolRules.decide(cfg, pool, now, windows, emptyList(), false)).reason)
        // Its window closing blocks it too, even if another window were open.
        val open = PoolState(session = s.copy(windowId = "morning"))
        assertEquals(BlockReason.OUTSIDE_WINDOW, blocked(PoolRules.decide(cfg, open, rome("2026-10-19T12:00"), windows, emptyList(), false)).reason)
    }

    @Test
    fun firstOpenWindowWithMinutesLeft() {
        val overlapping = listOf(morning, WindowSpan("extra", rome("2026-10-19T08:30"), rome("2026-10-19T10:00"), 10))
        assertEquals("morning", PoolRules.openWindow(overlapping, PoolState(), now)!!.id)
        assertEquals("extra", PoolRules.openWindow(overlapping, PoolState(windowUsed = mapOf("morning" to 1800)), now)!!.id)
        assertNull(PoolRules.openWindow(overlapping, PoolState(windowUsed = mapOf("morning" to 1800, "extra" to 600)), now))
    }

    @Test
    fun cooldownPenaltyAndHeavyUse() {
        val cooling = PoolState(cooldownUntil = now.plusSeconds(600), cooldownCause = PoolState.CAUSE_SESSION)
        val c = blocked(PoolRules.decide(cfg, cooling, now, windows, emptyList(), false))
        assertEquals(BlockReason.COOLDOWN, c.reason)
        assertEquals(now.plusSeconds(600), c.until)
        val penalty = PoolState(penaltyUntil = now.plusSeconds(7200))
        assertEquals(BlockReason.PENALTY, blocked(PoolRules.decide(cfg, penalty, now, windows, emptyList(), false)).reason)
        val heavy = PoolState(cooldownUntil = now.plusSeconds(1200), cooldownCause = PoolState.CAUSE_HEAVY_USE)
        val h = blocked(PoolRules.decide(cfg, heavy, now, windows, emptyList(), false))
        assertEquals(BlockReason.HEAVY_USE, h.reason)
        assertEquals(now.plusSeconds(1200), h.until)
        // Ended locks no longer block.
        val over = PoolState(cooldownUntil = now, penaltyUntil = now.minusSeconds(1))
        assertTrue(PoolRules.decide(cfg, over, now, windows, emptyList(), false) is PoolDecision.Wait)
    }

    @Test
    fun everyReasonInDesignOrder() {
        val pool = PoolState(
            windowUsed = mapOf("morning" to 1800),
            cooldownUntil = now.plusSeconds(60), cooldownCause = PoolState.CAUSE_SESSION,
            penaltyUntil = now.plusSeconds(120),
        )
        val outer = listOf(Block(BlockReason.RUN_LOCK, now.plusSeconds(30), "DHUHR"), Block(BlockReason.FRIENDS, null), Block(BlockReason.WALKING, null))
        val d = blocked(PoolRules.decide(cfg, pool, now, windows, outer, false))
        assertEquals(
            listOf(BlockReason.RUN_LOCK, BlockReason.FRIENDS, BlockReason.WALKING, BlockReason.WINDOW_CAP, BlockReason.COOLDOWN, BlockReason.PENALTY),
            d.blocks.map { it.reason },
        )
        assertEquals(BlockReason.RUN_LOCK, d.reason)
        assertEquals(now.plusSeconds(30), d.until)
        val heavy = pool.copy(cooldownCause = PoolState.CAUSE_HEAVY_USE)
        assertEquals(BlockReason.HEAVY_USE, blocked(PoolRules.decide(cfg, heavy, now, windows, emptyList(), false)).blocks.last().reason)
    }

    @Test
    fun penaltyStacksUpToTheDailyCap() {
        var pool = PoolState()
        val (p1, a1) = PoolRules.penalty(cfg, pool, now)
        assertEquals(120, a1)
        assertEquals(now.plusSeconds(7200), p1.penaltyUntil)
        val (p2, a2) = PoolRules.penalty(cfg, p1, now.plusSeconds(600))
        assertEquals(120, a2)
        assertEquals(now.plusSeconds(14400), p2.penaltyUntil)
        val (p3, a3) = PoolRules.penalty(cfg, p2, now.plusSeconds(1200))
        assertEquals(120, a3)
        assertEquals(360, p3.penaltyMinutesToday)
        val (p4, a4) = PoolRules.penalty(cfg, p3, now.plusSeconds(1800))
        assertEquals(0, a4)
        assertEquals(p3, p4)
        // A penalty that already ran out starts from now.
        pool = PoolState(penaltyUntil = now.minusSeconds(60), penaltyMinutesToday = 120)
        assertEquals(now.plusSeconds(7200), PoolRules.penalty(cfg, pool, now).first.penaltyUntil)
        // A partial top-up near the cap.
        val nearCap = PoolState(penaltyMinutesToday = 300)
        assertEquals(60, PoolRules.penalty(cfg, nearCap, now).second)
        assertEquals(0, PoolRules.penalty(PoolConfig(bailOut = BailOutConfig(0, 360)), PoolState(), now).second)
    }

    @Test
    fun heavyUseCounters() {
        val pool = PoolState(
            openTimes = listOf(now.minusSeconds(31 * 60), now.minusSeconds(20 * 60), now),
            recentUse = listOf(UseMark(now.minusSeconds(40 * 60), 600), UseMark(now.minusSeconds(10 * 60), 240)),
            session = PoolSession(now.minusSeconds(60), "morning", "x.y", inSince = now.minusSeconds(60)),
        )
        assertEquals(2, PoolRules.opensWithin(cfg, pool, now))
        assertEquals(300, PoolRules.recentUsedSeconds(cfg, pool, now))
    }

    @Test
    fun sessionUse() {
        val s = PoolSession(now.minusSeconds(300), "morning", "x.y", usedSeconds = 100, inSince = now.minusSeconds(50))
        assertEquals(150, s.usedAt(now))
        assertEquals(100, s.copy(inSince = null, leftAt = now.minusSeconds(50)).usedAt(now))
    }

    @Test
    fun newDayResetsCountersButKeepsLocks() {
        val pool = PoolState(
            opens = 4, openTimes = listOf(now), usedSeconds = 900, windowUsed = mapOf("morning" to 900),
            recentUse = listOf(UseMark(now, 900)), penaltyMinutesToday = 120, penaltyUntil = now.plusSeconds(60),
            cooldownUntil = now.plusSeconds(30), cooldownCause = PoolState.CAUSE_SESSION,
        )
        val fresh = pool.newDay()
        assertEquals(0, fresh.opens)
        assertEquals(0, fresh.usedSeconds)
        assertTrue(fresh.windowUsed.isEmpty())
        assertEquals(0, fresh.penaltyMinutesToday)
        assertEquals(pool.penaltyUntil, fresh.penaltyUntil)
        assertEquals(pool.cooldownUntil, fresh.cooldownUntil)
    }

    @Test
    fun texts() {
        assertEquals("COOLDOWN UNTIL 09:10", PoolText.block(Block(BlockReason.COOLDOWN, now.plusSeconds(600)), ROME))
        assertEquals("NO FREE WINDOW · NEXT WINDOW 20:15", PoolText.block(Block(BlockReason.OUTSIDE_WINDOW, evening.opensAt), ROME))
        assertEquals("WINDOW USED UP · NO MORE WINDOWS TODAY", PoolText.block(Block(BlockReason.WINDOW_CAP, null), ROME))
        assertEquals("DHUHR UNTIL 13:20", PoolText.block(Block(BlockReason.RUN_LOCK, rome("2026-10-19T13:20"), "Dhuhr"), ROME))
        assertEquals("YOU'RE WITH FRIENDS", PoolText.block(Block(BlockReason.FRIENDS, null), ROME))
        BlockReason.entries.forEach { assertEquals(it, BlockReason.fromJson(it.json)) }
        assertTrue(PoolText.reason(Block(BlockReason.HEAVY_USE, Instant.EPOCH)).isNotBlank())
    }
}
