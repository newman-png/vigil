package dev.vigil.core.engine

import dev.vigil.core.log.EventType
import dev.vigil.core.log.LogEvent
import dev.vigil.core.plan.BadDay
import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.FallbackPlan
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.Prayer
import dev.vigil.core.plan.QuietPolicy
import dev.vigil.core.plan.Step
import dev.vigil.core.plan.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class EngineTest {
    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val boundary: LocalTime = LocalTime.of(4, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Instant =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant()

    /** Wednesday 14 October 2026, 10:00 BST. */
    private val t0 = at(2026, 10, 14, 10, 0)

    private fun directive(
        id: String,
        quiet: QuietPolicy = QuietPolicy.QUEUE,
        tier: Int = 2,
        steps: List<Step> = listOf(Step("First", 5), Step("Second", 10)),
        trigger: Trigger = Trigger.Adhan(Prayer.ISHA),
    ) = Directive(id, tier, id.uppercase(), trigger, steps, "Because.", quiet)

    private val a = directive("a")
    private val b = directive("b")
    private val c = directive("c")
    private val kept = directive("kept")
    private val silentOne = directive("silent-one", QuietPolicy.SILENT)
    private val ignored = directive("ignored", QuietPolicy.IGNORE)
    private val loud = directive("loud", QuietPolicy.OVERRIDE, tier = 3)
    private val geofence = directive("geo", trigger = Trigger.Unsupported("geofence"))

    private val plainSet = ActiveSet(
        PlanSource.PLAN,
        listOf(a, b, c, kept, silentOne, ignored, loud, geofence),
        setOf("kept"),
        boundary,
        plan = null,
    )

    /** A mutable test harness: feeds each engine call the current clock and active set, and collects the log. */
    private inner class World(var now: Instant = t0, var active: ActiveSet = plainSet, var allowOverride: Boolean = false) {
        private var ids = 0
        var state = RuntimeState()
        val log = mutableListOf<LogEvent>()

        fun env() = Env(now, zone, active, allowOverride) { "t${++ids}" }

        fun run(step: (RuntimeState, Env) -> Outcome): Outcome {
            val o = step(state, env())
            state = o.state
            log += o.events
            return o
        }

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }

        fun trigger(vararg ids: String, trigger: String = "adhan") = run { s, e -> Engine.onTrigger(s, e, ids.toList(), trigger) }
        fun quiet(on: Boolean) = run { s, e -> Engine.setQuiet(s, e, on) }
        fun badDay(on: Boolean) = run { s, e -> Engine.setBadDay(s, e, on) }
        fun refresh() = run { s, e -> Engine.refresh(s, e) }
        fun shown() = run { s, e -> Engine.stepShown(s, e, state.active!!.takeoverId) }
        fun done(index: Int, id: String = state.active!!.takeoverId) = run { s, e -> Engine.stepDone(s, e, id, index) }
        fun dismiss(id: String = state.active!!.takeoverId) = run { s, e -> Engine.dismiss(s, e, id) }

        /** Shows and ticks every remaining step of the active takeover, waiting out each lock. */
        fun completeActive(): Outcome {
            val d = Engine.directiveFor(env(), state.active!!)!!
            var last: Outcome? = null
            for (i in state.active!!.stepsDone until d.steps.size) {
                shown()
                advance(d.steps[i].minSeconds.toLong())
                last = done(i)
            }
            return last!!
        }
    }

    private fun Outcome.types() = events.map { it.type }

    // ------------------------------------------------------------------ decide

    @Test
    fun decideMatrix() {
        // (quiet, policy, allowOverride) -> expected, without bad-day mode.
        val table = mutableMapOf<Triple<Boolean, QuietPolicy, Boolean>, Decision>()
        for (policy in QuietPolicy.entries) {
            for (allow in listOf(false, true)) table[Triple(false, policy, allow)] = Decision.Fire(silent = false)
        }
        for (allow in listOf(false, true)) {
            table[Triple(true, QuietPolicy.QUEUE, allow)] = Decision.Queue
            table[Triple(true, QuietPolicy.SILENT, allow)] = Decision.Fire(silent = true)
            table[Triple(true, QuietPolicy.IGNORE, allow)] = Decision.Skip(EventType.SKIPPED_QUIET)
        }
        table[Triple(true, QuietPolicy.OVERRIDE, false)] = Decision.Fire(silent = true)
        table[Triple(true, QuietPolicy.OVERRIDE, true)] = Decision.Fire(silent = false)
        assertEquals(16, table.size)

        for ((key, expected) in table) {
            val (quiet, policy, allow) = key
            val d = directive("x", policy, tier = 3)
            val label = "quiet=$quiet policy=$policy allow=$allow"
            assertEquals(label, expected, Engine.decide(d, quiet, badDay = false, keep = emptySet(), allowOverride = allow))
            // Bad day, directive kept: exactly as if there were no bad day.
            assertEquals("$label kept", expected, Engine.decide(d, quiet, badDay = true, keep = setOf("x"), allowOverride = allow))
            // Bad day, not kept: always skipped as bad day, whatever quiet mode says.
            assertEquals(
                "$label not kept",
                Decision.Skip(EventType.SKIPPED_BAD_DAY),
                Engine.decide(d, quiet, badDay = true, keep = setOf("other"), allowOverride = allow),
            )
            // keep only matters on a bad day.
            assertEquals("$label keep ignored", expected, Engine.decide(d, quiet, badDay = false, keep = setOf("other"), allowOverride = allow))
        }
    }

    @Test
    fun overrideOnlyRingsForTierThree() {
        // The parser rejects this, but the engine must not let a hand-built tier 2 override ring in quiet mode.
        val d = directive("x", QuietPolicy.OVERRIDE, tier = 2)
        assertEquals(Decision.Fire(silent = true), Engine.decide(d, quiet = true, badDay = false, keep = emptySet(), allowOverride = true))
    }

    // ------------------------------------------------------------------ onTrigger

    @Test
    fun triggerFiresAndLogs() {
        val w = World()
        val o = w.run { s, e -> Engine.onTrigger(s, e, listOf("a"), "time", scheduledFor = "2026-10-14T09:58:00+01:00") }
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), o.types())
        val ev = o.events.single()
        assertEquals("2026-10-14T10:00:00+01:00", ev.ts)
        assertEquals("a", ev.directiveId)
        assertEquals(2, ev.tier)
        assertEquals("time", ev.trigger)
        assertEquals("t1", ev.takeoverId)
        assertEquals("2026-10-14T09:58:00+01:00", ev.scheduledFor)
        assertEquals(false, ev.silent)
        assertEquals(2, ev.stepsTotal)
        assertTrue(o.showTakeover)
        val t = w.state.active!!
        assertEquals("t1", t.takeoverId)
        assertEquals("a", t.directiveId)
        assertEquals("time", t.trigger)
        assertEquals(t0.toEpochMilli(), t.firedAtMs)
        assertEquals("2026-10-14T09:58:00+01:00", t.scheduledFor)
        assertEquals(0, t.stepsDone)
        assertNull(t.stepShownAtMs)
        assertEquals("plan", w.state.lastSource)
    }

    @Test
    fun quietQueueHoldsDirectiveOnce() {
        val w = World()
        assertEquals(listOf(EventType.QUIET_ON), w.quiet(true).types())
        val o = w.trigger("a")
        assertEquals(listOf(EventType.QUEUED_QUIET), o.types())
        assertEquals("a", o.events.single().directiveId)
        assertEquals("adhan", o.events.single().trigger)
        assertFalse(o.showTakeover)
        assertEquals(listOf(Queued("a", "adhan", t0.toEpochMilli())), w.state.quietQueue)
        w.advance(60)
        w.trigger("a")
        assertEquals(1, w.state.quietQueue.size)
        assertEquals(t0.toEpochMilli(), w.state.quietQueue.single().queuedAtMs)
    }

    @Test
    fun quietIgnoreSkips() {
        val w = World()
        w.quiet(true)
        val o = w.trigger("ignored")
        assertEquals(listOf(EventType.SKIPPED_QUIET), o.types())
        assertEquals("ignored", o.events.single().directiveId)
        assertNull(w.state.active)
        assertTrue(w.state.quietQueue.isEmpty())
    }

    @Test
    fun quietSilentAndOverride() {
        val w = World()
        w.quiet(true)
        val silent = w.trigger("silent-one")
        assertEquals(true, silent.events.single().silent)
        assertTrue(w.state.active!!.silent)
        w.dismiss()

        assertEquals(true, w.trigger("loud").events.single().silent)
        w.dismiss()

        w.allowOverride = true
        assertEquals(false, w.trigger("loud").events.single().silent)
        assertFalse(w.state.active!!.silent)
    }

    @Test
    fun badDaySkipsUnkeptAndFiresKept() {
        val w = World()
        w.badDay(true)
        val skipped = w.trigger("a")
        assertEquals(listOf(EventType.SKIPPED_BAD_DAY), skipped.types())
        assertEquals("a", skipped.events.single().directiveId)
        assertNull(w.state.active)
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("kept").types())
        assertEquals("kept", w.state.active!!.directiveId)
    }

    @Test
    fun unknownAndUnsupportedAreIgnored() {
        val w = World()
        w.refresh()
        val before = w.state
        assertTrue(w.trigger("nope").events.isEmpty())
        assertTrue(w.trigger("geo").events.isEmpty())
        assertEquals(before, w.state)
    }

    @Test
    fun sameDirectiveIsNotStacked() {
        val w = World()
        w.trigger("a")
        val again = w.trigger("a")
        assertTrue(again.events.isEmpty())
        assertTrue(w.state.pending.isEmpty())

        w.trigger("b")
        assertEquals(listOf("b"), w.state.pending.map { it.directiveId })
        assertTrue(w.trigger("b").events.isEmpty())
        assertTrue(w.trigger("a", "b").events.isEmpty())
        assertEquals(1, w.state.pending.size)
    }

    @Test
    fun severalIdsInOneTrigger() {
        val w = World()
        val o = w.trigger("a", "b", "a", "c")
        assertEquals(listOf("a", "b", "c"), o.events.map { it.directiveId })
        assertEquals("a", w.state.active!!.directiveId)
        assertEquals(listOf("b", "c"), w.state.pending.map { it.directiveId })
        assertEquals(listOf("t1", "t2", "t3"), o.events.map { it.takeoverId })
    }

    @Test
    fun pendingIsPromotedWhenActiveCompletes() {
        val w = World()
        w.trigger("a")
        w.advance(30)
        w.trigger("b")
        assertEquals("a", w.state.active!!.directiveId)
        val o = w.completeActive()
        assertTrue(EventType.DIRECTIVE_COMPLETED in o.types())
        assertEquals("b", w.state.active!!.directiveId)
        assertTrue(w.state.pending.isEmpty())
        assertTrue(o.showTakeover)
        assertNull(w.state.active!!.stepShownAtMs)
    }

    @Test
    fun pendingIsPromotedWhenActiveIsDismissed() {
        val w = World()
        w.trigger("a", "b", "c")
        assertEquals(listOf(EventType.DIRECTIVE_DISMISSED), w.dismiss().types())
        assertEquals("b", w.state.active!!.directiveId)
        assertEquals(listOf("c"), w.state.pending.map { it.directiveId })
        w.dismiss()
        assertEquals("c", w.state.active!!.directiveId)
        w.dismiss()
        assertNull(w.state.active)
        assertFalse(w.refresh().showTakeover)
    }

    // ------------------------------------------------------------------ steps

    @Test
    fun stepLockAndCompletion() {
        val w = World()
        w.trigger("a")
        val id = w.state.active!!.takeoverId

        // Not shown yet: ignored however long it has been.
        w.advance(60)
        assertTrue(w.done(0).events.isEmpty())
        assertEquals(0, w.state.active!!.stepsDone)

        w.shown()
        assertEquals(w.now.toEpochMilli(), w.state.active!!.stepShownAtMs)
        val d = Engine.directiveFor(w.env(), w.state.active!!)!!
        assertEquals(5, Engine.lockRemainingSeconds(w.state.active!!, d, w.now.toEpochMilli()))
        w.advance(4)
        assertEquals(1, Engine.lockRemainingSeconds(w.state.active!!, d, w.now.toEpochMilli()))
        assertTrue(w.done(0).events.isEmpty())
        assertEquals(0, w.state.active!!.stepsDone)

        w.advance(1)
        val first = w.done(0)
        assertEquals(listOf(EventType.STEP_COMPLETED), first.types())
        val ev = first.events.single()
        assertEquals(0, ev.stepIndex)
        assertEquals("First", ev.stepText)
        assertEquals(5L, ev.durationS)
        assertEquals(id, ev.takeoverId)
        assertEquals(1, w.state.active!!.stepsDone)
        assertNull(w.state.active!!.stepShownAtMs)

        // The next step needs its own stepShown, and the right index and takeover id.
        w.advance(30)
        assertTrue(w.done(1).events.isEmpty())
        w.shown()
        w.advance(10)
        assertTrue(w.done(0).events.isEmpty())
        assertTrue(w.done(2).events.isEmpty())
        assertTrue(w.done(1, id = "other").events.isEmpty())

        val last = w.done(1)
        assertEquals(listOf(EventType.STEP_COMPLETED, EventType.DIRECTIVE_COMPLETED), last.types())
        assertEquals(10L, last.events[0].durationS)
        val completed = last.events[1]
        assertEquals("a", completed.directiveId)
        assertEquals(id, completed.takeoverId)
        assertEquals("adhan", completed.trigger)
        assertEquals(105L, completed.durationS)
        assertEquals(2, completed.stepsDone)
        assertEquals(2, completed.stepsTotal)
        assertNull(w.state.active)
        assertFalse(last.showTakeover)
    }

    @Test
    fun zeroSecondStepStillNeedsToBeShown() {
        val instant = directive("instant", steps = listOf(Step("Now", 0)), tier = 1)
        val w = World(active = plainSet.copy(directives = plainSet.directives + instant))
        w.trigger("instant")
        assertTrue(w.done(0).events.isEmpty())
        w.shown()
        assertEquals(listOf(EventType.STEP_COMPLETED, EventType.DIRECTIVE_COMPLETED), w.done(0).types())
    }

    @Test
    fun stepShownDoesNotRestartTheLock() {
        val w = World()
        w.trigger("a")
        w.shown()
        val shownAt = w.state.active!!.stepShownAtMs
        w.advance(3)
        w.shown()
        assertEquals(shownAt, w.state.active!!.stepShownAtMs)
        val wrong = w.run { s, e -> Engine.stepShown(s, e, "other") }
        assertEquals(shownAt, wrong.state.active!!.stepShownAtMs)
        w.advance(2)
        assertEquals(listOf(EventType.STEP_COMPLETED), w.done(0).types())
    }

    @Test
    fun stepActionsWithoutTakeoverAreNoOps() {
        val w = World()
        val empty = w.state
        assertEquals(empty, w.run { s, e -> Engine.stepShown(s, e, "t1") }.state)
        assertEquals(empty, w.run { s, e -> Engine.stepDone(s, e, "t1", 0) }.state)
        assertEquals(empty, w.run { s, e -> Engine.dismiss(s, e, "t1") }.state)
        assertTrue(w.log.isEmpty())
    }

    @Test
    fun lockRemainingEdgeCases() {
        val ticket = Ticket("t", "a", "adhan", silent = false, firedAtMs = 0)
        assertEquals(5, Engine.lockRemainingSeconds(ticket, a, 1_000_000))
        val shown = ticket.copy(stepShownAtMs = 10_000)
        // The clock went backwards: still locked for the full time, never negative.
        assertEquals(5, Engine.lockRemainingSeconds(shown, a, 0))
        assertEquals(0, Engine.lockRemainingSeconds(shown, a, 60_000))
        // Past the last step there is nothing to lock.
        assertEquals(0, Engine.lockRemainingSeconds(shown.copy(stepsDone = 2), a, 10_000))
    }

    @Test
    fun stepDoneOutOfRangeDoesNotCrash() {
        // A new plan gave the directive fewer steps than were already ticked.
        val w = World()
        w.state = RuntimeState(active = Ticket("t9", "a", "adhan", false, t0.toEpochMilli(), stepsDone = 2, stepShownAtMs = t0.toEpochMilli()))
        w.advance(60)
        assertTrue(w.done(2, id = "t9").events.isEmpty())
    }

    @Test
    fun refreshDropsTakeoverWhoseDirectiveLostSteps() {
        val w = World()
        w.state = RuntimeState(
            lastSource = "plan",
            active = Ticket("t9", "a", "adhan", false, t0.toEpochMilli(), stepsDone = 2),
            pending = listOf(Ticket("t10", "b", "adhan", false, t0.toEpochMilli())),
        )
        val o = w.refresh()
        assertEquals(listOf(EventType.DROPPED), o.types())
        assertEquals("t9", o.events.single().takeoverId)
        assertEquals("b", w.state.active!!.directiveId)
    }

    // ------------------------------------------------------------------ dismiss

    @Test
    fun dismissLogsProgress() {
        val w = World()
        w.trigger("a")
        w.shown()
        w.advance(5)
        w.done(0)
        w.advance(20)
        val o = w.dismiss()
        assertEquals(listOf(EventType.DIRECTIVE_DISMISSED), o.types())
        val ev = o.events.single()
        assertEquals("a", ev.directiveId)
        assertEquals(2, ev.tier)
        assertEquals("t1", ev.takeoverId)
        assertEquals("adhan", ev.trigger)
        assertEquals(25L, ev.durationS)
        assertEquals(1, ev.stepsDone)
        assertEquals(2, ev.stepsTotal)
        assertNull(w.state.active)
        assertTrue(w.log.none { it.type == EventType.DIRECTIVE_COMPLETED })
    }

    @Test
    fun dismissWithWrongIdIsIgnored() {
        val w = World()
        w.trigger("a")
        val before = w.state
        assertTrue(w.dismiss("t7").events.isEmpty())
        assertEquals(before, w.state)
    }

    // ------------------------------------------------------------------ quiet mode

    @Test
    fun quietSwitchIsIdempotent() {
        val w = World()
        assertTrue(w.quiet(false).events.isEmpty())
        assertEquals(listOf(EventType.QUIET_ON), w.quiet(true).types())
        assertTrue(w.quiet(true).events.isEmpty())
        assertTrue(w.state.quiet)
        assertEquals(listOf(EventType.QUIET_OFF), w.quiet(false).types())
        assertFalse(w.state.quiet)
    }

    @Test
    fun quietOffReleasesQueueInOrder() {
        val w = World()
        w.quiet(true)
        w.trigger("b")
        w.advance(60)
        w.trigger("a")
        w.trigger("c")
        w.advance(60)
        val o = w.quiet(false)
        assertEquals(
            listOf(EventType.QUIET_OFF, EventType.DIRECTIVE_FIRED, EventType.DIRECTIVE_FIRED, EventType.DIRECTIVE_FIRED),
            o.types(),
        )
        assertEquals(listOf(null, "b", "a", "c"), o.events.map { it.directiveId })
        assertTrue(o.events.drop(1).all { it.trigger == Engine.TRIGGER_QUIET_QUEUE && it.silent == false })
        assertEquals("b", w.state.active!!.directiveId)
        assertEquals(listOf("a", "c"), w.state.pending.map { it.directiveId })
        assertTrue(w.state.quietQueue.isEmpty())
        assertEquals(w.now.toEpochMilli(), w.state.active!!.firedAtMs)
    }

    @Test
    fun quietOffRechecksBadDay() {
        // A plan change can leave unkept directives queued during bad-day mode; release must still skip them.
        val w = World()
        w.state = RuntimeState(
            quiet = true,
            badDay = "2026-10-14",
            lastSource = "plan",
            quietQueue = listOf(Queued("a", "adhan", 1), Queued("kept", "adhan", 2)),
        )
        val o = w.quiet(false)
        assertEquals(listOf(EventType.QUIET_OFF, EventType.SKIPPED_BAD_DAY, EventType.DIRECTIVE_FIRED), o.types())
        assertEquals("a", o.events[1].directiveId)
        assertEquals(Engine.TRIGGER_QUIET_QUEUE, o.events[1].trigger)
        assertEquals("kept", w.state.active!!.directiveId)
    }

    @Test
    fun quietOffAfterBadDayEndedFiresEverything() {
        val w = World(now = at(2026, 10, 14, 22, 0))
        w.quiet(true)
        w.badDay(true)
        w.trigger("kept")
        w.now = at(2026, 10, 15, 5, 0)
        w.trigger("a")
        val o = w.quiet(false)
        assertEquals(
            listOf(EventType.QUIET_OFF, EventType.DIRECTIVE_FIRED, EventType.DIRECTIVE_FIRED),
            o.types(),
        )
        assertEquals(listOf(null, "kept", "a"), o.events.map { it.directiveId })
    }

    // ------------------------------------------------------------------ bad day

    @Test
    fun badDayBypassesUnkeptTakeovers() {
        val w = World()
        w.trigger("a")
        w.shown()
        w.advance(5)
        w.done(0)
        w.trigger("b", "kept", "c")
        w.advance(55)
        val o = w.badDay(true)
        assertEquals(EventType.BAD_DAY_ON, o.events.first().type)
        val bypassed = o.events.filter { it.type == EventType.BYPASSED_BAD_DAY }
        assertEquals(setOf("a", "b", "c"), bypassed.map { it.directiveId }.toSet())
        assertEquals(4, o.events.size)
        assertTrue(o.events.none { it.type == EventType.DIRECTIVE_DISMISSED })
        val forA = bypassed.single { it.directiveId == "a" }
        assertEquals("t1", forA.takeoverId)
        assertEquals(1, forA.stepsDone)
        assertEquals(2, forA.stepsTotal)
        assertEquals(60L, forA.durationS)
        assertEquals("kept", w.state.active!!.directiveId)
        assertTrue(w.state.pending.isEmpty())
        assertEquals("2026-10-14", w.state.badDay)
    }

    @Test
    fun badDayKeepsKeptActiveTakeover() {
        val w = World()
        w.trigger("kept", "a")
        val active = w.state.active
        val o = w.badDay(true)
        assertEquals(listOf(EventType.BAD_DAY_ON, EventType.BYPASSED_BAD_DAY), o.types())
        assertEquals("a", o.events[1].directiveId)
        assertEquals(active, w.state.active)
        assertTrue(w.state.pending.isEmpty())
    }

    @Test
    fun badDayRemovesUnkeptQueuedWithAnEvent() {
        val w = World()
        w.quiet(true)
        w.trigger("a", "kept")
        val o = w.badDay(true)
        assertEquals(listOf(EventType.BAD_DAY_ON, EventType.SKIPPED_BAD_DAY), o.types())
        assertEquals("a", o.events[1].directiveId)
        assertEquals(listOf("kept"), w.state.quietQueue.map { it.directiveId })
        val released = w.quiet(false)
        assertEquals(listOf(EventType.QUIET_OFF, EventType.DIRECTIVE_FIRED), released.types())
        assertEquals("kept", w.state.active!!.directiveId)
    }

    @Test
    fun badDaySwitchIsIdempotentAndCanBeTurnedOff() {
        val w = World()
        assertTrue(w.badDay(false).events.isEmpty())
        w.badDay(true)
        assertTrue(w.badDay(true).events.isEmpty())
        val off = w.badDay(false)
        assertEquals(listOf(EventType.BAD_DAY_OFF), off.types())
        assertEquals("switched off", off.events.single().detail)
        assertNull(w.state.badDay)
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("a").types())
    }

    @Test
    fun badDayEndsAtNextDayBoundary() {
        val w = World(now = at(2026, 10, 14, 22, 0))
        val on = w.badDay(true)
        assertEquals("until 2026-10-15T04:00", on.events.single().detail)
        assertEquals("2026-10-14", w.state.badDay)

        w.now = at(2026, 10, 15, 3, 59, 59)
        assertTrue(w.refresh().events.isEmpty())
        assertTrue(Engine.isBadDay(w.state, w.env()))
        assertEquals(listOf(EventType.SKIPPED_BAD_DAY), w.trigger("a").types())

        w.now = at(2026, 10, 15, 4, 0)
        val off = w.refresh()
        assertEquals(listOf(EventType.BAD_DAY_OFF), off.types())
        assertEquals("day ended", off.events.single().detail)
        assertNull(w.state.badDay)
        assertFalse(Engine.isBadDay(w.state, w.env()))
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("a").types())
    }

    @Test
    fun badDayAfterMidnightEndsAtTodaysBoundary() {
        // 01:30 still belongs to Wednesday, so the bad day ends at 04:00 the same calendar morning.
        val w = World(now = at(2026, 10, 15, 1, 30))
        val on = w.badDay(true)
        assertEquals("2026-10-14", w.state.badDay)
        assertEquals("until 2026-10-15T04:00", on.events.single().detail)
        w.now = at(2026, 10, 15, 4, 0)
        assertEquals(listOf(EventType.BAD_DAY_OFF), w.refresh().types())
    }

    @Test
    fun badDayEndIsNoticedByAnyAction() {
        val w = World(now = at(2026, 10, 14, 22, 0))
        w.badDay(true)
        w.now = at(2026, 10, 16, 12, 0)
        val o = w.trigger("a")
        assertEquals(listOf(EventType.BAD_DAY_OFF, EventType.DIRECTIVE_FIRED), o.types())
    }

    // ------------------------------------------------------------------ plan changes and expiry

    private fun planOf(directives: List<Directive>, keep: List<String>, fallback: List<Directive>?) = Plan(
        schemaVersion = 1,
        weekOf = LocalDate.of(2026, 10, 12),
        expires = LocalDateTime.of(2026, 10, 19, 4, 0),
        generatedBy = "test",
        context = null,
        dayBoundary = boundary,
        directives = directives,
        badDay = BadDay(keep),
        fallbackPlan = fallback?.let { FallbackPlan(it) },
    )

    private fun World.resolve(plan: Plan?) {
        active = ActivePlanResolver.resolve(plan, BuiltinPlan.plan, LocalDateTime.ofInstant(now, zone))
    }

    @Test
    fun expiryToFallbackLogsOnceAndDropsOldTickets() {
        val fb = directive("fb")
        val plan = planOf(listOf(a, b, c), listOf("a"), listOf(fb))
        val w = World(now = at(2026, 10, 19, 3, 0))
        w.resolve(plan)
        assertEquals(PlanSource.PLAN, w.active.source)
        assertTrue(w.refresh().events.isEmpty())
        w.trigger("a", "b")
        w.quiet(true)
        w.trigger("c")

        w.now = at(2026, 10, 19, 4, 0)
        w.resolve(plan)
        assertEquals(PlanSource.FALLBACK, w.active.source)
        val o = w.refresh()
        assertEquals(EventType.PLAN_EXPIRED, o.events.first().type)
        assertEquals("now running fallback_plan", o.events.first().detail)
        val dropped = o.events.drop(1)
        assertTrue(dropped.all { it.type == EventType.DROPPED })
        assertEquals(setOf("a", "b", "c"), dropped.map { it.directiveId }.toSet())
        assertEquals(4, o.events.size)
        assertNull(w.state.active)
        assertTrue(w.state.pending.isEmpty())
        assertTrue(w.state.quietQueue.isEmpty())
        assertEquals("fallback_plan", w.state.lastSource)

        w.advance(3600)
        assertTrue(w.refresh().events.isEmpty())
        w.quiet(false)
        assertTrue(w.trigger("a").events.isEmpty())
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("fb").types())
        assertEquals(1, w.log.count { it.type == EventType.PLAN_EXPIRED })
    }

    @Test
    fun expiryWithoutFallbackRunsBuiltinDefault() {
        val plan = planOf(listOf(a), listOf("a"), fallback = null)
        val w = World(now = at(2026, 10, 18, 12, 0))
        w.resolve(plan)
        w.refresh()
        w.now = at(2026, 10, 19, 4, 0, 1)
        w.resolve(plan)
        assertEquals(PlanSource.DEFAULT, w.active.source)
        val o = w.refresh()
        assertEquals(listOf(EventType.PLAN_EXPIRED), o.types())
        assertEquals("now running builtin_default", o.events.single().detail)
        assertTrue(w.refresh().events.isEmpty())
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("default-isha").types())
    }

    @Test
    fun noPlanAndNewImportAreNotExpiry() {
        val w = World()
        w.resolve(null)
        assertEquals(PlanSource.DEFAULT, w.active.source)
        assertTrue(w.refresh().events.isEmpty())
        assertEquals("builtin_default", w.state.lastSource)
        w.resolve(planOf(listOf(a), listOf("a"), null))
        assertTrue(w.refresh().events.isEmpty())
        assertEquals("plan", w.state.lastSource)
    }

    @Test
    fun ticketsForRemovedDirectivesAreDropped() {
        val w = World()
        w.trigger("a", "b", "c")
        w.advance(120)
        w.active = plainSet.copy(directives = listOf(c))
        val o = w.refresh()
        assertEquals(listOf(EventType.DROPPED, EventType.DROPPED), o.types())
        assertEquals(setOf("a", "b"), o.events.map { it.directiveId }.toSet())
        assertTrue(o.events.all { it.detail == "no longer in the active plan" && it.takeoverId != null })
        assertEquals("c", w.state.active!!.directiveId)
        assertTrue(w.state.pending.isEmpty())
    }

    @Test
    fun queuedForRemovedDirectivesAreDroppedWithAnEvent() {
        val w = World()
        w.quiet(true)
        w.trigger("a", "c")
        w.active = plainSet.copy(directives = listOf(c))
        val o = w.refresh()
        assertEquals(listOf(EventType.DROPPED), o.types())
        assertEquals("a", o.events.single().directiveId)
        assertEquals(listOf("c"), w.state.quietQueue.map { it.directiveId })
    }

    @Test
    fun refreshRepairsActiveMissingWithPending() {
        val w = World()
        w.state = RuntimeState(lastSource = "plan", pending = listOf(Ticket("t5", "b", "adhan", false, t0.toEpochMilli())))
        val o = w.refresh()
        assertTrue(o.showTakeover)
        assertEquals("t5", w.state.active!!.takeoverId)
    }

    // ------------------------------------------------------------------ timeout

    @Test
    fun takeoverTimesOut() {
        val w = World()
        w.trigger("a")
        w.shown()
        w.advance(5)
        w.done(0)
        w.now = t0.plusSeconds(Engine.MAX_TAKEOVER_MINUTES * 60 - 1)
        assertTrue(w.refresh().events.isEmpty())
        w.advance(1)
        val o = w.refresh()
        assertEquals(listOf(EventType.DIRECTIVE_TIMED_OUT), o.types())
        val ev = o.events.single()
        assertEquals("a", ev.directiveId)
        assertEquals(Engine.MAX_TAKEOVER_MINUTES * 60, ev.durationS)
        assertEquals(1, ev.stepsDone)
        assertEquals(2, ev.stepsTotal)
        assertEquals(2, ev.tier)
        assertNull(w.state.active)
    }

    @Test
    fun promotedTakeoverGetsItsOwnFullTime() {
        val w = World()
        w.trigger("a")
        w.advance(60)
        w.trigger("b")
        w.now = t0.plusSeconds(Engine.MAX_TAKEOVER_MINUTES * 60)
        assertEquals(listOf(EventType.DIRECTIVE_TIMED_OUT), w.refresh().types())
        val promotedAt = w.now
        assertEquals("b", w.state.active!!.directiveId)

        // b waited 89 minutes behind a, but has only just appeared: it must not time out a minute later.
        w.advance(60)
        assertTrue(w.refresh().events.isEmpty())
        assertEquals("b", w.state.active!!.directiveId)

        w.now = promotedAt.plusSeconds(Engine.MAX_TAKEOVER_MINUTES * 60)
        val o = w.refresh()
        assertEquals(listOf(EventType.DIRECTIVE_TIMED_OUT), o.types())
        assertEquals("b", o.events.single().directiveId)
    }

    @Test
    fun promotedAfterCompletionAlsoGetsFullTime() {
        val w = World()
        w.trigger("a", "b")
        w.now = t0.plusSeconds(Engine.MAX_TAKEOVER_MINUTES * 60 - 30)
        w.completeActive()
        assertEquals("b", w.state.active!!.directiveId)
        w.advance(120)
        assertTrue(w.refresh().events.isEmpty())
        assertEquals("b", w.state.active!!.directiveId)
    }

    // ------------------------------------------------------------------ debug

    @Test
    fun fireNowDemoIgnoresModesAndCompletes() {
        val w = World()
        w.quiet(true)
        w.badDay(true)
        val o = w.run { s, e -> Engine.fireNow(s, e, Engine.DEMO_ID) }
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), o.types())
        val ev = o.events.single()
        assertEquals(Engine.DEMO_ID, ev.directiveId)
        assertEquals("debug", ev.trigger)
        assertEquals(false, ev.silent)
        assertEquals(3, ev.tier)
        assertEquals(Engine.DEMO.steps.size, ev.stepsTotal)
        assertEquals(Engine.DEMO_ID, w.state.active!!.directiveId)

        // The demo is not in the plan, but must survive housekeeping.
        assertTrue(w.refresh().events.isEmpty())
        val done = w.completeActive()
        assertEquals(EventType.DIRECTIVE_COMPLETED, done.events.last().type)
        assertEquals(Engine.DEMO.steps.size, done.events.last().stepsDone)
        assertNull(w.state.active)
    }

    @Test
    fun fireNowPlanDirectiveAndUnknown() {
        val w = World()
        w.quiet(true)
        val unknown = w.run { s, e -> Engine.fireNow(s, e, "nope") }
        assertTrue(unknown.events.isEmpty())
        assertNull(w.state.active)
        val o = w.run { s, e -> Engine.fireNow(s, e, "a") }
        assertEquals("debug", o.events.single().trigger)
        assertEquals("a", w.state.active!!.directiveId)
        assertTrue(w.state.quietQueue.isEmpty())
    }

    // ------------------------------------------------------------------ state and env

    @Test
    fun runtimeStateRoundTrip() {
        val full = RuntimeState(
            quiet = true,
            badDay = "2026-10-14",
            quietQueue = listOf(Queued("a", "adhan", 1), Queued("b", "time", 2)),
            active = Ticket(
                "t1", "c", "time", silent = true, firedAtMs = 3, scheduledFor = "2026-10-14T07:00:00+01:00",
                stepsDone = 1, stepShownAtMs = 4, activeSinceMs = 5,
            ),
            pending = listOf(Ticket("t2", "kept", "app_usage", silent = false, firedAtMs = 6)),
            scheduleCursorMs = 7,
            lastSource = "fallback_plan",
        )
        assertEquals(full, RuntimeState.decode(full.encode()))
        assertEquals(RuntimeState(), RuntimeState.decode(RuntimeState().encode()))
    }

    @Test
    fun runtimeStateDecodeOfGarbageIsDefault() {
        val garbage = listOf(
            null, "", "   ", "not json", "null", "[]", "42", "\"text\"", "{", "{\"quiet\": \"yes\"}",
            "{\"active\": {\"takeover_id\": \"t1\"}}", "{\"pending\": 3}", "{\"quiet\":true}trailing",
        )
        for (text in garbage) assertEquals("decode($text)", RuntimeState(), RuntimeState.decode(text))
    }

    @Test
    fun runtimeStateDecodeIgnoresUnknownAndMissingFields() {
        assertEquals(RuntimeState(quiet = true), RuntimeState.decode("{\"quiet\": true, \"from_the_future\": [1, 2]}"))
        // A state saved before active_since_ms existed still loads.
        val old = "{\"active\": {\"takeover_id\": \"t1\", \"directive_id\": \"a\", \"trigger\": \"adhan\", \"silent\": false, \"fired_at_ms\": 9}}"
        assertEquals(Ticket("t1", "a", "adhan", false, 9), RuntimeState.decode(old).active)
    }

    @Test
    fun timestampAlwaysHasSeconds() {
        val env = Env(t0, zone, plainSet, false) { "x" }
        assertEquals("2026-10-14T10:00:00+01:00", env.timestamp())
        val winter = Env(at(2026, 12, 1, 7, 5, 9).plusMillis(999), zone, plainSet, false) { "x" }
        assertEquals("2026-12-01T07:05:09Z", winter.timestamp())
        val kolkata = Env(Instant.parse("2026-10-14T00:00:00Z"), ZoneId.of("Asia/Kolkata"), plainSet, false) { "x" }
        assertEquals("2026-10-14T05:30:00+05:30", kolkata.timestamp())
    }

    @Test
    fun envTodayUsesDayBoundary() {
        val late = Env(at(2026, 10, 15, 3, 59), zone, plainSet, false) { "x" }
        assertEquals("2026-10-14", late.today)
        val morning = Env(at(2026, 10, 15, 4, 0), zone, plainSet, false) { "x" }
        assertEquals("2026-10-15", morning.today)
    }
}
