package dev.vigil.core.pool

import dev.vigil.core.machine.ROME
import dev.vigil.core.machine.rome
import dev.vigil.core.protocol.BailOutConfig
import dev.vigil.core.protocol.PoolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** v2.1: the pool is a daily budget (90 minutes), a flat 1-minute wait, and a break after one long session. */
class PoolRulesTest {
    private val cfg = PoolConfig()
    private val now = rome("2026-10-19T09:00")

    private fun blocked(d: PoolDecision): PoolDecision.Blocked = d as PoolDecision.Blocked

    @Test
    fun theDefaultsAreTheBudgetModel() {
        assertEquals(90, cfg.dailyBudgetMin)
        assertEquals(1, cfg.waitMin)
        assertEquals(30, cfg.heavyUse.continuousMin)
        assertEquals(15, cfg.heavyUse.breakMin)
    }

    @Test
    fun anyTimeOfDayWaitsTheSameMinute() {
        for (t in listOf("2026-10-19T06:30", "2026-10-19T13:00", "2026-10-19T23:30")) {
            assertEquals(PoolDecision.Wait(1), PoolRules.decide(cfg, PoolState(), rome(t), emptyList(), false))
        }
        // The wait never grows with the opens of the day.
        assertEquals(PoolDecision.Wait(1), PoolRules.decide(cfg, PoolState(opens = 12), now, emptyList(), false))
        assertEquals(PoolDecision.Wait(3), PoolRules.decide(cfg.copy(waitMin = 3), PoolState(), now, emptyList(), false))
    }

    @Test
    fun theBudgetUsedUpBlocksUntilTheDayEnds() {
        // 89 minutes used: one minute left, still a wait.
        assertTrue(PoolRules.decide(cfg, PoolState(usedSeconds = 89 * 60L), now, emptyList(), false) is PoolDecision.Wait)
        assertEquals(60L, PoolRules.leftSeconds(cfg, PoolState(usedSeconds = 89 * 60L), now))
        val d = blocked(PoolRules.decide(cfg, PoolState(usedSeconds = 90 * 60L), now, emptyList(), false))
        assertEquals(BlockReason.BUDGET, d.reason)
        assertEquals(null, d.until)
        assertEquals("TODAY'S 90 MINUTES ARE USED", PoolText.block(d.blocks.first(), ROME))
        assertEquals(0L, PoolRules.leftSeconds(cfg, PoolState(usedSeconds = 100 * 60L), now))
    }

    @Test
    fun aLiveSessionCountsAgainstTheBudget() {
        val s = PoolSession(now.minusSeconds(600), "com.instagram.android", usedSeconds = 0, inSince = now.minusSeconds(600))
        val pool = PoolState(usedSeconds = 80 * 60L, session = s)
        assertEquals(90 * 60L, pool.usedToday(now))
        assertEquals(BlockReason.BUDGET, blocked(PoolRules.decide(cfg, pool, now, emptyList(), false)).reason)
        // With minutes left a live session is simply allowed.
        assertEquals(PoolDecision.Allowed, PoolRules.decide(cfg, pool.copy(usedSeconds = 0), now, emptyList(), false))
    }

    @Test
    fun penaltyAndTheBreakAfterHeavyUse() {
        val penalty = PoolState(penaltyUntil = now.plusSeconds(7200))
        assertEquals(BlockReason.PENALTY, blocked(PoolRules.decide(cfg, penalty, now, emptyList(), false)).reason)
        val rest = PoolState(breakUntil = now.plusSeconds(900))
        val h = blocked(PoolRules.decide(cfg, rest, now, emptyList(), false))
        assertEquals(BlockReason.HEAVY_USE, h.reason)
        assertEquals(now.plusSeconds(900), h.until)
        assertEquals("ENOUGH FOR NOW UNTIL 09:15", PoolText.block(h.blocks.first(), ROME))
        // Ended locks no longer block.
        val over = PoolState(breakUntil = now, penaltyUntil = now.minusSeconds(1))
        assertEquals(PoolDecision.Wait(1), PoolRules.decide(cfg, over, now, emptyList(), false))
    }

    @Test
    fun everyReasonInOrder() {
        val pool = PoolState(usedSeconds = 90 * 60L, penaltyUntil = now.plusSeconds(120), breakUntil = now.plusSeconds(60))
        val outer = listOf(
            Block(BlockReason.RUN_LOCK, now.plusSeconds(30), "DHUHR"), Block(BlockReason.FRIENDS, null),
            Block(BlockReason.WALKING, null), Block(BlockReason.NIGHT_LOCK, null),
        )
        val d = blocked(PoolRules.decide(cfg, pool, now, outer, false))
        assertEquals(
            listOf(
                BlockReason.RUN_LOCK, BlockReason.FRIENDS, BlockReason.WALKING, BlockReason.NIGHT_LOCK, BlockReason.BUDGET,
                BlockReason.PENALTY, BlockReason.HEAVY_USE,
            ),
            d.blocks.map { it.reason },
        )
        assertEquals(BlockReason.RUN_LOCK, d.reason)
        assertEquals(now.plusSeconds(30), d.until)
        // Suspended: everything is open.
        assertEquals(PoolDecision.Allowed, PoolRules.decide(cfg, pool, now, outer, true))
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
    fun sessionUse() {
        val s = PoolSession(now.minusSeconds(300), "x.y", usedSeconds = 100, inSince = now.minusSeconds(50))
        assertEquals(150, s.usedAt(now))
        assertEquals(100, s.copy(inSince = null, leftAt = now.minusSeconds(50)).usedAt(now))
    }

    @Test
    fun newDayResetsCountersButKeepsLocks() {
        val pool = PoolState(
            opens = 4, usedSeconds = 900, penaltyMinutesToday = 120, penaltyUntil = now.plusSeconds(60), breakUntil = now.plusSeconds(30),
        )
        val fresh = pool.newDay()
        assertEquals(0, fresh.opens)
        assertEquals(0, fresh.usedSeconds)
        assertEquals(0, fresh.penaltyMinutesToday)
        assertEquals(pool.penaltyUntil, fresh.penaltyUntil)
        assertEquals(pool.breakUntil, fresh.breakUntil)
    }

    @Test
    fun texts() {
        assertEquals("BAIL-OUT PENALTY UNTIL 09:10", PoolText.block(Block(BlockReason.PENALTY, now.plusSeconds(600)), ROME))
        assertEquals("TODAY'S MINUTES ARE USED", PoolText.block(Block(BlockReason.BUDGET, null), ROME))
        assertEquals("DHUHR UNTIL 13:20", PoolText.block(Block(BlockReason.RUN_LOCK, rome("2026-10-19T13:20"), "Dhuhr"), ROME))
        assertEquals("YOU'RE WITH FRIENDS", PoolText.block(Block(BlockReason.FRIENDS, null), ROME))
        BlockReason.entries.forEach { assertEquals(it, BlockReason.fromJson(it.json)) }
        assertTrue(PoolText.reason(Block(BlockReason.HEAVY_USE, Instant.EPOCH)).isNotBlank())
    }
}
