package dev.vigil.core.day

import dev.vigil.core.machine.ROME
import dev.vigil.core.machine.rome
import dev.vigil.core.protocol.DayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DayRulesTest {
    private val cfg = DayConfig()
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun keyIsTheDateSixHoursAfterTheEnd() {
        // GOING TO SLEEP at 23:30 belongs to tomorrow; a 06:00 fallback to today.
        assertEquals(d("2026-10-20"), DayRules.keyAfter(rome("2026-10-19T23:30"), ROME, d("2026-10-19")))
        assertEquals(d("2026-10-20"), DayRules.keyAfter(rome("2026-10-20T06:00"), ROME, d("2026-10-19")))
        assertEquals(d("2026-10-20"), DayRules.keyAfter(rome("2026-10-20T01:30"), ROME, d("2026-10-19")))
        assertEquals(d("2026-10-20"), DayRules.keyAfter(rome("2026-10-19T18:00"), ROME, d("2026-10-19")))
    }

    @Test
    fun keyAlwaysMovesForward() {
        // GOING TO SLEEP in the afternoon would give the same date; the new day still gets the next key.
        assertEquals(d("2026-10-20"), DayRules.keyAfter(rome("2026-10-19T15:00"), ROME, d("2026-10-19")))
        assertEquals(d("2026-10-19"), DayRules.keyAfter(rome("2026-10-19T15:00"), ROME, null))
    }

    @Test
    fun firstKey() {
        assertEquals(d("2026-10-19"), DayRules.firstKey(rome("2026-10-19T06:00"), ROME, cfg))
        assertEquals(d("2026-10-19"), DayRules.firstKey(rome("2026-10-19T23:59"), ROME, cfg))
        assertEquals(d("2026-10-18"), DayRules.firstKey(rome("2026-10-19T05:59"), ROME, cfg))
    }

    @Test
    fun fallbackIsTheMorningAfter() {
        assertEquals(rome("2026-10-20T06:00"), DayRules.fallbackEnd(d("2026-10-19"), cfg, ROME))
        assertEquals(rome("2026-10-20T05:30"), DayRules.fallbackEnd(d("2026-10-19"), DayConfig(fallbackEnd = "05:30"), ROME))
    }

    @Test
    fun idleCountsFromWindDownAndEndsAtScreenOff() {
        val key = d("2026-10-19")
        val windDown = rome("2026-10-19T22:00")
        // Screen off at 22:30: idle detected at 00:30, the day ended at 22:30.
        assertNull(DayRules.dueEnd(key, rome("2026-10-20T00:29"), ROME, cfg, rome("2026-10-19T22:30"), windDown))
        assertEquals(
            DayEnd(DayEndReason.IDLE, rome("2026-10-19T22:30")),
            DayRules.dueEnd(key, rome("2026-10-20T00:30"), ROME, cfg, rome("2026-10-19T22:30"), windDown),
        )
        // Screen off since 20:00 (before wind-down): the count starts at wind-down.
        assertEquals(
            DayEnd(DayEndReason.IDLE, windDown),
            DayRules.dueEnd(key, rome("2026-10-20T00:00"), ROME, cfg, rome("2026-10-19T20:00"), windDown),
        )
        // Without wind-down (or with the screen on) idle never ends the day.
        assertNull(DayRules.dueEnd(key, rome("2026-10-20T03:00"), ROME, cfg, rome("2026-10-19T20:00"), null))
        assertNull(DayRules.dueEnd(key, rome("2026-10-20T03:00"), ROME, cfg, null, windDown))
        assertNull(DayRules.idleStart(null, windDown))
    }

    @Test
    fun fallbackWinsWhenIdleWouldBeLater() {
        val key = d("2026-10-19")
        // Screen off at 04:30 after wind-down: idle would be noticed at 06:30, after the 06:00 fallback.
        assertEquals(
            DayEnd(DayEndReason.FALLBACK, rome("2026-10-20T06:00")),
            DayRules.dueEnd(key, rome("2026-10-20T07:00"), ROME, cfg, rome("2026-10-20T04:30"), rome("2026-10-19T22:00")),
        )
        assertEquals(
            DayEnd(DayEndReason.FALLBACK, rome("2026-10-20T06:00")),
            DayRules.dueEnd(key, rome("2026-10-20T06:00"), ROME, cfg, null, null),
        )
        assertNull(DayRules.dueEnd(key, rome("2026-10-20T05:59"), ROME, cfg, null, null))
    }

    @Test
    fun nextCheck() {
        val key = d("2026-10-19")
        assertEquals(rome("2026-10-20T06:00"), DayRules.nextCheck(key, ROME, cfg, null, rome("2026-10-19T22:00")))
        assertEquals(rome("2026-10-20T00:30"), DayRules.nextCheck(key, ROME, cfg, rome("2026-10-19T22:30"), rome("2026-10-19T22:00")))
    }

    @Test
    fun wakeAlarmEndsOnlyTheDayBefore() {
        assertTrue(DayRules.wakeAlarmEndsDay(d("2026-10-19"), rome("2026-10-20T07:00"), ROME))
        // The day keyed the 20th started at last night's GOING TO SLEEP: this morning's alarm belongs to it.
        assertFalse(DayRules.wakeAlarmEndsDay(d("2026-10-20"), rome("2026-10-20T07:00"), ROME))
    }

    @Test
    fun clocksGoBackOn25October() {
        // Fallback for the 24th: 06:00 CET on the 25th = 05:00 UTC (not 04:00).
        assertEquals(Instant.parse("2026-10-25T05:00:00Z"), DayRules.fallbackEnd(d("2026-10-24"), cfg, ROME))
        // Idle is two real hours across the repeated hour: off at 01:30 CEST (23:30Z), noticed at 02:30 CET (01:30Z).
        val off = Instant.parse("2026-10-24T23:30:00Z")
        assertNull(DayRules.dueEnd(d("2026-10-24"), Instant.parse("2026-10-25T01:29:00Z"), ROME, cfg, off, rome("2026-10-24T22:00")))
        assertEquals(
            DayEnd(DayEndReason.IDLE, off),
            DayRules.dueEnd(d("2026-10-24"), Instant.parse("2026-10-25T01:30:00Z"), ROME, cfg, off, rome("2026-10-24T22:00")),
        )
        assertEquals(d("2026-10-25"), DayRules.keyAfter(off, ROME, d("2026-10-24")))
        assertEquals(d("2026-10-25"), DayRules.keyAfter(Instant.parse("2026-10-25T05:00:00Z"), ROME, d("2026-10-24")))
        // The 25th is 25 hours long: its fallback is 06:00 CET on the 26th.
        assertEquals(Instant.parse("2026-10-26T05:00:00Z"), DayRules.fallbackEnd(d("2026-10-25"), cfg, ROME))
    }

    @Test
    fun reasonsRoundTrip() {
        DayEndReason.entries.forEach { assertEquals(it, DayEndReason.fromJson(it.json)) }
        assertEquals(listOf("sleep_tap", "idle", "wake_alarm", "fallback"), DayEndReason.entries.map { it.json })
    }
}
