package dev.vigil.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The CI gate for protocols.json: any validation error fails the build, so a bad edit never ships.
 * Warnings are printed (they are allowed).
 */
class BundledProtocolsTest {
    @Test
    fun bundledFileHasNoErrors() {
        when (val r = ProtocolParser.parse(BundledProtocols.text)) {
            is ProtocolParseResult.Failure -> fail("protocols.json is invalid:\n" + r.errors.joinToString("\n"))
            is ProtocolParseResult.Success -> r.warnings.forEach { println("protocols.json warning: $it") }
        }
    }

    private val file = BundledProtocols.file

    @Test
    fun poolMatchesTheDesign() {
        val pool = file.pool
        assertEquals(
            listOf(
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.facebook.katana", "com.facebook.lite",
                "com.google.android.youtube", "com.instagram.android", "com.pinterest", "com.brave.browser",
            ),
            pool.apps,
        )
        assertTrue(pool.allBrowsers)
        assertEquals(1, pool.waitStepMin)
        assertEquals(10, pool.waitCapMin)
        assertEquals(2.0, pool.cooldownFactor, 0.0)
        assertEquals(60, pool.cooldownCapMin)
        assertEquals(60, pool.sessionGapS)
        assertEquals(
            listOf(
                FreeWindowConfig("morning", "done:morning|wake+60", "next", 30),
                FreeWindowConfig("evening", "done:dinner|meal:dinner+45", "start:sleep|bedtime-60", 30),
            ),
            pool.windows,
        )
        assertEquals(HeavyUseConfig(10, 3, 30), pool.heavyUse)
        assertEquals(BailOutConfig(120, 360), pool.bailOut)
    }

    @Test
    fun globalsMatchTheDesign() {
        assertEquals(2, file.version)
        assertEquals(
            listOf(
                AppsButtonEntry(role = "dialer"), AppsButtonEntry(role = "adhan"), AppsButtonEntry(role = "clock"),
                AppsButtonEntry(packageName = "com.google.android.apps.maps"), AppsButtonEntry(packageName = "com.whatsapp"),
                AppsButtonEntry(labelContains = "PoliTO"),
            ),
            file.appsButton,
        )
        assertEquals(DayConfig(120, "06:00", "05:00"), file.day)
        assertEquals(TakeoverConfig(30, 30), file.takeover)
        assertEquals(StagesConfig(10, 3, 10), file.stages)
    }

    @Test
    fun fivePrayersAsTheDesignSays() {
        assertEquals(Prayer.entries.map { "prayer-${it.json}" }, file.protocols.map { it.id })
        for (prayer in Prayer.entries) {
            val p = file.prayerProtocol(prayer)!!
            assertEquals(ProtocolKind.PRAYER, p.kind)
            assertEquals(prayer.name, p.title)
            assertEquals(Trigger.Adhan(prayer), p.parsedTrigger)
            assertEquals(Intensity.FULL_THEN_SUBTLE, p.intensity)
            assertEquals(Lock(LockType.SCREEN, 20, 8), p.lock)
            assertEquals(listOf(StepType.CHECK, StepType.DONE, StepType.TIMER), p.steps.map { it.type })
            assertEquals(null, p.steps[2].min)
            assertEquals(10, p.intParam("heads_up_min"))
            assertEquals(15, p.intParam("window_warn_min"))
            assertTrue(p.stages)
            assertEquals(null, p.short)
        }
    }
}
