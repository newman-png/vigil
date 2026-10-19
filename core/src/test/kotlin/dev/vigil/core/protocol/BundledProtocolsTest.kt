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
                FreeWindowConfig("morning", "max(done:morning|woke+60,woke+60)", "next", 30),
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
        assertEquals(
            listOf(
                "prayer-fajr", "morning", "prayer-dhuhr", "attention", "lunch", "study", "prayer-asr", "gym", "prayer-maghrib",
                "dinner", "prayer-isha", "sleep", "jummah", "reset", "promise",
            ),
            file.protocols.map { it.id },
        )
        assertEquals(Prayer.entries.map { "prayer-${it.json}" }, file.protocols.filter { it.kind == ProtocolKind.PRAYER }.map { it.id })
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

    @Test
    fun sleepWindDownAsTheSpecSays() {
        val p = file.find("sleep")!!
        assertEquals(ProtocolKind.SLEEP, p.kind)
        assertEquals(Trigger.Time(TimeExpr(TimeBase.Bedtime, -60)), p.parsedTrigger)
        assertEquals(Intensity.FULL_THEN_SUBTLE, p.intensity)
        assertEquals(Lock(LockType.SCREEN), p.lock)
        // Close the day (3 lines), tomorrow (up to 4 to-dos), prep 10, hygiene 10, phone away, lights low, GOING TO SLEEP.
        assertEquals(
            listOf(StepType.TEXT, StepType.TEXT, StepType.CHECK, StepType.CHECK, StepType.CHECK, StepType.CHECK, StepType.DONE),
            p.steps.map { it.type },
        )
        assertEquals(listOf(3, 4), p.steps.take(2).map { it.lines })
        assertEquals(listOf(false, true), p.steps.take(2).map { it.todos })
        assertEquals(listOf(10, 10), p.steps.subList(2, 4).map { it.min })
        assertTrue(p.steps[5].noiseToggle)
        // Short skips prep and hygiene; minimal = a line, tomorrow's first task, phone away, sleep; single = sleep.
        assertEquals(p.steps.filterIndexed { i, _ -> i != 2 && i != 3 }, p.short)
        assertEquals(listOf(StepType.TEXT, StepType.TEXT, StepType.CHECK, StepType.DONE), p.minimal!!.map { it.type })
        assertEquals(listOf(1, 1), p.minimal!!.take(2).map { it.lines })
        assertTrue(p.minimal!![1].todos)
        assertEquals(listOf(StepType.DONE), p.single!!.map { it.type })
        assertEquals(20, p.intParam("shift_min"))
        assertEquals(2, p.intParam("hold_after_missed"))
        assertEquals(30, p.intParam("late_min"))
        assertEquals(120, p.intParam("night_lock_grace_min"))
        assertTrue(!p.boolParam("white_noise"))
    }

    @Test
    fun morningRoutineAsTheSpecSays() {
        val p = file.find("morning")!!
        assertEquals(ProtocolKind.MORNING, p.kind)
        assertEquals(Trigger.WakeAlarm, p.parsedTrigger)
        assertEquals(Lock(LockType.POOL), p.lock)
        fun shape(steps: List<Step>?) = steps!!.map { it.type to it.min }
        // Get up, sun 5 (timer), bathroom 5, skincare or shower 15, breakfast, chores 20 (timer), walk 15 (timer).
        assertEquals(
            listOf(StepType.CHECK to null, StepType.TIMER to 5, StepType.CHECK to 5, StepType.CHECK to 15, StepType.CHECK to null, StepType.TIMER to 20, StepType.TIMER to 15),
            shape(p.steps),
        )
        assertEquals(listOf(6), p.steps.indices.filter { p.steps[it].skipOnClassDays })
        // Short: no skincare, chores 10, walk 10. Minimal: get up, bathroom, breakfast, sun 5. Single: sun 5, marked minimum.
        assertEquals(
            listOf(StepType.CHECK to null, StepType.TIMER to 5, StepType.CHECK to 5, StepType.CHECK to null, StepType.TIMER to 10, StepType.TIMER to 10),
            shape(p.short),
        )
        assertEquals(listOf(StepType.CHECK to null, StepType.CHECK to null, StepType.CHECK to null, StepType.TIMER to 5), shape(p.minimal))
        assertEquals(listOf(StepType.TIMER to 5), shape(p.single))
        // Thursday (08:30 class): get up, sun 5, bathroom 5, shower 10, breakfast. Friday: a shower for Jummah.
        assertEquals(
            listOf(StepType.CHECK to null, StepType.TIMER to 5, StepType.CHECK to 5, StepType.CHECK to 10, StepType.CHECK to null),
            shape(p.stepsFor(Version.FULL, "thu")),
        )
        assertTrue(p.stepsFor(Version.FULL, "fri")!!.any { it.text.contains("Jummah") })
        assertTrue(p.stepsFor(Version.SHORT, "fri")!!.any { it.text.contains("Jummah") })
        assertEquals(10, p.intParam("ring_max_min"))
        assertEquals(5, p.intParam("snooze_min"))
        assertEquals(3, p.intParam("max_rings"))
    }

    @Test
    fun jummahAsTheSpecSays() {
        val p = file.find("jummah")!!
        assertEquals(ProtocolKind.JUMMAH, p.kind)
        assertEquals(Trigger.Slot(SlotType.JUMMAH), p.parsedTrigger)
        assertEquals(Intensity.FULL, p.intensity)
        // The lock: 45 minutes from jummah_time - 5, DONE 15 minutes in (jummah_time + 10).
        assertEquals(Lock(LockType.SCREEN, 45, 15), p.lock)
        // Shoes on, bag, leave, walking on; I'M AT UNI; the lock's DONE.
        assertEquals(listOf(StepType.CHECK, StepType.CHECK, StepType.CHECK, StepType.CHECK, StepType.CHECK, StepType.DONE), p.steps.map { it.type })
        assertEquals(listOf(3), p.steps.indices.filter { p.steps[it].walkingOn })
        assertEquals(listOf(4), p.steps.indices.filter { p.steps[it].arrival })
        assertEquals(null, p.short)
        assertTrue(!p.stages)
        assertEquals(15, p.intParam("leave_buffer_min"))
        assertEquals(15, p.intParam("arrive_before_min"))
        assertEquals(10, p.intParam("arrive_repeat_min"))
        assertEquals(5, p.intParam("card_before_end_min"))
        assertEquals(5, p.intParam("lock_before_min"))
        assertEquals(50, p.intParam("missed_after_min"))
        assertEquals(listOf("Jummah prep: clean clothes, bag packed."), p.textListParam("prep_todos"))
    }

    @Test
    fun resetAsTheSpecSays() {
        val p = file.find("reset")!!
        assertEquals(ProtocolKind.RESET, p.kind)
        assertEquals(Trigger.Misses(2), p.parsedTrigger)
        assertEquals(Intensity.FULL, p.intensity)
        assertEquals(Lock(LockType.SCREEN), p.lock)
        assertTrue(!p.stages)
        fun shape(steps: List<Step>?) = steps!!.map { it.type to it.min }
        // Stop 1, name it (optional), body 3, space 5, move 5, re-enter.
        assertEquals(
            listOf(StepType.TIMER to 1, StepType.TEXT to null, StepType.CHECK to 3, StepType.CHECK to 5, StepType.CHECK to 5, StepType.DONE to null),
            shape(p.steps),
        )
        assertTrue(p.steps[1].optional)
        assertEquals(1, p.steps[1].lines)
        // Short = steps 1, 3 and re-enter; minimal = water, wash your face, re-enter; single = re-enter.
        assertEquals(listOf(p.steps[0], p.steps[2], p.steps[5]), p.short)
        assertEquals(listOf(StepType.CHECK, StepType.CHECK, StepType.DONE), p.minimal!!.map { it.type })
        for (v in listOf(p.steps, p.short!!, p.minimal!!, p.single!!)) assertTrue(v.last().reenter && v.dropLast(1).none { it.reenter })
        assertEquals(60, p.intParam("next_within_min"))
    }

    @Test
    fun studyAsTheDecisionsSay() {
        val p = file.find("study")!!
        assertEquals(ProtocolKind.STUDY, p.kind)
        assertEquals(Trigger.Slot(SlotType.STUDY), p.parsedTrigger)
        assertEquals(Intensity.SUBTLE, p.intensity)
        assertEquals(Lock(LockType.SCREEN), p.lock)
        // Spot, notebook, book or laptop, the material, the rounds (50/10, chime), the closing line.
        assertEquals(
            listOf(StepType.CHECK, StepType.CHECK, StepType.CHECK, StepType.CHECK, StepType.ROUNDS, StepType.TEXT),
            p.steps.map { it.type },
        )
        val rounds = p.steps[4]
        assertEquals(listOf(50, 10), listOf(rounds.min, rounds.breakMin))
        assertTrue(rounds.chime && rounds.breakText!!.contains("water"))
        assertEquals(1, p.steps[5].lines)
        // Short: 25/5 rounds and the line; minimal: one 25-minute round; no single.
        assertEquals(listOf(StepType.ROUNDS, StepType.TEXT), p.short!!.map { it.type })
        assertEquals(listOf(25, 5), listOf(p.short!![0].min, p.short!![0].breakMin))
        assertEquals(listOf(StepType.TIMER to 25), p.minimal!!.map { it.type to it.min })
        assertEquals(null, p.single)
        assertEquals(100, p.intParam("default_min"))
    }

    @Test
    fun attentionResetAsTheDecisionsSay() {
        val p = file.find("attention")!!
        assertEquals(ProtocolKind.ATTENTION, p.kind)
        assertEquals(Trigger.After("prayer-dhuhr"), p.parsedTrigger)
        assertEquals(Intensity.FULL, p.intensity)
        assertEquals(Lock(LockType.SCREEN), p.lock)
        // Face a wall, phone face-down, look at one spot (the ramp's minutes, a chime at the end).
        assertEquals(listOf(StepType.CHECK, StepType.CHECK, StepType.TIMER), p.steps.map { it.type })
        assertEquals(null, p.steps[2].min)
        assertTrue(p.steps[2].chime)
        assertEquals(listOf(StepType.TIMER to 3), p.short!!.map { it.type to it.min })
        assertEquals(null, p.minimal)
        assertEquals(listOf(0, 3, 10, 2), listOf("offset_min", "start_min", "max_min", "ramp_every_days").map { p.intParam(it) })
    }

    @Test
    fun mealsAsTheDecisionsSay() {
        for ((id, label) in listOf("lunch" to "lunch", "dinner" to "dinner")) {
            val p = file.find(id)!!
            assertEquals(ProtocolKind.MEAL, p.kind)
            assertEquals(Trigger.Slot(SlotType.MEAL, label), p.parsedTrigger)
            assertEquals(Intensity.SUBTLE, p.intensity)
            assertEquals(Lock(LockType.POOL), p.lock)
            // Kitchen, ingredients, cook, eat, clean up (the pool opens), walk 10.
            assertEquals(listOf(null, null, null, null, null, 10), p.steps.map { it.min })
            assertEquals(listOf(4), p.steps.indices.filter { p.steps[it].unlock })
            assertEquals(StepType.TIMER, p.steps[5].type)
            // Short: a simple meal, no walk; minimal: eat something simple and clean up. Both end at the cleanup.
            for (v in listOf(p.short!!, p.minimal!!)) assertTrue(v.last().unlock && v.none { it.type == StepType.TIMER })
            assertEquals(emptyList<String>(), p.textListParam("fallback"))
        }
        // No breakfast protocol: the morning routine has its breakfast step.
        assertTrue(file.protocols.none { (it.parsedTrigger as? Trigger.Slot)?.label == "breakfast" })
        // The evening window opens when dinner is done.
        assertTrue(file.pool.windows.single { it.id == "evening" }.opens.startsWith("done:dinner|"))
    }

    @Test
    fun gymAsTheDecisionsSay() {
        val p = file.find("gym")!!
        assertEquals(ProtocolKind.GYM, p.kind)
        assertEquals(Trigger.Slot(SlotType.GYM), p.parsedTrigger)
        assertEquals(Intensity.SUBTLE, p.intensity)
        assertEquals(Lock(LockType.POOL), p.lock)
        assertEquals(listOf(StepType.CHECK, StepType.CHECK, StepType.CHECK, StepType.WORKOUT, StepType.CHECK), p.steps.map { it.type })
        assertEquals(null, p.steps[3].min)
        assertEquals(20, p.short!!.single { it.type == StepType.WORKOUT }.min)
        assertEquals(listOf(StepType.TIMER to 10), p.minimal!!.map { it.type to it.min })
        assertEquals(90, p.intParam("rest_s"))
        assertTrue(p.textListParam("exercises").containsAll(listOf("Squat", "Bench press")))
    }

    @Test
    fun promiseAsTheSpecSays() {
        val p = file.find("promise")!!
        assertEquals(ProtocolKind.PROMISE, p.kind)
        assertEquals(Trigger.Manual, p.parsedTrigger)
        assertEquals(Intensity.FULL, p.intensity)
        assertEquals(Lock(LockType.POOL), p.lock)
        assertEquals(listOf(StepType.DONE), p.steps.map { it.type })
        assertEquals(kotlinx.serialization.json.JsonArray(listOf(5, 10, 15, 30).map { kotlinx.serialization.json.JsonPrimitive(it) }), p.param("delays"))
        assertEquals(2, p.intParam("snooze_min_min"))
        assertTrue(!p.stages)
    }
}
