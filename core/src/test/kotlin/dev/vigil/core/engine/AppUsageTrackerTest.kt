package dev.vigil.core.engine

import dev.vigil.core.plan.Trigger
import dev.vigil.core.plan.UsageLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUsageTrackerTest {
    private val tiktok = "com.zhiliaoapp.musically"
    private val insta = "com.instagram.android"
    private val launcher = "com.nothing.launcher"
    private val none = emptyList<String>()

    private fun min(m: Double): Long = (m * 60_000).toLong()
    private fun min(m: Int): Long = m * 60_000L

    private fun opens(n: Int, windowMin: Int, cooldownMin: Int = 0, apps: List<String> = listOf(tiktok)) =
        Trigger.AppUsage(apps, UsageLimit.Opens(n, windowMin), cooldownMin)

    private fun continuous(minutes: Int, cooldownMin: Int = 0, apps: List<String> = listOf(tiktok)) =
        Trigger.AppUsage(apps, UsageLimit.Continuous(minutes), cooldownMin)

    // ------------------------------------------------------------------ opens

    @Test
    fun firesExactlyAtTheNthOpen() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(3, 10))
        assertEquals(none, t.onForeground(tiktok, min(0), triggers))
        assertEquals(1, t.openCount("d"))
        assertEquals(none, t.onForeground(launcher, min(1), triggers))
        assertEquals(none, t.onForeground(tiktok, min(2), triggers))
        assertEquals(none, t.onForeground(launcher, min(3), triggers))
        assertEquals(2, t.openCount("d"))
        assertEquals(listOf("d"), t.onForeground(tiktok, min(4), triggers))
        assertEquals(0, t.openCount("d"))
    }

    @Test
    fun opensOlderThanTheWindowDropOut() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(3, 10))
        fun open(at: Long) = t.onForeground(tiktok, at, triggers).also { t.onForeground(launcher, at + 1, triggers) }
        assertEquals(none, open(min(0)))
        assertEquals(none, open(min(5)))
        // Exactly one window after the first open: the first no longer counts.
        assertEquals(none, open(min(10)))
        assertEquals(2, t.openCount("d"))
        assertEquals(listOf("d"), open(min(12)))
    }

    @Test
    fun opensJustInsideTheWindowCount() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(3, 10))
        t.onForeground(tiktok, min(0), triggers)
        t.onForeground(launcher, min(1), triggers)
        t.onForeground(tiktok, min(5), triggers)
        t.onForeground(launcher, min(6), triggers)
        assertEquals(listOf("d"), t.onForeground(tiktok, min(10) - 1, triggers))
    }

    @Test
    fun switchingBetweenListedAppsIsNotANewOpen() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(2, 60, apps = listOf(tiktok, insta)))
        assertEquals(none, t.onForeground(tiktok, min(0), triggers))
        assertEquals(none, t.onForeground(insta, min(1), triggers))
        assertEquals(none, t.onForeground(tiktok, min(2), triggers))
        assertEquals(1, t.openCount("d"))
        assertEquals(none, t.onForeground(launcher, min(3), triggers))
        assertEquals(listOf("d"), t.onForeground(insta, min(4), triggers))
    }

    @Test
    fun theSameAppReportedAgainIsNotANewOpen() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(2, 60))
        t.onForeground(tiktok, min(0), triggers)
        assertEquals(none, t.onForeground(tiktok, min(1), triggers))
        assertEquals(none, t.onForeground(tiktok, min(2), triggers))
        assertEquals(1, t.openCount("d"))
    }

    @Test
    fun screenOffThenBackIsANewOpen() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(2, 60))
        t.onForeground(tiktok, min(0), triggers)
        assertEquals(none, t.onForeground(null, min(1), triggers))
        assertNull(t.foreground)
        assertEquals(listOf("d"), t.onForeground(tiktok, min(2), triggers))
    }

    @Test
    fun cooldownSuppressesFiring() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(2, 60, cooldownMin = 30))
        fun open(at: Long) = t.onForeground(tiktok, at, triggers).also { t.onForeground(launcher, at + 1, triggers) }
        open(min(0))
        assertEquals(listOf("d"), open(min(1)))
        assertEquals(none, open(min(5)))
        assertEquals(none, open(min(6)))
        assertEquals(none, open(min(30)))
        // Cooldown over (31 minutes after firing): the opens of the last hour still count.
        assertEquals(listOf("d"), open(min(31)))
    }

    @Test
    fun countRestartsAfterFiring() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(2, 60))
        fun open(at: Long) = t.onForeground(tiktok, at, triggers).also { t.onForeground(launcher, at + 1, triggers) }
        assertEquals(none, open(min(0)))
        assertEquals(listOf("d"), open(min(1)))
        assertEquals(none, open(min(2)))
        assertEquals(listOf("d"), open(min(3)))
    }

    @Test
    fun oneOpenLimitFiresOnEveryOpenOutsideTheCooldown() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(1, 10, cooldownMin = 5))
        assertEquals(listOf("d"), t.onForeground(tiktok, min(0), triggers))
        t.onForeground(launcher, min(1), triggers)
        assertEquals(none, t.onForeground(tiktok, min(2), triggers))
        t.onForeground(launcher, min(3), triggers)
        assertEquals(listOf("d"), t.onForeground(tiktok, min(5), triggers))
    }

    @Test
    fun triggersSharingAnAppCountIndependently() {
        val t = AppUsageTracker()
        val triggers = mapOf("tiktok" to opens(2, 60), "both" to opens(2, 60, apps = listOf(insta, tiktok)))
        t.onForeground(tiktok, min(0), triggers)
        t.onForeground(insta, min(1), triggers)
        // tiktok -> insta left "tiktok" but not "both".
        assertEquals(listOf("tiktok"), t.onForeground(tiktok, min(2), triggers))
        assertEquals(1, t.openCount("both"))
        t.onForeground(launcher, min(3), triggers)
        assertEquals(listOf("both"), t.onForeground(tiktok, min(4), triggers))
        assertEquals(1, t.openCount("tiktok"))
    }

    @Test
    fun ticksDoNotCountOpens() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(1, 60, cooldownMin = 0))
        t.onForeground(launcher, min(0), triggers)
        assertEquals(none, t.onTick(min(1), triggers))
        assertEquals(0, t.openCount("d"))
    }

    // ------------------------------------------------------------------ continuous use

    @Test
    fun continuousUseFiresOnTickAfterTheLimit() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to continuous(10))
        assertEquals(none, t.onForeground(tiktok, min(0), triggers))
        assertEquals(none, t.onTick(min(5), triggers))
        assertEquals(none, t.onTick(min(10) - 1, triggers))
        assertEquals(listOf("d"), t.onTick(min(10), triggers))
        // Another full stretch is needed before it fires again.
        assertEquals(none, t.onTick(min(11), triggers))
        assertEquals(none, t.onTick(min(19.9), triggers))
        assertEquals(listOf("d"), t.onTick(min(20), triggers))
    }

    @Test
    fun leavingBreaksContinuousUse() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to continuous(10))
        t.onForeground(tiktok, min(0), triggers)
        assertEquals(none, t.onForeground(launcher, min(8), triggers))
        assertEquals(none, t.onTick(min(9), triggers))
        t.onForeground(tiktok, min(9), triggers)
        assertEquals(none, t.onTick(min(15), triggers))
        assertEquals(listOf("d"), t.onTick(min(19), triggers))
    }

    @Test
    fun screenOffBreaksContinuousUse() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to continuous(10))
        t.onForeground(tiktok, min(0), triggers)
        t.onForeground(null, min(5), triggers)
        assertEquals(none, t.onTick(min(6), triggers))
        t.onForeground(tiktok, min(6), triggers)
        assertEquals(none, t.onTick(min(10), triggers))
        assertEquals(listOf("d"), t.onTick(min(16), triggers))
    }

    @Test
    fun switchingBetweenListedAppsKeepsContinuity() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to continuous(10, apps = listOf(tiktok, insta)))
        t.onForeground(tiktok, min(0), triggers)
        assertEquals(none, t.onForeground(insta, min(5), triggers))
        assertEquals(listOf("d"), t.onTick(min(10), triggers))
    }

    @Test
    fun switchingBetweenListedAppsCanFireWithoutATick() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to continuous(10, apps = listOf(tiktok, insta)))
        t.onForeground(tiktok, min(0), triggers)
        assertEquals(listOf("d"), t.onForeground(insta, min(10), triggers))
    }

    @Test
    fun continuousCooldown() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to continuous(10, cooldownMin = 30))
        t.onForeground(tiktok, min(0), triggers)
        assertEquals(listOf("d"), t.onTick(min(10), triggers))
        assertEquals(none, t.onTick(min(20), triggers))
        assertEquals(none, t.onTick(min(39), triggers))
        assertEquals(listOf("d"), t.onTick(min(40), triggers))
    }

    @Test
    fun ticksOutsideTheListedAppsDoNothing() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to continuous(1))
        assertEquals(none, t.onTick(min(5), triggers))
        t.onForeground(launcher, min(5), triggers)
        assertEquals(none, t.onTick(min(10), triggers))
        t.onForeground(null, min(10), triggers)
        assertEquals(none, t.onTick(min(20), triggers))
    }

    @Test
    fun triggerAddedWhileInTheAppStartsCountingFromTheFirstTick() {
        val t = AppUsageTracker()
        t.onForeground(tiktok, min(0), emptyMap())
        val triggers = mapOf("use" to continuous(10), "open" to opens(1, 10))
        // Already in the app: not an open, and continuous use starts now rather than at minute 0.
        assertEquals(none, t.onForeground(tiktok, min(3), triggers))
        assertEquals(0, t.openCount("open"))
        assertEquals(none, t.onTick(min(5), triggers))
        assertEquals(none, t.onTick(min(14), triggers))
        assertEquals(listOf("use"), t.onTick(min(15), triggers))
    }

    // ------------------------------------------------------------------ plan changes

    @Test
    fun triggersRemovedFromTheMapAreForgotten() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(3, 60), "other" to opens(3, 60))
        t.onForeground(tiktok, min(0), triggers)
        t.onForeground(launcher, min(1), triggers)
        t.onForeground(tiktok, min(2), triggers)
        assertEquals(2, t.openCount("d"))
        t.onForeground(launcher, min(3), mapOf("other" to opens(3, 60)))
        assertEquals(0, t.openCount("d"))
        assertEquals(2, t.openCount("other"))
        // Back in the map: it starts from zero, while "other" reaches its third open.
        assertEquals(listOf("other"), t.onForeground(tiktok, min(4), triggers))
        assertEquals(1, t.openCount("d"))
    }

    @Test
    fun removedContinuousTriggerIsForgottenByTicks() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to continuous(10))
        t.onForeground(tiktok, min(0), triggers)
        assertEquals(none, t.onTick(min(5), emptyMap()))
        // Re-added: continuity restarts from the next tick.
        assertEquals(none, t.onTick(min(10), triggers))
        assertEquals(listOf("d"), t.onTick(min(20), triggers))
    }

    @Test
    fun resetForgetsCountsButNotTheForegroundApp() {
        val t = AppUsageTracker()
        val triggers = mapOf("d" to opens(2, 60))
        t.onForeground(tiktok, min(0), triggers)
        t.reset()
        assertEquals(0, t.openCount("d"))
        assertEquals(tiktok, t.foreground)
        // Still in TikTok, so this is not a new open.
        assertEquals(none, t.onForeground(tiktok, min(1), triggers))
        assertEquals(0, t.openCount("d"))
    }
}
