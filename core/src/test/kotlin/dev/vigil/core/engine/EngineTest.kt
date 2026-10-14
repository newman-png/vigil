package dev.vigil.core.engine

import dev.vigil.core.log.EventType
import dev.vigil.core.log.LogEvent
import dev.vigil.core.plan.AfterBadDay
import dev.vigil.core.plan.BadDay
import dev.vigil.core.plan.CheckInAnswer
import dev.vigil.core.plan.DayMode
import dev.vigil.core.plan.Directive
import dev.vigil.core.plan.FallbackPlan
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.Prayer
import dev.vigil.core.plan.QuietPolicy
import dev.vigil.core.plan.Rating
import dev.vigil.core.plan.RatingPolicy
import dev.vigil.core.plan.Step
import dev.vigil.core.plan.Trigger
import dev.vigil.core.plan.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private inner class World(
        var now: Instant = t0,
        var active: ActiveSet = plainSet,
        var allowOverride: Boolean = false,
        var planKey: String? = null,
    ) {
        private var ids = 0
        var state = RuntimeState()
        val log = mutableListOf<LogEvent>()

        fun env() = Env(now, zone, active, allowOverride, planKey) { "t${++ids}" }

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
        fun fireNow(id: String) = run { s, e -> Engine.fireNow(s, e, id) }
        fun present() = run { s, e -> Engine.onUserPresent(s, e) }
        fun answer(answer: CheckInAnswer, id: String = state.active!!.takeoverId) = run { s, e -> Engine.answerCheckIn(s, e, id, answer) }
        fun rate(rating: Rating?, id: String = state.pendingRating!!.takeoverId) = run { s, e -> Engine.rate(s, e, id, rating) }

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

        /** Triggers [id] and completes it; returns its takeover id. */
        fun complete(id: String, via: String = "adhan"): String {
            trigger(id, trigger = via)
            val ticket = state.active!!
            check(ticket.directiveId == id) { "$id is not on screen" }
            completeActive()
            return ticket.takeoverId
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
        // A repeat is not stacked, but it is logged so the planner can see it happened.
        assertEquals(listOf(EventType.REPEATED), again.events.map { it.type })
        assertEquals("a", again.events.single().directiveId)
        assertTrue(w.state.pending.isEmpty())

        w.trigger("b")
        assertEquals(listOf("b"), w.state.pending.map { it.directiveId })
        assertEquals(listOf(EventType.REPEATED), w.trigger("b").events.map { it.type })
        assertEquals(listOf("a", "b"), w.trigger("a", "b").events.map { it.directiveId })
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
    fun quietOnAppliesToTakeoversThatAlreadyFired() {
        val w = World()
        w.trigger("a") // showing: a queue directive, so it goes silent but stays on screen
        w.trigger("b", "ignored", "silent-one", "loud") // waiting
        val o = w.quiet(true)

        assertEquals("a", w.state.active!!.directiveId)
        assertTrue("the takeover on screen goes silent", w.state.active!!.silent)
        // b (queue) moves to the quiet queue; ignored is skipped; silent-one waits silently; loud waits, silent (no override allowed).
        assertEquals(listOf("silent-one", "loud"), w.state.pending.map { it.directiveId })
        assertTrue(w.state.pending.all { it.silent })
        assertEquals(listOf("b"), w.state.quietQueue.map { it.directiveId })
        val dropped = o.events.filter { it.type == EventType.DROPPED }
        assertEquals(listOf("b", "ignored"), dropped.map { it.directiveId })
        assertEquals(listOf(Engine.DETAIL_HELD_FOR_QUIET, Engine.DETAIL_SKIPPED_FOR_QUIET), dropped.map { it.detail })
        assertEquals(listOf("b"), o.events.filter { it.type == EventType.QUEUED_QUIET }.map { it.directiveId })

        // Quiet off releases b again, as a new takeover.
        val off = w.quiet(false)
        assertEquals(listOf("silent-one", "loud", "b"), w.state.pending.map { it.directiveId })
        assertEquals(Engine.TRIGGER_QUIET_QUEUE, off.events.single { it.type == EventType.DIRECTIVE_FIRED }.trigger)
    }

    @Test
    fun quietOnKeepsSoundForAnAllowedOverride() {
        val w = World(allowOverride = true)
        w.trigger("loud")
        w.quiet(true)
        assertFalse(w.state.active!!.silent)
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
        assertEquals(DayModeState("2026-10-14", "bad_day", DayModeState.SOURCE_USER), w.state.dayMode)
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
        assertNull(w.state.dayMode)
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("a").types())
    }

    @Test
    fun badDayEndsAtNextDayBoundary() {
        val w = World(now = at(2026, 10, 14, 22, 0))
        val on = w.badDay(true)
        assertEquals("until 2026-10-15T04:00", on.events.single().detail)
        assertEquals("2026-10-14", w.state.dayMode?.day)

        w.now = at(2026, 10, 15, 3, 59, 59)
        assertTrue(w.refresh().events.isEmpty())
        assertTrue(Engine.isBadDay(w.state, w.env()))
        assertEquals(listOf(EventType.SKIPPED_BAD_DAY), w.trigger("a").types())

        // The day ending is day_mode; bad_day_off is only the user's "I'm okay again".
        w.now = at(2026, 10, 15, 4, 0)
        val off = w.refresh()
        assertEquals(listOf(EventType.DAY_MODE), off.types())
        assertEquals("day ended", off.events.single().detail)
        assertEquals("normal", off.events.single().mode)
        assertNull(w.state.dayMode)
        assertNull(w.state.checkIn)
        assertFalse(Engine.isBadDay(w.state, w.env()))
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("a").types())
    }

    @Test
    fun badDayAfterMidnightEndsAtTodaysBoundary() {
        // 01:30 still belongs to Wednesday, so the bad day ends at 04:00 the same calendar morning.
        val w = World(now = at(2026, 10, 15, 1, 30))
        val on = w.badDay(true)
        assertEquals("2026-10-14", w.state.dayMode?.day)
        assertEquals("until 2026-10-15T04:00", on.events.single().detail)
        w.now = at(2026, 10, 15, 4, 0)
        assertEquals(listOf(EventType.DAY_MODE), w.refresh().types())
    }

    @Test
    fun badDayEndIsNoticedByAnyAction() {
        val w = World(now = at(2026, 10, 14, 22, 0))
        w.badDay(true)
        w.now = at(2026, 10, 16, 12, 0)
        val o = w.trigger("a")
        assertEquals(listOf(EventType.DAY_MODE, EventType.DIRECTIVE_FIRED), o.types())
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

    // ------------------------------------------------------------------ day modes

    /** "b" is kept on a bad day but not on a minimum day; "kept" is kept on both. */
    private val modesSet = plainSet.copy(
        badDayKeep = setOf("kept", "b"),
        minimumKeep = setOf("kept"),
        afterBadDay = AfterBadDay(better = DayMode.NORMAL, same = DayMode.BAD_DAY, worse = DayMode.MINIMUM),
    )

    private fun minimumToday() = RuntimeState(lastSource = "plan", dayMode = DayModeState("2026-10-14", "minimum", DayModeState.SOURCE_CHECK_IN))

    @Test
    fun decideFollowsTheDayMode() {
        for (quiet in listOf(false, true)) {
            val b = directive("b")
            val normal = Engine.decide(b, quiet, DayMode.NORMAL, modesSet, allowOverride = false)
            assertEquals(if (quiet) Decision.Queue else Decision.Fire(silent = false), normal)
            // b is in bad_day.keep: a bad day changes nothing for it.
            assertEquals(normal, Engine.decide(b, quiet, DayMode.BAD_DAY, modesSet, allowOverride = false))
            // It is not in minimum.keep: skipped as minimum, whatever quiet mode says.
            assertEquals(Decision.Skip(EventType.SKIPPED_MINIMUM), Engine.decide(b, quiet, DayMode.MINIMUM, modesSet, allowOverride = false))
            assertEquals(normal, Engine.decide(directive("kept"), quiet, DayMode.MINIMUM, modesSet, allowOverride = false))
            assertEquals(
                Decision.Skip(EventType.SKIPPED_BAD_DAY),
                Engine.decide(directive("a"), quiet, DayMode.BAD_DAY, modesSet, allowOverride = false),
            )
            // The v1 overload still answers the same.
            assertEquals(normal, Engine.decide(b, quiet, badDay = true, keep = modesSet.badDayKeep, allowOverride = false))
        }
    }

    @Test
    fun v1BadDayStateIsMigrated() {
        val v1 = """{"quiet":false,"bad_day":"2026-10-14","last_source":"plan"}"""
        val w = World()
        w.state = RuntimeState.decode(v1)
        assertEquals("2026-10-14", w.state.badDay)
        // The app already sees the bad day before the first refresh moves it.
        assertTrue(Engine.isBadDay(w.state, w.env()))
        assertEquals(DayMode.BAD_DAY, Engine.dayMode(w.state, w.env()))
        assertTrue(w.refresh().events.isEmpty())
        assertNull(w.state.badDay)
        assertEquals(DayModeState("2026-10-14", "bad_day", DayModeState.SOURCE_USER), w.state.dayMode)
        assertFalse(w.state.encode().contains("\"bad_day\":"))
        assertEquals(listOf(EventType.SKIPPED_BAD_DAY), w.trigger("a").types())
        assertEquals(listOf(EventType.BAD_DAY_OFF), w.badDay(false).types())

        // A v1 bad day from yesterday ends with day_mode, the first thing the new version logs.
        val old = World(now = at(2026, 10, 15, 9, 0))
        old.state = RuntimeState.decode(v1)
        assertFalse(Engine.isBadDay(old.state, old.env()))
        val o = old.refresh()
        assertEquals(listOf(EventType.DAY_MODE), o.types())
        assertEquals("day ended", o.events.single().detail)
        assertNull(old.state.badDay)
        assertNull(old.state.dayMode)

        // With after_bad_day it is a bad day like any other: the check-in is due.
        val withCheckIn = World(now = at(2026, 10, 15, 9, 0), active = modesSet)
        withCheckIn.state = RuntimeState.decode(v1)
        withCheckIn.refresh()
        assertEquals(CheckInState("2026-10-15", "bad_day"), withCheckIn.state.checkIn)
    }

    @Test
    fun minimumDayFiresOnlyMinimumKeep() {
        val w = World(active = modesSet)
        w.state = minimumToday()
        assertEquals(DayMode.MINIMUM, Engine.dayMode(w.state, w.env()))
        assertFalse(Engine.isBadDay(w.state, w.env()))
        val skipped = w.trigger("b")
        assertEquals(listOf(EventType.SKIPPED_MINIMUM), skipped.types())
        assertEquals("b", skipped.events.single().directiveId)
        assertEquals("adhan", skipped.events.single().trigger)
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("kept").types())

        // Directives held by quiet mode from before are re-checked when it ends.
        val q = World(active = modesSet)
        q.state = minimumToday().copy(quiet = true, quietQueue = listOf(Queued("a", "adhan", 1), Queued("kept", "adhan", 2)))
        val o = q.quiet(false)
        assertEquals(listOf(EventType.QUIET_OFF, EventType.SKIPPED_MINIMUM, EventType.DIRECTIVE_FIRED), o.types())
        assertEquals("a", o.events[1].directiveId)
        assertEquals(Engine.TRIGGER_QUIET_QUEUE, o.events[1].trigger)
        assertEquals("kept", q.state.active!!.directiveId)
    }

    @Test
    fun okayAgainEndsAMinimumDayAndNotOkayLeavesItAlone() {
        val w = World(active = modesSet)
        w.state = minimumToday()
        assertTrue("already a minimum day", w.badDay(true).events.isEmpty())
        assertEquals(DayMode.MINIMUM, Engine.dayMode(w.state, w.env()))
        val off = w.badDay(false)
        assertEquals(listOf(EventType.BAD_DAY_OFF), off.types())
        assertEquals("switched off", off.events.single().detail)
        assertNull(w.state.dayMode)
        assertEquals(DayMode.NORMAL, Engine.dayMode(w.state, w.env()))
        assertTrue(w.badDay(false).events.isEmpty())
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("a").types())
    }

    @Test
    fun aMinimumDayUnderAPlanWithoutMinimumKeepsItsEssentials() {
        // A minimum day chosen under the imported plan, which then expired without a fallback: the built-in plan has no
        // minimum list, so its bad_day.keep (the prayers and sleep) still fires instead of nothing.
        val builtinSet = ActivePlanResolver.resolve(null, BuiltinPlan.plan, LocalDateTime.ofInstant(t0, zone))
        val w = World(active = builtinSet)
        w.state = minimumToday().copy(lastSource = PlanSource.DEFAULT.json)
        assertEquals(DayMode.MINIMUM, Engine.dayMode(w.state, w.env()))
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("default-isha").types())
        w.dismiss()
        assertEquals(listOf(EventType.SKIPPED_MINIMUM), w.trigger("default-water-am", trigger = "time").types())
    }

    // ------------------------------------------------------------------ the morning check-in

    /** Wednesday was a bad day; it is now Thursday 07:00 and the morning check-in is due (not yet shown). */
    private fun checkInDue(active: ActiveSet = modesSet): World {
        val w = World(now = at(2026, 10, 14, 22, 0), active = active)
        w.badDay(true)
        w.now = at(2026, 10, 15, 7, 0)
        w.refresh()
        return w
    }

    private val sameIsNormal = modesSet.copy(afterBadDay = AfterBadDay(DayMode.NORMAL, DayMode.NORMAL, DayMode.MINIMUM))

    @Test
    fun checkInIsDueTheMorningAfterABadDay() {
        val w = World(now = at(2026, 10, 14, 22, 0), active = modesSet)
        w.badDay(true)
        w.now = at(2026, 10, 15, 3, 59)
        assertTrue("still the bad day", w.refresh().events.isEmpty())
        assertNull(w.state.checkIn)

        w.now = at(2026, 10, 15, 4, 0)
        val o = w.refresh()
        assertEquals(listOf(EventType.DAY_MODE), o.types())
        val ev = o.events.single()
        assertEquals("bad_day", ev.mode)
        assertEquals("check-in due after a bad day; until it is answered the \"same\" answer applies", ev.detail)
        assertEquals(CheckInState("2026-10-15", "bad_day"), w.state.checkIn)
        assertEquals(DayModeState("2026-10-15", "bad_day", DayModeState.SOURCE_CHECK_IN_PENDING), w.state.dayMode)

        // Until it is answered the "same" answer (here bad_day) applies: b is kept on a bad day, a is not.
        assertEquals(DayMode.BAD_DAY, Engine.dayMode(w.state, w.env()))
        assertEquals(listOf(EventType.SKIPPED_BAD_DAY), w.trigger("a").types())
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("b").types())
        // It never fires by itself: it waits for the user to be at the phone.
        assertTrue(w.log.none { it.directiveId == Engine.CHECK_IN_ID })
        assertTrue(w.refresh().events.isEmpty())
    }

    @Test
    fun checkInIsDueAfterAMinimumDay() {
        val w = World(now = at(2026, 10, 15, 4, 30), active = modesSet)
        w.state = minimumToday()
        val o = w.refresh()
        assertEquals(listOf(EventType.DAY_MODE), o.types())
        assertEquals("check-in due after a minimum day; until it is answered the \"same\" answer applies", o.events.single().detail)
        assertEquals(CheckInState("2026-10-15", "minimum"), w.state.checkIn)
        assertEquals(DayMode.BAD_DAY, Engine.dayMode(w.state, w.env()))
    }

    @Test
    fun noCheckInWithoutAfterBadDay() {
        val w = World(now = at(2026, 10, 14, 22, 0))
        w.badDay(true)
        w.now = at(2026, 10, 15, 7, 0)
        val o = w.present()
        assertEquals(listOf(EventType.DAY_MODE), o.types())
        assertEquals("normal", o.events.single().mode)
        assertEquals("day ended", o.events.single().detail)
        assertNull(w.state.checkIn)
        assertNull(w.state.active)
        assertEquals(DayMode.NORMAL, Engine.dayMode(w.state, w.env()))
    }

    @Test
    fun noCheckInAfterAGapOfMoreThanADay() {
        val w = World(now = at(2026, 10, 14, 22, 0), active = modesSet)
        w.badDay(true)
        // Nothing ran on Thursday; the next refresh is on Friday.
        w.now = at(2026, 10, 16, 12, 0)
        val o = w.present()
        assertEquals(listOf(EventType.DAY_MODE), o.types())
        assertEquals("day ended", o.events.single().detail)
        assertNull(w.state.checkIn)
        assertNull(w.state.dayMode)
        assertNull(w.state.active)
    }

    @Test
    fun provisionalSameCanBeNormal() {
        val w = checkInDue(sameIsNormal)
        val ev = w.log.last()
        assertEquals(EventType.DAY_MODE, ev.type)
        assertEquals("normal", ev.mode)
        assertTrue(ev.detail!!.startsWith("check-in due after a bad day"))
        assertNull(w.state.dayMode)
        assertEquals(CheckInState("2026-10-15", "bad_day"), w.state.checkIn)
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.trigger("a").types())
    }

    @Test
    fun onUserPresentFiresTheCheckInOnce() {
        val w = checkInDue()
        assertNull(w.state.active)
        val o = w.present()
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), o.types())
        val ev = o.events.single()
        assertEquals(Engine.CHECK_IN_ID, ev.directiveId)
        assertEquals(1, ev.tier)
        assertEquals(Engine.TRIGGER_CHECK_IN, ev.trigger)
        assertEquals(false, ev.silent)
        assertEquals(1, ev.stepsTotal)
        val t = w.state.active!!
        assertEquals(Engine.CHECK_IN_ID, t.directiveId)
        assertEquals(CheckInState("2026-10-15", "bad_day", fired = true, takeoverId = t.takeoverId), w.state.checkIn)
        assertTrue(w.present().events.isEmpty())

        // It is answered, not ticked.
        w.shown()
        assertTrue(w.done(0).events.isEmpty())
        assertEquals(t.takeoverId, w.state.active!!.takeoverId)
        assertNull("a check-in is never rated", w.state.pendingRating)

        // Housekeeping keeps it although no plan names it.
        assertTrue(w.refresh().events.isEmpty())
        assertEquals(t.takeoverId, w.state.active!!.takeoverId)
    }

    @Test
    fun checkInWaitsBehindATakeoverAndIsSilentInQuietMode() {
        val w = checkInDue()
        w.trigger("b")
        w.quiet(true)
        val o = w.present()
        assertEquals(true, o.events.single().silent)
        assertEquals("b", w.state.active!!.directiveId)
        val waiting = w.state.pending.single()
        assertEquals(Engine.CHECK_IN_ID, waiting.directiveId)
        assertTrue(waiting.silent)
        w.dismiss()
        assertEquals(Engine.CHECK_IN_ID, w.state.active!!.directiveId)
    }

    @Test
    fun checkInAnswersPickTheMode() {
        val expected = mapOf(CheckInAnswer.BETTER to DayMode.NORMAL, CheckInAnswer.SAME to DayMode.BAD_DAY, CheckInAnswer.WORSE to DayMode.MINIMUM)
        for ((answer, mode) in expected) {
            val w = checkInDue()
            w.present()
            val id = w.state.active!!.takeoverId
            w.advance(12)
            val o = w.answer(answer)
            assertEquals(answer.json, listOf(EventType.CHECK_IN), o.types())
            val ev = o.events.single()
            assertEquals(answer.json, ev.answer)
            assertEquals(mode.json, ev.mode)
            assertEquals(id, ev.takeoverId)
            assertEquals(Engine.TRIGGER_CHECK_IN, ev.trigger)
            assertEquals("for 2026-10-15, after a bad day", ev.detail)
            assertNull(w.state.active)
            assertNull(w.state.checkIn)
            assertEquals(mode, Engine.dayMode(w.state, w.env()))
            val stored = if (mode == DayMode.NORMAL) null else DayModeState("2026-10-15", mode.json, DayModeState.SOURCE_CHECK_IN)
            assertEquals(stored, w.state.dayMode)
            // Answering again does nothing, and it does not come back.
            assertTrue(w.answer(answer, id).events.isEmpty())
            assertTrue(w.present().events.isEmpty())
            val b = w.trigger("b").types()
            assertEquals(answer.json, if (mode == DayMode.MINIMUM) listOf(EventType.SKIPPED_MINIMUM) else listOf(EventType.DIRECTIVE_FIRED), b)
        }
    }

    @Test
    fun checkInAnswerBypassesUnkeptFiredTakeovers() {
        val w = checkInDue(sameIsNormal)
        w.quiet(true)
        w.trigger("c", "kept") // both quiet: queue, so held
        w.present() // the check-in, silent
        w.trigger("silent-one") // waits behind it, silent
        w.advance(30)
        val o = w.answer(CheckInAnswer.WORSE)
        assertEquals(listOf(EventType.CHECK_IN, EventType.BYPASSED_MINIMUM, EventType.SKIPPED_MINIMUM), o.types())
        assertEquals("minimum", o.events[0].mode)
        val bypassed = o.events[1]
        assertEquals("silent-one", bypassed.directiveId)
        assertNotNull(bypassed.takeoverId)
        assertEquals(30L, bypassed.durationS)
        assertEquals(0, bypassed.stepsDone)
        assertEquals(2, bypassed.stepsTotal)
        assertEquals("c", o.events[2].directiveId)
        assertEquals(Engine.TRIGGER_QUIET_QUEUE, o.events[2].trigger)
        assertNull(w.state.active)
        assertTrue(w.state.pending.isEmpty())
        assertEquals(listOf("kept"), w.state.quietQueue.map { it.directiveId })
        assertEquals(listOf(EventType.QUIET_OFF, EventType.DIRECTIVE_FIRED), w.quiet(false).types())
        assertEquals("kept", w.state.active!!.directiveId)
        assertTrue(w.log.none { it.type == EventType.DIRECTIVE_DISMISSED || it.type == EventType.DIRECTIVE_TIMED_OUT })
    }

    @Test
    fun checkInAnswerBetterLeavesFiredTakeoversAlone() {
        val w = checkInDue(sameIsNormal)
        w.present()
        w.trigger("a")
        val o = w.answer(CheckInAnswer.BETTER)
        assertEquals(listOf(EventType.CHECK_IN), o.types())
        assertEquals("a", w.state.active!!.directiveId)
    }

    @Test
    fun testCheckInChangesNothing() {
        val w = checkInDue()
        val before = w.state
        val fired = w.fireNow(Engine.CHECK_IN_ID)
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), fired.types())
        assertEquals(Engine.CHECK_IN_ID, fired.events.single().directiveId)
        assertEquals("debug", fired.events.single().trigger)
        assertEquals("the real check-in is untouched", before.checkIn, w.state.checkIn)
        // The real one waits until the preview has closed.
        assertTrue(w.present().events.isEmpty())

        val o = w.answer(CheckInAnswer.WORSE)
        assertEquals(listOf(EventType.CHECK_IN), o.types())
        val ev = o.events.single()
        assertEquals("worse", ev.answer)
        assertEquals("minimum", ev.mode)
        assertEquals("debug", ev.trigger)
        assertEquals("test: nothing changed", ev.detail)
        assertNull(w.state.active)
        assertEquals(before.checkIn, w.state.checkIn)
        assertEquals(before.dayMode, w.state.dayMode)
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.present().types())

        // Without after_bad_day the preview still works, and the answer would pick normal.
        val plain = World()
        plain.fireNow(Engine.CHECK_IN_ID)
        val answered = plain.answer(CheckInAnswer.WORSE).events.single()
        assertEquals("normal", answered.mode)
        assertEquals(RuntimeState(lastSource = "plan"), plain.state)
    }

    @Test
    fun dismissedCheckInLogsNoneAndKeepsTheSameMode() {
        val w = checkInDue()
        w.present()
        val id = w.state.active!!.takeoverId
        w.advance(20)
        val o = w.dismiss()
        assertEquals(listOf(EventType.CHECK_IN), o.types())
        val ev = o.events.single()
        assertEquals(Engine.ANSWER_NONE, ev.answer)
        assertEquals("bad_day", ev.mode)
        assertEquals("dismissed", ev.detail)
        assertEquals(id, ev.takeoverId)
        assertNull(w.state.active)
        assertNull(w.state.checkIn)
        assertEquals(DayModeState("2026-10-15", "bad_day", DayModeState.SOURCE_CHECK_IN_PENDING), w.state.dayMode)
        assertTrue(w.log.none { it.type == EventType.DIRECTIVE_DISMISSED })
        assertTrue(w.present().events.isEmpty())

        // Resolved: the next morning logs no second check_in for it (the "same" bad day asks again, though).
        w.now = at(2026, 10, 16, 7, 0)
        val next = w.refresh()
        assertEquals(listOf(EventType.DAY_MODE), next.types())
        assertEquals(CheckInState("2026-10-16", "bad_day"), w.state.checkIn)
    }

    @Test
    fun checkInDismissedAfterTheBoundaryLogsTheModeOfItsOwnDay() {
        // Shown at 03:00 (still Thursday) and dismissed just after Friday's boundary, before anything refreshed.
        val w = checkInDue()
        w.now = at(2026, 10, 16, 3, 0)
        w.present()
        val id = w.state.active!!.takeoverId
        w.now = at(2026, 10, 16, 4, 1)
        val ev = w.dismiss().events.single()
        assertEquals(EventType.CHECK_IN, ev.type)
        assertEquals(Engine.ANSWER_NONE, ev.answer)
        assertEquals("Thursday's \"same\" mode, not Friday's", "bad_day", ev.mode)
        assertEquals("dismissed", ev.detail)
        assertEquals(id, ev.takeoverId)
        assertNull(w.state.checkIn)
        // The next refresh ends Thursday without a second check_in for it, and Friday asks again.
        assertEquals(listOf(EventType.DAY_MODE), w.refresh().types())
        assertEquals(CheckInState("2026-10-16", "bad_day"), w.state.checkIn)
    }

    @Test
    fun aDismissedPreviewLeavesTheRealCheckInAlone() {
        val w = checkInDue()
        w.fireNow(Engine.CHECK_IN_ID)
        val ev = w.dismiss().events.single()
        assertEquals(EventType.CHECK_IN, ev.type)
        assertEquals("debug", ev.trigger)
        assertEquals("dismissed", ev.detail)
        assertEquals("bad_day", ev.mode)
        assertEquals(CheckInState("2026-10-15", "bad_day"), w.state.checkIn)
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), w.present().types())
        assertEquals(Engine.TRIGGER_CHECK_IN, w.state.active!!.trigger)
    }

    @Test
    fun answerCheckInIgnoresATakeoverThatIsNotTheCheckIn() {
        val w = checkInDue()
        w.trigger("b")
        w.present()
        val before = w.state
        // The plan directive on screen, and the check-in waiting behind it: neither can be answered.
        assertTrue(w.answer(CheckInAnswer.WORSE).events.isEmpty())
        assertTrue(w.answer(CheckInAnswer.WORSE, w.state.pending.single().takeoverId).events.isEmpty())
        assertEquals(before, w.state)
    }

    @Test
    fun timedOutCheckInLogsNone() {
        val w = checkInDue()
        w.present()
        w.now = w.now.plusSeconds(Engine.MAX_TAKEOVER_MINUTES * 60)
        val o = w.refresh()
        assertEquals(listOf(EventType.CHECK_IN), o.types())
        assertEquals(Engine.ANSWER_NONE, o.events.single().answer)
        assertEquals("timed out", o.events.single().detail)
        assertEquals("bad_day", o.events.single().mode)
        assertNull(w.state.active)
        assertNull(w.state.checkIn)
        assertTrue(w.log.none { it.type == EventType.DIRECTIVE_TIMED_OUT })
        assertEquals(DayMode.BAD_DAY, Engine.dayMode(w.state, w.env()))
    }

    @Test
    fun notOkayReplacesAnOpenCheckIn() {
        // Waiting behind a takeover, with a normal "same": replaced, then the bad day starts.
        val w = checkInDue(sameIsNormal)
        w.trigger("kept")
        w.present()
        val id = w.state.pending.single().takeoverId
        val o = w.badDay(true)
        assertEquals(listOf(EventType.CHECK_IN, EventType.BAD_DAY_ON), o.types())
        val ev = o.events[0]
        assertEquals(Engine.ANSWER_NONE, ev.answer)
        assertEquals("normal", ev.mode)
        assertEquals("replaced by \"I'm not okay today\"", ev.detail)
        assertEquals(id, ev.takeoverId)
        assertNull(w.state.checkIn)
        assertEquals("kept", w.state.active!!.directiveId)
        assertTrue("the check-in's takeover is gone", w.state.pending.isEmpty())
        assertEquals(DayModeState("2026-10-15", "bad_day", DayModeState.SOURCE_USER), w.state.dayMode)
        assertEquals(1, w.log.count { it.takeoverId == id && it.type != EventType.DIRECTIVE_FIRED })

        // On screen, with a bad_day "same": replaced, and the day stays a bad day without a second bad_day_on.
        val v = checkInDue()
        v.present()
        val shown = v.state.active!!.takeoverId
        val p = v.badDay(true)
        assertEquals(listOf(EventType.CHECK_IN), p.types())
        assertEquals(shown, p.events.single().takeoverId)
        assertEquals("bad_day", p.events.single().mode)
        assertNull(v.state.active)
        assertNull(v.state.checkIn)
        assertEquals(DayMode.BAD_DAY, Engine.dayMode(v.state, v.env()))

        // Not shown yet: replaced, with no takeover to close.
        val u = checkInDue()
        val q = u.badDay(true)
        assertEquals(listOf(EventType.CHECK_IN), q.types())
        assertNull(q.events.single().takeoverId)
        assertTrue(u.present().events.isEmpty())
    }

    @Test
    fun unansweredCheckInIsLoggedAtTheNextRollover() {
        val w = checkInDue()
        w.now = at(2026, 10, 16, 7, 0)
        val o = w.refresh()
        assertEquals(listOf(EventType.CHECK_IN, EventType.DAY_MODE), o.types())
        val none = o.events[0]
        assertEquals(Engine.ANSWER_NONE, none.answer)
        assertEquals("bad_day", none.mode)
        assertEquals("not answered; the \"same\" answer applied", none.detail)
        assertNull(none.takeoverId)
        // Its "same" answer was a bad day, so Friday asks again.
        assertEquals(CheckInState("2026-10-16", "bad_day"), w.state.checkIn)

        // Still on screen when the day ends: closed with that one event, and the new day's check-in can come.
        val v = checkInDue()
        v.now = at(2026, 10, 16, 3, 0)
        v.present()
        val old = v.state.active!!.takeoverId
        v.now = at(2026, 10, 16, 4, 0)
        val r = v.refresh()
        assertEquals(listOf(EventType.CHECK_IN, EventType.DAY_MODE), r.types())
        assertEquals(old, r.events[0].takeoverId)
        assertNull(v.state.active)
        val next = v.present()
        assertEquals(listOf(EventType.DIRECTIVE_FIRED), next.types())
        assertTrue(next.events.single().takeoverId != old)

        // With a normal "same" the next day is normal: the unanswered one is logged and nothing more.
        val u = checkInDue(sameIsNormal)
        u.now = at(2026, 10, 16, 7, 0)
        val s = u.refresh()
        assertEquals(listOf(EventType.CHECK_IN), s.types())
        assertEquals("normal", s.events.single().mode)
        assertNull(u.state.checkIn)
    }

    @Test
    fun aChainOfHardDaysAsksEachMorning() {
        val w = World(now = at(2026, 10, 14, 22, 0), active = modesSet)
        w.badDay(true)
        // Thursday: worse, so a minimum day.
        w.now = at(2026, 10, 15, 7, 0)
        w.present()
        w.answer(CheckInAnswer.WORSE)
        assertEquals(DayMode.MINIMUM, Engine.dayMode(w.state, w.env()))
        // Friday: asked again, after a minimum day; same, so a bad day.
        w.now = at(2026, 10, 16, 7, 0)
        assertEquals(listOf(EventType.DAY_MODE, EventType.DIRECTIVE_FIRED), w.present().types())
        assertEquals(CheckInState("2026-10-16", "minimum", fired = true, takeoverId = w.state.active!!.takeoverId), w.state.checkIn)
        assertEquals("for 2026-10-16, after a minimum day", w.answer(CheckInAnswer.SAME).events.single().detail)
        assertEquals(DayMode.BAD_DAY, Engine.dayMode(w.state, w.env()))
        // Saturday: after a bad day; better, so a normal day.
        w.now = at(2026, 10, 17, 7, 0)
        w.present()
        w.answer(CheckInAnswer.BETTER)
        assertEquals(DayMode.NORMAL, Engine.dayMode(w.state, w.env()))
        // Sunday: the day before was normal, so there is no check-in and nothing to log.
        w.now = at(2026, 10, 18, 7, 0)
        assertTrue(w.present().events.isEmpty())
        assertNull(w.state.checkIn)
        assertEquals(listOf("worse", "same", "better"), w.log.filter { it.type == EventType.CHECK_IN }.map { it.answer })
        assertEquals(listOf("minimum", "bad_day", "normal"), w.log.filter { it.type == EventType.CHECK_IN }.map { it.mode })
    }

    @Test
    fun clocksGoingBackOverTheDayBoundaryDoNotEndTheNewDay() {
        // day_boundary 01:30 in London: on Sunday 25 October 2026 01:00-02:00 happens twice (BST, then GMT), so
        // between 01:00 and 01:30 GMT "today" reads as Saturday again for half an hour.
        val w = World(now = at(2026, 10, 24, 22, 0), active = modesSet.copy(dayBoundary = LocalTime.of(1, 30)))
        w.badDay(true)
        w.now = Instant.parse("2026-10-25T00:40:00Z") // 01:40 BST: Sunday
        assertEquals(listOf(EventType.DAY_MODE, EventType.DIRECTIVE_FIRED), w.present().types())
        val sunday = CheckInState("2026-10-25", "bad_day", fired = true, takeoverId = w.state.active!!.takeoverId)
        assertEquals(sunday, w.state.checkIn)
        val sundayMode = w.state.dayMode
        assertEquals("2026-10-25", sundayMode?.day)

        w.now = Instant.parse("2026-10-25T01:10:00Z") // 01:10 GMT: Saturday again, briefly
        assertEquals("2026-10-24", w.env().today)
        assertTrue("nothing ends, nothing is logged", w.present().events.isEmpty())
        assertEquals(sunday, w.state.checkIn)
        assertEquals(sundayMode, w.state.dayMode)
        assertEquals(sunday.takeoverId, w.state.active?.takeoverId)

        // Answered inside that half hour: the mode is Sunday's, so Sunday does not ask a second time.
        val ev = w.answer(CheckInAnswer.WORSE).events.single()
        assertEquals("for 2026-10-25, after a bad day", ev.detail)
        assertEquals(DayModeState("2026-10-25", "minimum", DayModeState.SOURCE_CHECK_IN), w.state.dayMode)
        w.now = Instant.parse("2026-10-25T01:40:00Z") // 01:40 GMT: Sunday again
        assertTrue(w.present().events.isEmpty())
        assertNull(w.state.checkIn)
        assertEquals(DayMode.MINIMUM, Engine.dayMode(w.state, w.env()))
        assertEquals(listOf(EventType.SKIPPED_MINIMUM), w.trigger("b").types())
    }

    // ------------------------------------------------------------------ built-ins

    @Test
    fun builtinDirectives() {
        for ((id, tier) in mapOf(Engine.DEMO_1_ID to 1, Engine.DEMO_2_ID to 2, Engine.DEMO_3_ID to 3)) {
            val d = Engine.builtin(id)!!
            assertEquals(id, d.id)
            assertEquals(tier, d.tier)
            assertEquals(tier, d.steps.size)
            assertTrue(d.trigger is Trigger.Unsupported)
            assertTrue(d.steps.all { it.text == it.text.uppercase() && it.minSeconds in 1..10 })
        }
        assertEquals(Engine.DEMO_3.copy(id = Engine.DEMO_ID), Engine.builtin(Engine.DEMO_ID))
        assertEquals(Engine.DEMO, Engine.builtin(Engine.DEMO_ID))

        val c = Engine.builtin(Engine.CHECK_IN_ID)!!
        assertEquals(1, c.tier)
        assertEquals("CHECK-IN", c.title)
        assertEquals(listOf(Step("HOW IS TODAY?", 0)), c.steps)
        assertEquals("YESTERDAY WAS HARD. ANSWER HONESTLY.", c.why)
        assertEquals(QuietPolicy.SILENT, c.quiet)
        assertEquals(RatingPolicy.NEVER, c.rating)
        assertEquals(Trigger.Unsupported(Engine.TRIGGER_CHECK_IN), c.trigger)

        assertNull(Engine.builtin("a"))
        assertNull(Engine.builtin("vigil-unknown"))
        assertEquals(setOf(Engine.DEMO_1_ID, Engine.DEMO_2_ID, Engine.DEMO_3_ID, Engine.DEMO_ID, Engine.CHECK_IN_ID), Engine.BUILTIN_IDS)
        assertTrue(Engine.BUILTIN_IDS.all { it.startsWith(Engine.BUILTIN_ID_PREFIX) })
    }

    @Test
    fun builtinTicketsSurviveRefreshAndQuietMode() {
        val w = World()
        for (id in Engine.BUILTIN_IDS) w.fireNow(id)
        assertEquals(listOf(1, 2, 3, 3, 1), w.log.filter { it.type == EventType.DIRECTIVE_FIRED }.map { it.tier })
        fun tickets() = (listOfNotNull(w.state.active) + w.state.pending).map { it.directiveId }
        assertEquals(Engine.BUILTIN_IDS.toList(), tickets())

        // A plan that names none of them: housekeeping keeps every one.
        w.active = plainSet.copy(directives = listOf(a))
        assertTrue(w.refresh().events.isEmpty())
        assertEquals(Engine.BUILTIN_IDS.toList(), tickets())

        // Quiet mode neither holds nor skips them: they go silent.
        assertEquals(listOf(EventType.QUIET_ON), w.quiet(true).types())
        assertEquals(Engine.BUILTIN_IDS.toList(), tickets())
        assertTrue((listOfNotNull(w.state.active) + w.state.pending).all { it.silent })

        // "I'm not okay today" never bypasses them.
        assertEquals(listOf(EventType.BAD_DAY_ON), w.badDay(true).types())
        assertEquals(Engine.BUILTIN_IDS.toList(), tickets())
    }

    // ------------------------------------------------------------------ ratings

    private val always = directive("always").copy(rating = RatingPolicy.ALWAYS)
    private val sampled = directive("sampled", tier = 1, steps = listOf(Step("Drink.", 0))).copy(rating = RatingPolicy.SAMPLE)
    private val never = directive("never").copy(rating = RatingPolicy.NEVER)
    private val easierRun = Variant("EASIER RUN", listOf(Step("Just start.", 2)), why = null)
    private val adaptive = directive("adaptive").copy(rating = RatingPolicy.ALWAYS, easier = easierRun, stepDownAfterHard = 2)
    private val ratingSet = plainSet.copy(directives = plainSet.directives + listOf(always, sampled, never, adaptive))

    @Test
    fun ratingAlwaysAsksAfterEveryCompletion() {
        val w = World(active = ratingSet)
        val t1 = w.complete("always")
        assertEquals(PendingRating(t1, "always", null, "adhan", w.now.toEpochMilli()), w.state.pendingRating)
        assertEquals(mapOf("always" to 1), w.state.completions)
        w.rate(Rating.OK)
        val t2 = w.complete("always")
        assertEquals(t2, w.state.pendingRating!!.takeoverId)
        assertEquals(mapOf("always" to 2), w.state.completions)
    }

    @Test
    fun ratingSampleAsksOnTheFirstFourthAndSeventhCompletion() {
        val w = World(active = ratingSet)
        val asked = (1..7).map {
            w.complete("sampled", via = "time")
            val open = w.state.pendingRating != null
            if (open) w.rate(Rating.EASY)
            open
        }
        assertEquals(listOf(true, false, false, true, false, false, true), asked)
        assertEquals(7, w.state.completions["sampled"])
    }

    @Test
    fun ratingNeverDoesNotAsk() {
        val w = World(active = ratingSet)
        w.complete("never")
        assertNull(w.state.pendingRating)
        assertEquals(mapOf("never" to 1), w.state.completions)
        w.fireNow("never")
        w.completeActive()
        assertNull(w.state.pendingRating)
    }

    @Test
    fun testFiresAskButDoNotCount() {
        val w = World(active = ratingSet)
        repeat(2) {
            w.fireNow("sampled")
            val id = w.state.active!!.takeoverId
            w.completeActive()
            assertEquals(id, w.state.pendingRating!!.takeoverId)
            assertEquals("debug", w.state.pendingRating!!.trigger)
            w.rate(Rating.HARD)
        }
        assertTrue(w.state.completions.isEmpty())
        assertTrue("test ratings are not remembered", w.state.recentRatings.isEmpty())
        assertTrue(w.log.filter { it.type == EventType.RATED }.all { it.trigger == "debug" })
        // The first real completion is still the first: asked.
        w.complete("sampled")
        assertNotNull(w.state.pendingRating)
        assertEquals(mapOf("sampled" to 1), w.state.completions)
        w.rate(null)
        // The tier previews ask too, so the TEST screen shows the question.
        w.fireNow(Engine.DEMO_1_ID)
        w.completeActive()
        assertEquals(Engine.DEMO_1_ID, w.state.pendingRating!!.directiveId)
        assertEquals(mapOf("sampled" to 1), w.state.completions)
    }

    @Test
    fun rateLogsRatedOrSkipped() {
        val w = World(active = ratingSet)
        val t = w.complete("always")
        w.advance(4)
        val o = w.rate(Rating.EASY, t)
        assertEquals(listOf(EventType.RATED), o.types())
        val ev = o.events.single()
        assertEquals("always", ev.directiveId)
        assertEquals(2, ev.tier)
        assertEquals(t, ev.takeoverId)
        assertEquals("adhan", ev.trigger)
        assertEquals("easy", ev.rating)
        assertNull(ev.variant)
        assertNull(ev.detail)
        assertNull(w.state.pendingRating)
        assertEquals(mapOf("always" to listOf("easy")), w.state.recentRatings)

        val t2 = w.complete("always")
        val skip = w.rate(null, t2)
        assertEquals(listOf(EventType.RATING_SKIPPED), skip.types())
        assertEquals("skipped", skip.events.single().detail)
        assertEquals(t2, skip.events.single().takeoverId)
        assertNull(skip.events.single().rating)
        assertEquals("a skip is not a rating", listOf("easy"), w.state.recentRatings["always"])
        // Answered once: a second answer is ignored.
        assertTrue(w.rate(Rating.HARD, t2).events.isEmpty())
    }

    @Test
    fun rateIgnoresAWrongTakeoverId() {
        val w = World(active = ratingSet)
        val t = w.complete("always")
        val before = w.state
        assertTrue(w.rate(Rating.HARD, "t99").events.isEmpty())
        assertEquals(before, w.state)
        w.rate(Rating.OK, t)
        assertTrue(w.rate(Rating.OK, t).events.isEmpty())
        val fresh = World()
        assertTrue(fresh.rate(Rating.OK, "t1").events.isEmpty())
    }

    @Test
    fun anOpenRatingIsReplacedByTheNextQuestion() {
        val w = World(active = ratingSet)
        val t1 = w.complete("always")
        w.advance(60)
        w.trigger("always")
        val t2 = w.state.active!!.takeoverId
        val o = w.completeActive()
        val skipped = o.events.single { it.type == EventType.RATING_SKIPPED }
        assertEquals(t1, skipped.takeoverId)
        assertEquals("replaced", skipped.detail)
        assertEquals(EventType.RATING_SKIPPED, o.events.last().type)
        assertEquals(t2, w.state.pendingRating!!.takeoverId)
        assertTrue(w.rate(Rating.HARD, t1).events.isEmpty())
    }

    @Test
    fun anOpenRatingExpiresAfterThirtyMinutes() {
        val w = World(active = ratingSet)
        val t = w.complete("always")
        val askedAt = w.now
        w.now = askedAt.plusMillis(Engine.RATING_WINDOW_MS - 1000)
        assertTrue(w.refresh().events.isEmpty())
        assertNotNull(w.state.pendingRating)
        w.now = askedAt.plusMillis(Engine.RATING_WINDOW_MS)
        val o = w.refresh()
        assertEquals(listOf(EventType.RATING_SKIPPED), o.types())
        val ev = o.events.single()
        assertEquals("not answered", ev.detail)
        assertEquals(t, ev.takeoverId)
        assertEquals("always", ev.directiveId)
        assertEquals(2, ev.tier)
        assertEquals("adhan", ev.trigger)
        assertNull(w.state.pendingRating)
        assertTrue(w.rate(Rating.OK, t).events.isEmpty())

        // Replaced by a completion that came after the window: logged as not answered, not as replaced.
        val v = World(active = ratingSet)
        v.complete("always")
        v.trigger("always")
        v.advance(Engine.RATING_WINDOW_MS / 1000)
        val late = v.completeActive().events.single { it.type == EventType.RATING_SKIPPED }
        assertEquals("not answered", late.detail)
    }

    @Test
    fun recentRatingsKeepTheLastTen() {
        val w = World(active = ratingSet)
        repeat(12) { i ->
            w.complete("always")
            w.rate(if (i < 2) Rating.HARD else Rating.EASY)
        }
        assertEquals(List(Engine.MAX_RECENT_RATINGS) { "easy" }, w.state.recentRatings["always"])
    }

    // ------------------------------------------------------------------ step-down

    @Test
    fun stepDownAfterNHardRatingsRunsTheEasierVariant() {
        val w = World(active = ratingSet)
        w.complete("adaptive")
        assertEquals(listOf(EventType.RATED), w.rate(Rating.HARD).types())
        assertTrue(w.state.steppedDown.isEmpty())
        w.complete("adaptive")
        val o = w.rate(Rating.HARD)
        assertEquals(listOf(EventType.RATED, EventType.STEPPED_DOWN), o.types())
        val down = o.events[1]
        assertEquals("adaptive", down.directiveId)
        assertEquals(2, down.tier)
        assertEquals(Variant.EASIER, down.variant)
        assertEquals("the last 2 ratings were HARD: variants.easier runs for the rest of this plan", down.detail)
        assertEquals(listOf("adaptive"), w.state.steppedDown)

        // The next takeover runs the easier steps, and its events say so.
        val fired = w.trigger("adaptive").events.single()
        assertEquals(Variant.EASIER, fired.variant)
        assertEquals(1, fired.stepsTotal)
        val ticket = w.state.active!!
        assertEquals(Variant.EASIER, ticket.variant)
        val running = Engine.directiveFor(w.env(), ticket)!!
        assertEquals("EASIER RUN", running.title)
        assertEquals(easierRun.steps, running.steps)
        assertEquals("the directive's own why", "Because.", running.why)
        val done = w.completeActive()
        assertEquals(listOf(EventType.STEP_COMPLETED, EventType.DIRECTIVE_COMPLETED), done.types())
        assertTrue(done.events.all { it.variant == Variant.EASIER })
        assertEquals("Just start.", done.events[0].stepText)
        assertEquals(PendingRating(ticket.takeoverId, "adaptive", Variant.EASIER, "adhan", w.now.toEpochMilli()), w.state.pendingRating)

        // Ratings of the easier version are logged but never step down again (or back up).
        val rated = w.rate(Rating.HARD)
        assertEquals(listOf(EventType.RATED), rated.types())
        assertEquals(Variant.EASIER, rated.events.single().variant)
        assertEquals(listOf("hard", "hard"), w.state.recentRatings["adaptive"])
        assertEquals(listOf("adaptive"), w.state.steppedDown)
        assertEquals(3, w.state.completions["adaptive"])

        // Every way a takeover of it closes names the variant.
        w.trigger("adaptive")
        assertEquals(Variant.EASIER, w.dismiss().events.single().variant)
        w.trigger("adaptive")
        val bypassed = w.badDay(true).events.single { it.type == EventType.BYPASSED_BAD_DAY }
        assertEquals(Variant.EASIER, bypassed.variant)
        assertEquals(1, bypassed.stepsTotal)
    }

    @Test
    fun noStepDownWithAnOkInBetween() {
        val w = World(active = ratingSet)
        for (r in listOf(Rating.HARD, Rating.OK, Rating.HARD)) {
            w.complete("adaptive")
            w.rate(r)
        }
        assertTrue(w.state.steppedDown.isEmpty())
        assertTrue(w.log.none { it.type == EventType.STEPPED_DOWN })
        // A skip neither counts nor breaks the run.
        w.complete("adaptive")
        w.rate(null)
        assertTrue(w.state.steppedDown.isEmpty())
        w.complete("adaptive")
        assertEquals(listOf(EventType.RATED, EventType.STEPPED_DOWN), w.rate(Rating.HARD).types())
        assertEquals(1, w.log.count { it.type == EventType.STEPPED_DOWN })
    }

    @Test
    fun stepDownAfterASingleHardRating() {
        val once = adaptive.copy(id = "once", stepDownAfterHard = 1)
        val w = World(active = ratingSet.copy(directives = ratingSet.directives + once))
        w.complete("once")
        val o = w.rate(Rating.HARD)
        assertEquals("the last rating was HARD: variants.easier runs for the rest of this plan", o.events.last().detail)
        assertEquals(listOf("once"), w.state.steppedDown)
    }

    @Test
    fun noStepDownWithoutAnEasierVariant() {
        // The parser rejects this, but the engine must not step down to nothing.
        val noEasier = directive("no-easier").copy(rating = RatingPolicy.ALWAYS, stepDownAfterHard = 1)
        val w = World(active = plainSet.copy(directives = plainSet.directives + noEasier))
        repeat(3) {
            w.complete("no-easier")
            w.rate(Rating.HARD)
        }
        assertTrue(w.state.steppedDown.isEmpty())
        assertTrue(w.log.none { it.type == EventType.STEPPED_DOWN })
        assertNull(w.trigger("no-easier").events.single().variant)
    }

    @Test
    fun noStepDownFromTestRatings() {
        val w = World(active = ratingSet)
        repeat(3) {
            w.fireNow("adaptive")
            w.completeActive()
            w.rate(Rating.HARD)
        }
        assertTrue(w.state.steppedDown.isEmpty())
        assertTrue(w.state.recentRatings.isEmpty())
    }

    @Test
    fun aNewPlanResetsThePerPlanMemory() {
        val w = World(active = ratingSet, planKey = "1000")
        repeat(2) {
            w.complete("adaptive")
            w.rate(Rating.HARD)
        }
        w.complete("always")
        assertEquals("1000", w.state.planKey)
        assertEquals(listOf("adaptive"), w.state.steppedDown)
        assertNotNull(w.state.pendingRating)
        // The same plan: nothing changes.
        assertTrue(w.refresh().events.isEmpty())
        assertEquals(listOf("adaptive"), w.state.steppedDown)

        // A new import: everything starts again, without an event.
        w.planKey = "2000"
        assertTrue(w.refresh().events.isEmpty())
        assertEquals("2000", w.state.planKey)
        assertTrue(w.state.completions.isEmpty())
        assertTrue(w.state.recentRatings.isEmpty())
        assertTrue(w.state.steppedDown.isEmpty())
        assertNull(w.state.pendingRating)
        assertNull(w.trigger("adaptive").events.single().variant)

        // No plan any more is a change too.
        w.dismiss()
        w.complete("always")
        w.planKey = null
        w.refresh()
        assertNull(w.state.planKey)
        assertTrue(w.state.completions.isEmpty())
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
            pending = listOf(Ticket("t2", "kept", "app_usage", silent = false, firedAtMs = 6, variant = "easier")),
            scheduleCursorMs = 7,
            lastSource = "fallback_plan",
            dayMode = DayModeState("2026-10-14", "minimum", DayModeState.SOURCE_CHECK_IN_PENDING),
            checkIn = CheckInState("2026-10-14", "bad_day", fired = true, takeoverId = "t0"),
            pendingRating = PendingRating("t9", "a", variant = "easier", trigger = "time", askedAtMs = 8),
            planKey = "1760000000000",
            completions = mapOf("a" to 4, "b" to 1),
            recentRatings = mapOf("a" to listOf("hard", "ok")),
            steppedDown = listOf("a"),
        )
        assertEquals(full, RuntimeState.decode(full.encode()))
        val json = full.encode()
        for (key in listOf("day_mode", "check_in", "pending_rating", "plan_key", "completions", "recent_ratings", "stepped_down", "asked_at_ms")) {
            assertTrue(key, json.contains("\"$key\":"))
        }
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
