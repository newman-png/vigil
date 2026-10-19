package dev.vigil.core.machine

import dev.vigil.core.common.WallClock
import dev.vigil.core.day.DayEndReason
import dev.vigil.core.day.DayRules
import dev.vigil.core.journal.Entry
import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolRules
import dev.vigil.core.pool.PoolSession
import dev.vigil.core.pool.PoolState
import dev.vigil.core.pool.PoolText
import dev.vigil.core.pool.PoolWait
import dev.vigil.core.pool.UseMark
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.SlotType
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.protocol.Trigger
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.CallInfo
import dev.vigil.core.run.GymInfo
import dev.vigil.core.run.GymSet
import dev.vigil.core.run.JummahInfo
import dev.vigil.core.run.JummahPart
import dev.vigil.core.run.PromiseInfo
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunOutcome
import dev.vigil.core.run.RunPhase
import dev.vigil.core.run.SlotInfo
import dev.vigil.core.run.StageMemory
import dev.vigil.core.run.Stages
import dev.vigil.core.timeline.StartSource
import dev.vigil.core.timeline.Timeline
import dev.vigil.core.times.ResolvedSlot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * One reducer step in progress. Mutates only its own copy of the state ([s]) and collects journal entries and
 * effects; [Machine.step] returns them as an [Outcome]. Not shared, not reused.
 */
internal class Txn(state: MachineState, env: MachineEnv) : View(state, env) {
    private val entries = mutableListOf<Entry>()
    private val effects = mutableListOf<Effect>()

    /** The run that owned the screen when the step began. */
    private val ownerAtStart: String? = if (suspended) null else screenOwner()?.id

    fun outcome(): Outcome {
        // Whenever the screen passes to a run, the app is told to show it (once).
        val owner = if (suspended) null else screenOwner()
        if (owner != null && owner.id != ownerAtStart && effects.none { it is Effect.ShowTakeover && it.runId == owner.id }) {
            effects += Effect.ShowTakeover(owner.id)
        }
        // A run that took the screen and ended within this same step (the day ended, the kill switch) shows nothing.
        effects.removeAll { it is Effect.ShowTakeover && run(it.runId) == null }
        return Outcome(s, entries.toList(), effects.toList())
    }

    // ------------------------------------------------------------------ journal helpers

    private fun log(
        type: String,
        at: Instant = now,
        protocol: String? = null,
        run: String? = null,
        step: Int? = null,
        version: String? = null,
        stage: Int? = null,
        app: String? = null,
        minutes: Int? = null,
        seconds: Long? = null,
        reason: String? = null,
        detail: String? = null,
        text: String? = null,
        day: String? = null,
        prayer: Prayer? = null,
        until: Instant? = null,
        scheduled: Instant? = null,
        test: Boolean = false,
        prayerName: String? = null,
        exercise: String? = null,
        reps: Int? = null,
        weight: Double? = null,
    ) {
        entries += Entry(
            ts = WallClock.stamp(at, zone), type = type, protocol = protocol, run = run, step = step, version = version,
            stage = stage, app = app, minutes = minutes, seconds = seconds, reason = reason, detail = detail, text = text,
            day = day, prayer = prayer?.json ?: prayerName, until = until?.let { WallClock.stamp(it, zone) },
            scheduled = scheduled?.let { WallClock.stamp(it, zone) }, test = test.takeIf { it },
            exercise = exercise, reps = reps, weight = weight,
        )
    }

    private fun logRun(
        type: String,
        r: Run,
        at: Instant = now,
        reason: String? = null,
        detail: String? = null,
        step: Int? = null,
        text: String? = null,
        minutes: Int? = null,
        until: Instant? = null,
        scheduled: Instant? = null,
        exercise: String? = null,
        reps: Int? = null,
        weight: Double? = null,
    ) = log(
        type, at = at, protocol = r.protocol, run = r.id, step = step, version = r.version.label, stage = r.stage,
        minutes = minutes, reason = reason, detail = detail, text = text, prayer = r.prayer, until = until, scheduled = scheduled,
        test = r.test, prayerName = if (r.jummah != null) JUMMAH_PRAYER else null, exercise = exercise, reps = reps, weight = weight,
    )

    private fun hhmm(at: Instant) = WallClock.hhmm(at, zone)

    // ------------------------------------------------------------------ state helpers

    private fun put(r: Run) {
        s = s.copy(runs = s.runs.map { if (it.id == r.id) r else it })
    }

    private fun add(r: Run) {
        s = s.copy(runs = s.runs + r)
    }

    /** Takes run [id] out of play; a Jummah lock that silenced the phone gives Do Not Disturb back, whatever ended it. */
    private fun remove(id: String) {
        run(id)?.let { if (it.jummah?.dnd == true) dndOff(it, "the Jummah run ended") }
        s = s.copy(runs = s.runs.filterNot { it.id == id })
    }

    private fun dndOff(r: Run, why: String) {
        effects += Effect.SetDnd(false)
        logRun(EntryType.DND, r, reason = "off", detail = why)
    }

    private fun updateDay(f: (DayState) -> DayState) {
        s = s.copy(day = f(day))
    }

    private fun updatePool(f: (PoolState) -> PoolState) {
        s = s.copy(pool = f(s.pool))
    }

    private fun title(r: Run): String = protocol(r)?.title ?: r.protocol

    // ------------------------------------------------------------------ advance (everything due by now)

    fun advance() {
        ensureDay()
        expireSuspension()
        settleWrapUp()
        expireCall()
        settleToggles()
        settleRuns()
        // The wake alarm before the day boundary too: one due while the old day still ran (a 05:50 wake before the
        // 06:00 fallback, noticed at 06:05) rings for it instead of being lost with that day.
        settleAlarm()
        settleDay()
        settleAlarm()
        prunePool()
        settlePool()
        settleHeadsUps()
        fireDue()
        settleNudges()
        settleRuns()
        if (regateDue) {
            regateDue = false
            regate()
        }
    }

    /** Set when a suspension ends: a pool app already in front (opened while everything was open) is judged again. */
    private var regateDue = false

    /** True while [endDay] closes the day's runs: nothing new starts then ([fire] skips it). */
    private var dayEnding = false

    /** The pool app in front without a session (it came in while VIGIL was suspended) gets the gate now. */
    private fun regate() {
        val app = s.foreground?.takeIf { s.foregroundIsPool } ?: return
        if (suspended || s.pool.session != null || effects.any { it is Effect.PoolGate && it.app == app }) return
        gate(app)
    }

    /** A call that never reported its end stops pausing the runs after [Machine.MAX_CALL_PAUSE]. */
    private fun expireCall() {
        val since = s.callSince ?: return
        val limit = since.plus(Machine.MAX_CALL_PAUSE)
        if (now.isBefore(limit)) return
        endCall(limit, "no call end reported within ${Machine.MAX_CALL_PAUSE.toHours()} h: timers and locks run again")
    }

    /**
     * "I'm with friends" turns itself off at its cap (spec: "Ends when you switch it off, or after the 4-hour cap");
     * "I'm walking" when the steps stop for `still_end_min` ("or when you stop moving for 10 minutes"), or without a step
     * counter by the time rules ([View.walkEnd]).
     */
    private fun settleToggles() {
        friendsEnd()?.let { end ->
            if (!now.isBefore(end)) setToggle(ToggleName.FRIENDS, false, at = end, detail = "off (the ${friendsCapMin()}-minute cap)", why = "cap")
        }
        val since = s.toggles.walkingSince
        walkEnd()?.let { end ->
            if (now.isBefore(end) || since == null) return@let
            if (s.steps.available) {
                setToggle(ToggleName.WALKING, false, at = end, detail = "off (no steps for ${walkParam("still_end_min")} minutes)", why = "still")
            } else {
                setToggle(ToggleName.WALKING, false, at = end, detail = "off (by itself after ${span(since, end)})", why = "time")
            }
        }
    }

    /** "3 hours", "1 hour" or "75 minutes". */
    private fun span(from: Instant, to: Instant): String {
        val m = Duration.between(from, to).toMinutes()
        return when {
            m == 60L -> "1 hour"
            m > 0 && m % 60 == 0L -> "${m / 60} hours"
            else -> "$m minutes"
        }
    }

    private fun ensureDay() {
        if (s.day != null) return
        val key = DayRules.firstKey(now, zone, file.day).toString()
        s = s.copy(day = DayState(key, now, Machine.FIRST_START), times = s.times.atDayStart())
        log(EntryType.DAY_START, day = key, reason = Machine.FIRST_START)
        sleepTarget()?.let { setTarget(it, 0, "start", 0) }
    }

    private fun expireSuspension() {
        val until = s.suspendedUntil ?: return
        if (now.isBefore(until)) return
        s = s.copy(suspendedUntil = null)
        log(EntryType.KILL_SWITCH, at = until, reason = "resumed", detail = "the suspension ended")
        regateDue = true
    }

    private fun settleDay() {
        repeat(Machine.MAX_CATCH_UP_DAYS) {
            val end = DayRules.dueEnd(day.date, now, zone, file.day, s.screenOffSince.takeIf { !s.screenOn }, windDownAt()) ?: return
            endDay(end.reason, end.at)
        }
    }

    /**
     * Ends the current day at [at]: self-check, the night's verdict for sleep shifting, runs that close with the day,
     * counters reset, the next day starts (with its target bedtime), and the night lock begins after GOING TO SLEEP (or
     * an idle or fallback end once wind-down had started).
     */
    fun endDay(reason: DayEndReason, at: Instant) {
        val d = day
        val sleep = sleepProtocol
        val windDownStarted = sleep != null && startedToday(sleep.id) != null
        selfCheck(d, at)
        val goodNight = judgeNight(d, reason, at)
        // Closing the day's runs starts nothing: no `event:misses` reset offer and no `event:after` protocol at GOING TO
        // SLEEP (or in the night the day ended in). Those triggers are skipped; the miss count starts again.
        dayEnding = true
        // The switches end with the day (their runs complete; nothing waiting for them starts now).
        if (s.toggles.walking) setToggle(ToggleName.WALKING, false, at = at, detail = "off (the day ended)", why = "day_end")
        if (s.toggles.friends) setToggle(ToggleName.FRIENDS, false, at = at, detail = "off (the day ended)", why = "day_end")
        closeRunsAtDayEnd(at)
        dayEnding = false
        for (w in day.deferred) {
            log(EntryType.SKIPPED, at = at, protocol = w.protocol, reason = "day_end", detail = "waited (${w.reason}) until the day ended")
        }
        clearOffer("expired", "the day ended", at)
        endSessionAtDayEnd(at)
        log(
            EntryType.DAY_END, at = at, day = d.key, reason = reason.json,
            detail = if (at != now) "noticed at ${hhmm(now)}" else null,
        )
        val key = DayRules.keyAfter(at, zone, d.date).toString()
        val applied = s.times.pending != null
        s = s.copy(
            day = DayState(key, at, reason.json), times = s.times.atDayStart(), pool = s.pool.newDay(),
            // Unticked to-dos expire with their day; the ones written tonight for the new day stay.
            todos = s.todos.filterKeys { it > d.key },
        )
        log(
            EntryType.DAY_START, at = at, day = key, reason = reason.json,
            detail = if (applied) "the MY TIMES changes apply from today" else null,
        )
        if (sleep != null) shiftTarget(goodNight)
        expireAlarm(key)
        val night = when (reason) {
            DayEndReason.SLEEP_TAP -> true
            DayEndReason.IDLE, DayEndReason.FALLBACK -> windDownStarted
            DayEndReason.WAKE_ALARM -> false
        }
        if (night) startNight(reason, at)
    }

    /** Daily self-check: every planned start of the day that never fired, and every expected adhan that never came. */
    private fun selfCheck(d: DayState, at: Instant) {
        for (item in plan()) {
            if (!item.at.isBefore(at)) continue
            if (item.source == StartSource.ADHAN) {
                val prayer = item.prayer ?: continue
                if (prayer !in d.adhans) {
                    log(
                        EntryType.NOT_FIRED, at = at, protocol = item.protocolId, prayer = prayer, scheduled = item.at,
                        reason = "no_adhan", detail = "no adhan notification arrived (expected about ${hhmm(item.at)})",
                    )
                }
            } else if (item.key !in d.handled) {
                log(
                    EntryType.NOT_FIRED, at = at, protocol = item.protocolId, scheduled = item.at, reason = item.source.json,
                    detail = "planned for ${hhmm(item.at)} but never started",
                )
            }
        }
        // The next wake alarm, due while the day ran, that never rang (the phone was off, VIGIL was stopped): this
        // day's own, or tomorrow's when it falls before this day's end (a 05:30 wake before the 06:00 fallback).
        if (alarmOn) {
            val key = alarmKey().toString()
            val due = alarmAt(alarmKey())
            val a = s.wakeAlarm
            if (due.isBefore(at) && a.ringingFor != key && a.handledFor != key) {
                s = s.copy(wakeAlarm = a.copy(handledFor = key))
                log(
                    EntryType.NOT_FIRED, at = at, protocol = morningProtocol?.id, scheduled = due, reason = "wake_alarm",
                    detail = "the wake alarm (${hhmm(due)}) never rang",
                )
            }
        }
    }

    /**
     * A pool session still alive when the day ends belongs to that day: it ends now (its minutes count for the day that
     * ends and its cooldown starts), so it never eats into the new day's window caps. Still in the app: it closes.
     */
    private fun endSessionAtDayEnd(at: Instant) {
        val session = s.pool.session ?: return
        val inApp = session.inSince != null
        endSession(if (inApp) at else session.leftAt ?: at, cooldown = true)
        if (inApp) {
            log(EntryType.POOL_BLOCKED, at = at, app = session.app, reason = BlockReason.OUTSIDE_WINDOW.json, detail = "session ended: the day ended")
            effects += Effect.Kick(session.app, BlockReason.OUTSIDE_WINDOW)
        }
    }

    private fun closeRunsAtDayEnd(at: Instant) {
        for (id in s.runs.map { it.id }) {
            // Fresh each time: closing one run can start a queued one.
            val r = run(id) ?: continue
            when {
                r.test -> {
                    remove(r.id)
                    logRun(EntryType.DROPPED, r, at = at, reason = "day_end", detail = "test run")
                    cancelRunNotices(r)
                }
                r.phase == RunPhase.NIGHT -> endNight(r, "day_end", at)
                // A promise never gets stuck: it ends with its day, without a penalty or a miss.
                r.promise != null -> {
                    remove(r.id)
                    logRun(EntryType.PROMISE, r, at = at, reason = "expired_at_day_end", text = r.promise.text)
                    logRun(EntryType.DROPPED, r, at = at, reason = "day_end", detail = "the promise expired with the day")
                    cancelRunNotices(r)
                }
                r.kind == ProtocolKind.NUDGE -> endNudge(r, "day_end", at)
                r.kind in SWITCHES -> complete(r, at)
                r.call != null -> missCall(r, "day_end", at)
                r.prayer == Prayer.ISHA -> if (r.confirmed) complete(r) else missPrayer(r, "day_end", at)
                r.prayer != null -> Unit
                // An unconfirmed stage-3 reminder is a miss like any other: without it a protocol at stage 3 could
                // never move back a stage, however often it was ignored.
                r.phase == RunPhase.REMINDER -> missRun(r, "day_end", at, detail = "reminder not confirmed")
                else -> missRun(r, "day_end", at)
            }
        }
    }

    // ------------------------------------------------------------------ runs

    private fun settleRuns() {
        for (id in s.runs.map { it.id }) settleRun(id)
        promoteQueue()
        settleDeferred()
    }

    /**
     * Attention resets waiting to start: from their due time, once no class is in progress and nothing holds the screen;
     * one that cannot start within [Machine.DEFER_LIMIT] is skipped (not a miss).
     */
    private fun settleDeferred() {
        for (w in day.deferred) {
            if (now.isBefore(w.due)) continue
            val limit = w.due.plus(Machine.DEFER_LIMIT)
            val late = !now.isBefore(limit)
            val waiting = dayTimes().classAt(now) != null || screenHolder() != null
            if (waiting && !late) continue
            updateDay { d -> d.copy(deferred = d.deferred.filterNot { it.protocol == w.protocol }) }
            val p = file.find(w.protocol) ?: continue
            if (late) {
                log(
                    EntryType.SKIPPED, at = limit, protocol = p.id, reason = "too_late",
                    detail = "could not start by ${hhmm(limit)} (a class or another protocol held the screen)",
                )
            } else {
                fire(p, w.reason, deferOk = false)
            }
        }
    }

    private fun settleRun(id: String) {
        var r = run(id) ?: return
        val p = protocol(r)
        if (p == null) {
            remove(id)
            logRun(EntryType.DROPPED, r, reason = "protocol_removed", detail = "the protocol is no longer in protocols.json")
            cancelRunNotices(r)
            return
        }
        if (r.jummah != null) {
            settleJummah(id)
            return
        }
        when {
            r.kind == ProtocolKind.NUDGE -> {
                settleNudge(r)
                return
            }
            r.kind in SWITCHES -> {
                settleSwitch(r)
                return
            }
            r.call != null -> {
                settleCall(r)
                return
            }
        }
        r.promise?.let { promise ->
            if (r.phase == RunPhase.WAITING && !now.isBefore(promise.dueAt)) promiseDue(r)
            return
        }
        if (r.prayer != null && !r.confirmed) {
            val end = windowEnd(r)
            if (end != null && !now.isBefore(end)) {
                missPrayer(r, "window_closed", end)
                return
            }
            val warnAt = warnAt(r, p)
            if (warnAt != null && !r.warned && !now.isBefore(warnAt) && r.phase in WARNABLE) {
                warn(r, p)
                r = run(id) ?: return
            }
        }
        when (r.phase) {
            RunPhase.CARD -> r.classEndsAt?.let { if (!now.isBefore(it)) classOver(r) }
            RunPhase.ACTIVE -> if (s.callSince == null) settleActive(r, p)
            // Never answered: the night lock ends by itself at wake + night_lock_grace_min.
            RunPhase.NIGHT -> r.lockEndsAt?.let { if (!now.isBefore(it)) endNight(r, "timeout", it) }
            else -> Unit
        }
    }

    /** Timer steps that ran out, a workout nobody finished, completion, and the lock's end. */
    private fun settleActive(start: Run, p: Protocol) {
        var r = runTimers(start) ?: return
        val st = stepsOf(r).getOrNull(r.step)
        val workoutEnd = st?.takeIf { it.type == StepType.WORKOUT }?.let { workoutEnd(r, it) }
        if (workoutEnd != null && !now.isBefore(workoutEnd)) r = finishWorkout(r, auto = true, at = workoutEnd) ?: return
        val lockEnd = r.lockEndsAt
        if (lockEnd != null && !now.isBefore(lockEnd)) lockEnded(r, p, lockEnd)
    }

    /** Timer steps that ran out, and completion once the steps are over. Returns the run, or null when that finished it. */
    private fun runTimers(start: Run): Run? {
        var r = start
        var guard = 0
        while (guard++ < 200) {
            val steps = stepsOf(r)
            if (r.step >= steps.size) {
                complete(r)
                return null
            }
            val st = steps[r.step]
            if (st.type != StepType.TIMER) break
            val end = timerEnd(r, st) ?: break
            if (now.isBefore(end)) break
            r = stepDone(r, steps, auto = true, at = end) ?: return null
        }
        return r
    }

    /**
     * Marks the run's current step done. A following timer that runs "until the lock ends" is over at once when the
     * lock has already ended. Returns the updated run, or null when that finished it.
     */
    private fun stepDone(r: Run, steps: List<Step>, text: String? = null, auto: Boolean = false, at: Instant = now): Run? {
        val st = steps[r.step]
        logRun(EntryType.STEP_DONE, r, at = at, step = r.step, detail = st.text, text = text, reason = if (auto) "timer" else null)
        if (st.todos && text != null && r.kind == ProtocolKind.SLEEP && !r.test) setTodos(r, st, text)
        if (st.walkingOn && !r.test) setToggle(ToggleName.WALKING, true, detail = "on (${title(r)}: ${st.text})", why = "jummah")
        if (st.reenter && !r.test) reenter(r)
        var next = r.copy(
            step = r.step + 1,
            stepStartedAt = at,
            texts = if (text != null) r.texts + (r.step.toString() to text) else r.texts,
        )
        if (st.type == StepType.DONE && !r.confirmed) {
            next = next.copy(confirmed = true)
            if (r.prayer != null) logConfirmed(next)
        }
        // A step that ends the lock (a meal's cleanup): the pool opens, the steps after it go on without a lock.
        if (st.unlock && next.lock != LockType.NONE && next.step < steps.size) {
            next = next.copy(lock = LockType.NONE, lockEndsAt = null)
            logRun(EntryType.LOCK_ENDED, next, at = at, reason = "unlock", detail = "the lock ended at \"${st.text}\"; the rest goes on")
        }
        while (next.step < steps.size) {
            val following = steps[next.step]
            val lockOver = next.lockEndsAt == null || !runNow.isBefore(next.lockEndsAt)
            if (following.type != StepType.TIMER || following.min != null || !lockOver) break
            logRun(EntryType.STEP_DONE, next, at = at, step = next.step, detail = following.text, reason = "timer")
            next = next.copy(step = next.step + 1)
        }
        if (next.step >= steps.size) {
            complete(next, at)
            return null
        }
        put(next)
        // Without a screen lock its notification shows the current step (the morning card).
        if (next.call != null) notifyCall(next) else if (next.lock != LockType.SCREEN && next.phase == RunPhase.ACTIVE) notifyRun(next)
        return next
    }

    private fun notifyRun(r: Run) {
        val p = protocol(r) ?: return
        effects += Effect.Notify(runNotice(r), runTitle(r, p), currentStepText(r), silent = true, NotifyKind.RUN, r.id)
    }

    /** A run notice's title: the protocol's, with POOL LOCKED while its pool lock holds (until a meal's cleanup). */
    private fun runTitle(r: Run, p: Protocol): String = if (r.lock == LockType.POOL && lockActive(r)) "${p.title} · POOL LOCKED" else p.title

    private fun logConfirmed(r: Run) {
        val prayer = r.prayer ?: return
        logRun(EntryType.CONFIRMED, r)
        if (!r.test && prayer !in day.confirmed) updateDay { it.copy(confirmed = it.confirmed + prayer) }
    }

    private fun lockEnded(r: Run, p: Protocol, at: Instant) {
        put(r.copy(phase = RunPhase.OPEN))
        logRun(
            EntryType.LOCK_ENDED, r, at = at,
            detail = if (r.prayer != null) "not confirmed: CONFIRM on home" else "not finished: finish it on home",
        )
        if (r.prayer != null) {
            notifyConfirm(r, p)
        } else {
            effects += Effect.Notify(runNotice(r), "${p.title}: FINISH ON VIGIL", currentStepText(r), silent = true, NotifyKind.RUN, r.id)
        }
        fireAfter(r)
    }

    private fun currentStepText(r: Run): String = stepsOf(r).getOrNull(r.step)?.text ?: ""

    /**
     * The run finished at [at] (a timer that ran out while the phone dozed finished it when it ran out): journal, stage
     * outcome, `after:` triggers, the queue moves on.
     */
    private fun complete(r: Run, at: Instant = now) {
        remove(r.id)
        logRun(EntryType.COMPLETED, r, at = at, minutes = WallClock.minutesCeil(r.firedAt, at), reason = if (r.clean) "clean" else "done")
        cancelRunNotices(r)
        if (r.kind == ProtocolKind.STUDY) {
            logRun(
                EntryType.STUDY, r, at = at, minutes = workMinutes(r), detail = r.label?.ifBlank { null },
                text = r.texts.values.filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { null },
            )
        }
        r.call?.let { c ->
            logRun(EntryType.CALL, r, at = at, reason = "done", minutes = c.callS.takeIf { it > 0 }?.let { WallClock.minutesCeil(it) }, detail = who(r))
        }
        if (!r.test) {
            updateDay { it.copy(completed = it.completed + (r.protocol to at)) }
            // The attention ramp counts the days it was done in full, once a day.
            if (r.kind == ProtocolKind.ATTENTION && r.version == Version.FULL && s.attention.lastDay != day.key) {
                s = s.copy(attention = AttentionMemory(s.attention.days + 1, day.key))
            }
            if (!r.bailed && r.kind !in NEUTRAL) recordOutcome(r, if (r.clean) RunOutcome.CLEAN else RunOutcome.DONE)
            if (!r.afterFired && !r.bailed) fireAfter(r)
        }
        promoteQueue()
    }

    private fun missRun(r: Run, why: String, at: Instant = now, detail: String? = null) {
        remove(r.id)
        logRun(EntryType.MISSED, r, at = at, reason = why, detail = detail)
        cancelRunNotices(r)
        if (!r.test && !r.bailed && r.kind !in NEUTRAL) recordOutcome(r, RunOutcome.MISSED)
        promoteQueue()
    }

    private fun missPrayer(r: Run, why: String, at: Instant) {
        val prayer = r.prayer ?: return
        remove(r.id)
        val detail = when (why) {
            "window_closed" -> "the window closed unconfirmed"
            "next_adhan" -> "the next adhan came unconfirmed"
            "day_end" -> "the day ended unconfirmed"
            else -> null
        }
        logRun(EntryType.MISSED, r, at = at, reason = why, detail = detail)
        cancelRunNotices(r)
        if (!r.test) {
            if (prayer !in day.missed) updateDay { it.copy(missed = it.missed + prayer) }
            if (!r.bailed) recordOutcome(r, RunOutcome.MISSED)
        }
        promoteQueue()
    }

    private fun cancelRunNotices(r: Run) {
        effects += Effect.CancelNotify(runNotice(r))
        r.prayer?.let { effects += Effect.CancelNotify(prayerNotice(it)) }
        if (r.jummah != null && !r.test) effects += Effect.CancelNotify(JUMMAH_CARD_NOTICE)
    }

    /** Stage memory, stage changes, and the misses-in-a-row trigger. */
    private fun recordOutcome(r: Run, outcome: RunOutcome) {
        val p = protocol(r) ?: return
        val (mem, change) = Stages.record(s.stages[p.id] ?: StageMemory(), outcome, file.stages, pinned = pinned(p))
        s = s.copy(stages = s.stages + (p.id to mem))
        if (change > 0) {
            log(EntryType.STAGE_UP, protocol = p.id, stage = mem.stage, detail = "${file.stages.promoteAfterClean} clean completions in a row")
        } else if (change < 0) {
            log(
                EntryType.STAGE_DOWN, protocol = p.id, stage = mem.stage,
                detail = "${file.stages.demoteAfterMisses} misses or bail-outs in the last ${file.stages.window}",
            )
        }
        if (outcome.failure) {
            val n = s.missesInARow + 1
            s = s.copy(missesInARow = n)
            // The reset is offered, not forced; NOT NOW costs nothing, and the next offer needs n more misses.
            val offers = file.protocols.filter { (it.parsedTrigger as? Trigger.Misses)?.count == n }
            if (offers.isNotEmpty()) {
                s = s.copy(missesInARow = 0)
                offers.forEach { offer(it, "misses:$n") }
            }
        } else {
            s = s.copy(missesInARow = 0)
        }
    }

    /**
     * Starts the protocols that wait for [r] (`event:after:<id>`), once per run. On Fridays Jummah replaces Dhuhr: what
     * waits for the Dhuhr protocol follows the Jummah run.
     */
    private fun fireAfter(r: Run) {
        run(r.id)?.let { put(it.copy(afterFired = true)) }
        if (r.test) return
        val ids = setOfNotNull(r.protocol, if (r.jummah != null) file.prayerProtocol(Prayer.DHUHR)?.id else null)
        file.protocols.filter { (it.parsedTrigger as? Trigger.After)?.protocolId in ids }.forEach { fire(it, "after:${r.protocol}") }
    }

    private fun newRun(p: Protocol, stage: Int, reason: String, planned: Instant?, slot: SlotInfo?, test: Boolean): Run {
        val id = "run-${s.nextRun}"
        s = s.copy(nextRun = s.nextRun + 1)
        val dt = dayTimes()
        return Run(
            id = id, protocol = p.id, kind = p.kind, stage = stage, phase = RunPhase.QUEUED, firedAt = now, reason = reason,
            weekday = WallClock.dayCode(day.date), lock = if (stage >= 3) LockType.NONE else p.lock.type,
            planned = planned, slot = slot, test = test,
            classDay = dt.classes.isNotEmpty() || dt.slots(SlotType.COMMUTE).isNotEmpty(),
        )
    }

    /**
     * A trigger fired for [p]: start a run (or say why not). Returns the run, or null when nothing started. [minutes] and
     * [label]: a manual study block's length and what to study. An attention reset started by its trigger waits first
     * ([deferOk]: not yet waited).
     */
    private fun fire(
        p: Protocol,
        reason: String,
        planned: Instant? = null,
        slot: SlotInfo? = null,
        test: Boolean = false,
        minutes: Int? = null,
        label: String? = null,
        deferOk: Boolean = true,
    ): Run? {
        if (dayEnding) {
            log(EntryType.SKIPPED, protocol = p.id, reason = "day_end", scheduled = planned, detail = "triggered ($reason) while the day ended")
            return null
        }
        if (suspended && !test) {
            log(EntryType.SKIPPED, protocol = p.id, reason = "suspended", scheduled = planned, detail = "VIGIL is suspended")
            return null
        }
        if (p.kind == ProtocolKind.ATTENTION && !test && deferOk && reason !in STARTS_AT_ONCE) {
            defer(p, reason)
            return null
        }
        if (p.kind == ProtocolKind.PROMISE) {
            log(EntryType.SKIPPED, protocol = p.id, reason = "needs_text", detail = "a promise starts from LATER: one line and its minutes")
            return null
        }
        // Each MY TIMES calls row is a run of its own (mom and dad): only the same row is "already running".
        val call = p.kind == ProtocolKind.CALL
        if (!test && !call) {
            // A run left unfinished after its lock (or an unconfirmed reminder) does not hold the protocol: its next
            // start (lunch after breakfast, the second study block, tonight's wind-down) replaces it, as a miss.
            val replaceable = s.runs.filter { it.protocol == p.id && !it.test && it.prayer == null && it.jummah == null && it.phase in REPLACEABLE }
            for (old in replaceable) missRun(old, "replaced", detail = "the next start of this protocol came before it was finished")
        }
        // A walk or friends run lasts while its switch is on: any start of it (home, a commute row, the reset's re-entry)
        // turns the switch on, which starts the run.
        val switch = switchOf(p.kind)?.takeIf { p.parsedTrigger == Trigger.Toggle(it) }
        if (switch != null && !test && !switchOn(switch)) {
            setToggle(switch, true, detail = if (reason == "commute") "on (the ${planned?.let { hhmm(it) } ?: ""} commute)" else "on ($reason)",
                why = if (reason == "commute") "commute" else "switch", slot = slot)
            return s.runs.firstOrNull { it.protocol == p.id && !it.test }
        }
        val sameRow = { r: Run -> !call || (r.slot?.start == slot?.start && r.slot?.label == slot?.label) }
        if (!test && s.runs.any { it.protocol == p.id && !it.test && it.phase != RunPhase.NIGHT && sameRow(it) }) {
            log(EntryType.SKIPPED, protocol = p.id, reason = "already_running", scheduled = planned)
            return null
        }
        if (p.kind == ProtocolKind.NUDGE) return nudgeNow(p, reason, test)
        // Started by any way (home, the offer's START): the offer is answered.
        if (!test && s.offer?.protocol == p.id) clearOffer("started", "started ($reason)")
        if (p.kind == ProtocolKind.JUMMAH) return startJummah(p, reason, planned, test, if (test) JummahPart.LEAVE else null)
        val r = newRun(p, if (test) 1 else stageOf(p), reason, planned, slot, test).copy(
            lengthMin = lengthFor(p, slot, minutes),
            label = label?.trim()?.take(MAX_LABEL_CHARS)?.ifEmpty { null } ?: slot?.label?.ifBlank { null }
                ?: if (call && test) "TEST" else null,
            gym = if (p.kind == ProtocolKind.GYM) GymInfo() else null,
            call = if (call) CallInfo(dueAt = slot?.end ?: now.plus(Machine.CALL_WITHOUT_ROW), wrapUpMin = slot?.minutes ?: p.intParam("wrap_up_min")) else null,
        )
        place(r, p)
        return r
    }

    /** An attention reset was triggered: it waits offset_min, then for a class to end and the screen to be free. */
    private fun defer(p: Protocol, reason: String) {
        if (day.deferred.any { it.protocol == p.id }) {
            log(EntryType.SKIPPED, protocol = p.id, reason = "already_running", detail = "triggered ($reason) while it already waits")
            return
        }
        val due = now.plus(Duration.ofMinutes(p.intParam("offset_min").toLong()))
        updateDay { it.copy(deferred = it.deferred + Deferred(p.id, reason, now, due)) }
        log(
            EntryType.DEFERRED, protocol = p.id, reason = reason, until = due,
            detail = "starts at ${hhmm(due)}, after a class or another protocol on the screen; skipped if not started by " +
                hhmm(due.plus(Machine.DEFER_LIMIT)),
        )
    }

    /**
     * Puts a new run into play: stage 3 = one notification; a screen run waits while another owns the screen, except a
     * prayer: a protocol holding the screen pauses for it, and the night lock yields to it.
     */
    private fun place(r: Run, p: Protocol) {
        if (!r.test) s = s.copy(lastFired = s.lastFired + (p.id to now))
        // A nudge gives way to any protocol that needs the screen.
        if (r.lock == LockType.SCREEN && r.stage < 3) screenHolder()?.takeIf { it.kind == ProtocolKind.NUDGE }?.let { endNudge(it, "gave_way") }
        // Thursday's wind-down puts the Jummah prep onto Friday's to-dos.
        if (p.kind == ProtocolKind.SLEEP && !r.test) addPrepTodos()
        // An asking takeover (I'M AT UNI, a promise) gives way to any screen lock.
        val owner = screenHolder()
        val prayerFirst = r.prayer != null && !r.test && owner != null && !owner.prayerLike
        when {
            r.stage >= 3 -> {
                add(r.copy(phase = RunPhase.REMINDER))
                // The reminder is this run's start (wind-down for a sleep reminder, `start:` anchors).
                if (!r.test) recordStart(p.id)
                logRun(EntryType.FIRED, r, reason = r.reason, scheduled = r.planned, detail = "stage 3: one notification")
                val text = if (r.prayer != null) "Time to pray. Tap CONFIRM when done." else currentStepText(r)
                val id = r.prayer?.let { prayerNotice(it) } ?: runNotice(r)
                val title = if (r.call != null) "${p.title} ${who(r)}" else p.title
                effects += Effect.Notify(id, title, text, silent = s.toggles.quiet || r.silent, NotifyKind.REMINDER, r.id)
            }
            r.lock == LockType.SCREEN && owner != null && !prayerFirst -> {
                add(r.copy(phase = RunPhase.QUEUED))
                logRun(EntryType.FIRED, r, reason = r.reason, scheduled = r.planned, detail = "waits behind ${title(owner)}", minutes = r.lengthMin)
            }
            else -> {
                val taken = if (r.lock == LockType.SCREEN && prayerFirst && owner != null) {
                    yieldScreenToPrayer()
                    if (owner.phase == RunPhase.NIGHT) "takes the screen from the night lock" else "${title(owner)} pauses for it"
                } else {
                    null
                }
                add(r)
                logRun(EntryType.FIRED, r, reason = r.reason, scheduled = r.planned, detail = taken, minutes = r.lengthMin)
                activate(r.id)
            }
        }
    }

    /**
     * A prayer (or the Jummah lock) takes the screen: a non-prayer protocol holding it pauses, its timers frozen like in
     * a call. The Jummah lock itself is never paused.
     */
    private fun yieldScreenToPrayer() {
        val owner = s.runs.firstOrNull { it.lock == LockType.SCREEN && lockActive(it) && !it.prayerLike } ?: return
        // A nudge is not paused: it simply ends.
        if (owner.kind == ProtocolKind.NUDGE) {
            endNudge(owner, "gave_way")
            return
        }
        put(owner.copy(phase = RunPhase.PAUSED, pausedAt = runNow))
        logRun(EntryType.PAUSED, owner, detail = "a prayer took the screen; it goes on after the prayer's lock")
    }

    /** A paused run goes on where it was: its lock, done lock and current step move on by the pause. */
    private fun resume(id: String) {
        val r = run(id) ?: return
        val shift = Duration.between(r.pausedAt ?: runNow, runNow).takeIf { !it.isNegative } ?: Duration.ZERO
        val back = r.copy(
            phase = RunPhase.ACTIVE, pausedAt = null, lockEndsAt = r.lockEndsAt?.plus(shift),
            doneAfterAt = r.doneAfterAt?.plus(shift), stepStartedAt = r.stepStartedAt?.plus(shift),
        )
        put(back)
        logRun(EntryType.RESUMED, back, minutes = WallClock.minutesCeil(shift.seconds))
        if (back.lock == LockType.SCREEN) effects += Effect.ShowTakeover(back.id)
    }

    /** The run becomes active now: its lock, done lock and first step start. */
    private fun activate(id: String) {
        val r = run(id) ?: return
        val p = protocol(r) ?: return
        if (r.jummah != null) {
            activateJummah(id)
            return
        }
        if (r.call != null) {
            activateCall(id)
            return
        }
        val lockEnd = p.lock.minutes?.takeIf { r.lock != LockType.NONE }?.let { now.plus(Duration.ofMinutes(it.toLong())) }
        val doneAfter = if (r.stage == 1) p.lock.doneAfterMin?.let { now.plus(Duration.ofMinutes(it.toLong())) } else null
        var a = r.copy(
            phase = RunPhase.ACTIVE, startedAt = r.startedAt ?: now, lockStartedAt = now, lockEndsAt = lockEnd,
            doneAfterAt = doneAfter, stepStartedAt = now,
        )
        val minimum = p.lock.doneAfterMin
        if (a.shortened && minimum != null && lockEnd != null) a = a.copy(lockEndsAt = minOf(lockEnd, now.plus(Duration.ofMinutes(minimum.toLong()))))
        put(a)
        if (!r.test) recordStart(p.id)
        // The attention reset: no sound while you look at one spot.
        if (p.kind == ProtocolKind.ATTENTION) stopAudio("attention")
        if (a.kind in SWITCHES) {
            notifySwitch(a)
        } else if (a.lock == LockType.SCREEN) {
            effects += Effect.ShowTakeover(a.id)
        } else {
            effects += Effect.Notify(runNotice(a), runTitle(a, p), currentStepText(a), silent = true, NotifyKind.RUN, a.id)
        }
    }

    /**
     * Records protocol [id]'s start today (`start:` anchors, wind-down): its first start, but a start in the night
     * before the day's wake time gives way to the first one after it.
     */
    private fun recordStart(id: String) {
        val prev = day.started[id]
        val wake = dayTimes().wake
        if (prev == null || (!prev.isAfter(wake) && now.isAfter(wake))) updateDay { it.copy(started = it.started + (id to now)) }
    }

    /**
     * While nothing holds the screen (an asking takeover gives way), the next queued prayer (or Jummah lock) starts, else
     * a paused run goes on, else the next queued run.
     */
    private fun promoteQueue() {
        if (suspended) return
        var guard = 0
        while (screenHolder() == null && guard++ < 50) {
            val queued = s.runs.filter { it.phase == RunPhase.QUEUED }
            val paused = s.runs.firstOrNull { it.phase == RunPhase.PAUSED }
            val next = queued.firstOrNull { it.prayerLike } ?: paused ?: queued.firstOrNull() ?: return
            if (next.phase == RunPhase.PAUSED) resume(next.id) else activate(next.id)
        }
    }

    // ------------------------------------------------------------------ prayers

    private fun onAdhan(prayer: Prayer, manual: Boolean) {
        val last = s.prayers.lastAdhan[prayer]
        if (last != null && Duration.between(last, now) < Machine.ADHAN_DEDUPE) return
        // A later repeat of the same prayer (the adhan app re-posting, a manual start) while its run is open: the same
        // prayer, not "the next adhan", so it neither misses that run nor starts a second one.
        if (s.runs.any { it.prayer == prayer && !it.test && Duration.between(it.firedAt, now) < Machine.SAME_PRAYER }) return
        s = s.copy(prayers = s.prayers.copy(lastAdhan = s.prayers.lastAdhan + (prayer to now)))
        updateDay { it.copy(adhans = it.adhans + (prayer to now)) }
        log(EntryType.ADHAN, prayer = prayer, reason = if (manual) "manual" else null)
        effects += Effect.CancelNotify(headsUpNotice(prayer))
        // The earlier prayers' windows end with this adhan (Fajr ends last night's), and so does a day-old run of this
        // same prayer. A later prayer still open (this one started by hand after it) keeps its own window.
        for (r in s.runs.filter { it.prayer != null && !it.test && (it.prayer == prayer || endsWindowOf(prayer, it.prayer)) }) {
            if (r.confirmed) complete(r) else missPrayer(r, "next_adhan", now)
        }
        val p = file.prayerProtocol(prayer) ?: return
        if (dhuhrReplaced(prayer)) {
            log(EntryType.SKIPPED, protocol = p.id, prayer = prayer, reason = "jummah", detail = "Friday: Jummah replaces Dhuhr")
            return
        }
        startPrayer(prayer, p, "adhan")
    }

    /** The run of [prayer] starts now ([reason] adhan, or jummah_missed): its window runs until the next prayer's adhan. */
    private fun startPrayer(prayer: Prayer, p: Protocol, reason: String) {
        val (windowEnd, estimated) = estimateWindowEnd(prayer)
        val stage = stageOf(p)
        // Class rule at stage 3 too: the one notification is silent during a class.
        val inClass = stage >= 3 && dayTimes().classAt(now) != null
        val base = newRun(p, stage, reason, null, null, false)
            .copy(prayer = prayer, windowEndsAt = windowEnd, windowEstimated = estimated, silent = inClass)
        if (suspended) {
            s = s.copy(lastFired = s.lastFired + (p.id to now))
            val open = base.copy(phase = RunPhase.OPEN, lock = LockType.NONE)
            add(open)
            logRun(EntryType.FIRED, open, reason = reason, detail = "VIGIL is suspended: no takeover; CONFIRM when prayed")
            notifyConfirm(open, p)
            return
        }
        val classEnd = if (stage <= 2) dayTimes().classBlockEnd(now) else null
        if (classEnd != null) {
            s = s.copy(lastFired = s.lastFired + (p.id to now))
            val card = base.copy(phase = RunPhase.CARD, classEndsAt = classEnd, silent = true)
            add(card)
            logRun(EntryType.FIRED, card, reason = reason, detail = "in class until ${hhmm(classEnd)}: silent card")
            logRun(EntryType.CLASS_CARD, card, until = classEnd, detail = "silent card; the takeover starts when the class ends")
            effects += Effect.Notify(
                prayerNotice(prayer), p.title, "Class now: pray in the first break or right after class.",
                silent = true, kind = NotifyKind.CLASS_CARD, runId = card.id,
            )
            return
        }
        place(base, p)
    }

    /** True when [arriving]'s adhan closes the window of [open]: an earlier prayer of the day, or (Fajr) any of last night's. */
    private fun endsWindowOf(arriving: Prayer, open: Prayer?): Boolean =
        open != null && open != arriving && (arriving == Prayer.FAJR || open.ordinal < arriving.ordinal)

    /** The next prayer's estimated adhan (last adhan + whole days), or a 12-hour safety end without an estimate. */
    private fun estimateWindowEnd(prayer: Prayer): Pair<Instant?, Boolean> {
        val next = prayer.next ?: return null to false
        val last = s.prayers.lastAdhan[next]
        if (last != null) {
            val est = Timeline.estimate(last, now.plusSeconds(1))
            if (Duration.between(now, est) <= Machine.MAX_ESTIMATE_AHEAD) return est to true
        }
        return now.plus(Machine.SAFETY_WINDOW) to false
    }

    private fun warn(r: Run, p: Protocol) {
        val end = windowEnd(r)
        logRun(EntryType.WINDOW_WARNING, r, until = end, detail = "the window closes at ${end?.let { hhmm(it) }} and the prayer is not confirmed")
        val inClass = dayTimes().classAt(now) != null
        if (r.stage <= 2 && !suspended) {
            if (!r.test) yieldScreenToPrayer()
            put(r.copy(warned = true, returned = true, phase = RunPhase.RETURNED, classEndsAt = null, silent = inClass))
            effects += Effect.CancelNotify(prayerNotice(r.prayer!!))
            if (screenOwner()?.id == r.id) effects += Effect.ShowTakeover(r.id)
        } else {
            put(r.copy(warned = true))
            val left = end?.let { WallClock.minutesCeil(now, it) } ?: 0
            effects += Effect.Notify(
                prayerNotice(r.prayer!!), "${p.title}: $left MIN LEFT", "Pray, then tap CONFIRM. The window closes at ${end?.let { hhmm(it) }}.",
                silent = s.toggles.quiet || inClass, kind = NotifyKind.WARNING, runId = r.id,
            )
        }
    }

    private fun classOver(r: Run) {
        logRun(EntryType.CLASS_CARD, r, detail = "class over: the takeover starts")
        effects += Effect.CancelNotify(prayerNotice(r.prayer!!))
        put(r.copy(classEndsAt = null, silent = false, phase = RunPhase.QUEUED))
        val owner = screenHolder()
        // Like at the adhan: a protocol holding the screen pauses for the prayer, the night lock yields.
        if (owner == null || (!owner.prayerLike && !r.test)) {
            yieldScreenToPrayer()
            activate(r.id)
        }
    }

    private fun notifyConfirm(r: Run, p: Protocol) {
        val end = windowEnd(r)
        val text = if (end != null) "Pray, then tap CONFIRM. The window closes at ${hhmm(end)}." else "Pray, then tap CONFIRM."
        effects += Effect.Notify(prayerNotice(r.prayer!!), "${p.title} NOT CONFIRMED", text, silent = true, kind = NotifyKind.CONFIRM, runId = r.id)
    }

    private fun settleHeadsUps() {
        if (suspended) return
        for (prayer in Prayer.entries) {
            val p = file.prayerProtocol(prayer) ?: continue
            if (stageOf(p) >= 3 || dhuhrReplaced(prayer)) continue
            val lead = p.intParam("heads_up_min")
            if (lead <= 0) continue
            val last = s.prayers.lastAdhan[prayer] ?: continue
            val est = nextEstimate(last)
            val at = est.minus(Duration.ofMinutes(lead.toLong()))
            if (now.isBefore(at) || !now.isBefore(est) || s.prayers.headsUpFor[prayer] == est) continue
            s = s.copy(prayers = s.prayers.copy(headsUpFor = s.prayers.headsUpFor + (prayer to est)))
            log(EntryType.HEADS_UP, protocol = p.id, prayer = prayer, scheduled = est)
            effects += Effect.Notify(headsUpNotice(prayer), "${p.title} IN $lead MIN", "Finish what you are doing.", silent = true, kind = NotifyKind.HEADS_UP)
        }
    }

    // ------------------------------------------------------------------ planned starts

    private fun fireDue() {
        for (item in plan()) {
            if (item.source == StartSource.ADHAN || item.key in day.handled) continue
            val p = file.find(item.protocolId) ?: continue
            // Jummah still starts late (the phone was off at leave-by): it joins at the part that is due, until missed.
            val late = when (p.kind) {
                ProtocolKind.JUMMAH -> jummahToday()?.missedAt
                // A call card comes until its deadline, a commute walk until the commute's end.
                ProtocolKind.CALL, ProtocolKind.WALK -> item.slot?.end
                else -> null
            }
            val until = late ?: item.at.plus(Machine.PLANNED_GRACE)
            if (item.at.isAfter(now) || now.isAfter(until)) continue
            // A walk protocol's planned starts are the MY TIMES commute rows: the walking switch goes on.
            val reason = if (p.kind == ProtocolKind.WALK) "commute" else (p.parsedTrigger as? Trigger.Slot)?.let { "slot:${it.type.json}" } ?: "time"
            val r = fire(p, reason, item.at, item.slot?.let(::slotInfo))
            // A Jummah skipped while VIGIL is suspended starts once the suspension is over.
            if (r == null && suspended && p.kind == ProtocolKind.JUMMAH) continue
            val started = r != null || day.deferred.any { it.protocol == p.id }
            updateDay { it.copy(handled = it.handled + (item.key to if (started) "fired" else "skipped")) }
        }
    }

    private fun slotInfo(slot: ResolvedSlot) = SlotInfo(slot.list.name.lowercase(), slot.label, slot.start, slot.end, slot.minutes)

    // ------------------------------------------------------------------ pool

    private fun prunePool() {
        val since = now.minus(Duration.ofMinutes(cfg.heavyUse.withinMin.toLong()))
        val pool = s.pool
        if (pool.openTimes.any { !it.isAfter(since) } || pool.recentUse.any { !it.at.isAfter(since) }) {
            updatePool { it.copy(openTimes = it.openTimes.filter { t -> t.isAfter(since) }, recentUse = it.recentUse.filter { u -> u.at.isAfter(since) }) }
        }
    }

    private fun settlePool() {
        val session = s.pool.session ?: return
        val inSince = session.inSince
        if (inSince == null) {
            val left = session.leftAt ?: return
            if (!now.isBefore(left.plusSeconds(cfg.sessionGapS.toLong()))) endSession(left, cooldown = true)
            return
        }
        if (suspended) return
        if (session.usedAt(now) >= cfg.heavyUse.continuousMin * 60L) {
            heavyUse("continuous")
            return
        }
        val blocks = lockBlocks() + PoolRules.timeBlocks(cfg, s.pool, now, windows())
        if (blocks.isEmpty()) return
        val b = blocks.first()
        endSession(now, cooldown = true)
        log(
            EntryType.POOL_BLOCKED, app = session.app, reason = b.reason.json, until = b.until,
            detail = "session ended: ${PoolText.block(b, zone)}",
        )
        effects += Effect.Kick(session.app, b.reason)
    }

    private fun endSession(at: Instant, cooldown: Boolean) {
        val session = s.pool.session ?: return
        val used = session.usedAt(at)
        var pool = s.pool.copy(
            session = null,
            usedSeconds = s.pool.usedSeconds + used,
            windowUsed = session.windowId?.let { s.pool.windowUsed + (it to (s.pool.windowUsed[it] ?: 0) + used) } ?: s.pool.windowUsed,
            recentUse = s.pool.recentUse + UseMark(at, used),
        )
        log(
            EntryType.POOL_SESSION, at = at, app = session.app, minutes = WallClock.minutesCeil(used), seconds = used,
            detail = session.windowId?.let { "$it window" },
        )
        if (cooldown) {
            val cool = PoolRules.cooldownSeconds(cfg, used)
            if (cool > 0) {
                val until = at.plusSeconds(cool)
                pool = pool.copy(cooldownUntil = maxOf(pool.cooldownUntil ?: until, until), cooldownCause = PoolState.CAUSE_SESSION)
                log(EntryType.COOLDOWN, at = at, minutes = WallClock.minutesCeil(cool), seconds = cool, until = until, reason = PoolState.CAUSE_SESSION)
            }
        }
        s = s.copy(pool = pool)
    }

    private fun heavyUse(kind: String) {
        val session = s.pool.session ?: return
        val recent = PoolRules.recentUsedSeconds(cfg, s.pool, now)
        val detail = if (kind == "continuous") {
            "${cfg.heavyUse.continuousMin} minutes in one session"
        } else {
            "${cfg.heavyUse.opens} opens within ${cfg.heavyUse.withinMin} minutes"
        }
        log(EntryType.HEAVY_USE, app = session.app, reason = kind, minutes = WallClock.minutesCeil(recent), seconds = recent, detail = detail)
        endSession(now, cooldown = false)
        var cool = PoolRules.cooldownSeconds(cfg, recent)
        if (kind == "opens") {
            // Until enough of these opens leave the span, the next open would be heavy use again the moment its wait
            // ends: keep the pool shut until then instead of making the user wait only to be kicked.
            val times = s.pool.openTimes.sorted()
            val clears = times.getOrNull(times.size - cfg.heavyUse.opens + 1)?.plus(Duration.ofMinutes(cfg.heavyUse.withinMin.toLong()))
            if (clears != null) cool = maxOf(cool, Duration.between(now, clears).seconds)
        }
        if (cool > 0) {
            val until = now.plusSeconds(cool)
            updatePool { it.copy(cooldownUntil = maxOf(it.cooldownUntil ?: until, until), cooldownCause = PoolState.CAUSE_HEAVY_USE) }
            log(EntryType.COOLDOWN, minutes = WallClock.minutesCeil(cool), seconds = cool, until = until, reason = PoolState.CAUSE_HEAVY_USE)
        }
        effects += Effect.Kick(session.app, BlockReason.HEAVY_USE)
    }

    private fun onForeground(app: String?, isPool: Boolean) {
        val wasPool = s.foregroundIsPool
        val nowPool = isPool && app != null
        s = s.copy(foreground = app, foregroundIsPool = nowPool)
        val session = s.pool.session
        if (session != null) {
            val updated = when {
                nowPool && session.inSince == null -> session.copy(inSince = now, leftAt = null, app = app!!)
                nowPool -> session.copy(app = app!!)
                session.inSince != null -> session.copy(usedSeconds = session.usedAt(now), inSince = null, leftAt = now)
                else -> session
            }
            updatePool { it.copy(session = updated) }
            return
        }
        if (nowPool && !wasPool && !suspended) gate(app!!)
    }

    private fun gate(app: String) {
        when (val d = decision(true)) {
            is PoolDecision.Blocked -> {
                logBlocked(app, d)
                effects += Effect.PoolGate(app, d)
            }
            is PoolDecision.Wait -> effects += Effect.PoolGate(app, d)
            PoolDecision.Allowed -> Unit
        }
    }

    private fun logBlocked(app: String, d: PoolDecision.Blocked) {
        val b = d.blocks.first()
        log(EntryType.POOL_BLOCKED, app = app, reason = b.reason.json, until = b.until, detail = PoolText.block(b, zone))
    }

    private fun onWaitStarted(app: String) {
        if (s.pool.session != null || suspended) return
        when (val d = decision(true)) {
            is PoolDecision.Wait -> {
                updatePool { it.copy(wait = PoolWait(app, now, d.minutes)) }
                log(EntryType.POOL_WAIT, app = app, minutes = d.minutes)
            }
            is PoolDecision.Blocked -> {
                logBlocked(app, d)
                effects += Effect.PoolGate(app, d)
            }
            PoolDecision.Allowed -> Unit
        }
    }

    private fun onWaitDone(app: String) {
        val w = s.pool.wait ?: return
        if (s.pool.session != null) {
            updatePool { it.copy(wait = null) }
            return
        }
        if (Duration.between(w.startedAt, now).seconds < w.minutes * 60L - Machine.WAIT_TOLERANCE_S) return
        updatePool { it.copy(wait = null) }
        // The wait is over: only what blocks now counts (not whether a new wait would still end inside the window).
        val blocks = if (suspended) emptyList() else lockBlocks() + PoolRules.timeBlocks(cfg, s.pool, now, windows())
        if (blocks.isNotEmpty()) {
            val d = PoolDecision.Blocked(blocks)
            logBlocked(app, d)
            effects += Effect.PoolGate(app, d)
            return
        }
        startSession(app, w)
    }

    private fun startSession(app: String, w: PoolWait) {
        val window = PoolRules.openWindow(windows(), s.pool, now)
        val opens = s.pool.opens + 1
        updatePool {
            it.copy(opens = opens, openTimes = it.openTimes + now, wait = null, session = PoolSession(now, window?.id, app))
        }
        s = s.copy(foreground = app, foregroundIsPool = true)
        log(EntryType.POOL_OPEN, app = app, minutes = w.minutes, detail = "open $opens today" + (window?.let { " (${it.id} window)" } ?: ""))
        if (PoolRules.opensWithin(cfg, s.pool, now) >= cfg.heavyUse.opens) heavyUse("opens")
    }

    private fun onWaitAbandoned(app: String) {
        val w = s.pool.wait ?: return
        updatePool { it.copy(wait = null) }
        log(EntryType.POOL_WAIT_ABANDONED, app = app, minutes = w.minutes, seconds = Duration.between(w.startedAt, now).seconds.coerceAtLeast(0))
    }

    /** The bail-out penalty for leaving [r] early. */
    private fun penalty(r: Run) {
        val (pool, added) = PoolRules.penalty(cfg, s.pool, now)
        s = s.copy(pool = pool)
        log(
            EntryType.PENALTY, protocol = r.protocol, run = r.id, minutes = added, until = pool.penaltyUntil,
            detail = if (added == 0) "the daily cap of ${cfg.bailOut.dailyCapMin} minutes is reached" else null,
        )
    }

    // ------------------------------------------------------------------ inputs

    fun apply(input: Input) {
        when (input) {
            Input.Tick -> Unit
            is Input.Alarm -> input.scheduledAt?.let { planned ->
                val late = Duration.between(planned, now).seconds
                if (late > Machine.ALARM_LATE_S) {
                    log(EntryType.ALARM_LATE, scheduled = planned, seconds = late, minutes = WallClock.minutesCeil(late))
                }
            }
            is Input.Adhan -> onAdhan(input.prayer, manual = false)
            is Input.WakeAlarm -> when {
                input.test -> wakeTest()
                // A dismissal with no alarm ringing or waiting (a stale alarm screen) changes nothing: it must not
                // count as tomorrow's alarm.
                s.wakeAlarm.ringingFor == null && s.wakeAlarm.morningAtUnlock == null -> log(
                    EntryType.SKIPPED, protocol = morningProtocol?.id, reason = "wake_alarm", detail = "no wake alarm was ringing",
                )
                else -> wakeUp("dismissed")
            }
            is Input.WakeAlarmUnanswered -> when {
                input.test -> log(EntryType.WAKE_ALARM_UNANSWERED, protocol = morningProtocol?.id, detail = "TEST ring", test = true)
                s.wakeAlarm.ringingSince != null -> unanswered(now)
            }
            Input.RingWakeAlarmTest -> {
                log(EntryType.WAKE_ALARM, protocol = morningProtocol?.id, reason = "test", detail = "TEST ring", test = true)
                stopAudio("wake_alarm")
                effects += Effect.RingWakeAlarm(now, 1, now.plus(ringMax()), test = true)
            }
            is Input.TodoDone -> onTodo(input.index, input.done)
            is Input.Toggle -> onToggle(input.name, input.on)
            is Input.Manual -> onManual(input.protocolId, input.test, input.minutes, input.label)
            is Input.StepDone -> onStepDone(input.runId, input.index, input.text)
            is Input.Confirm -> onConfirm(input.runId)
            is Input.NotOkay -> onNotOkay(input.runId)
            is Input.BailOut -> onBailOut(input.runId)
            is Input.CallActive -> onCall(input.on)
            is Input.Screen -> {
                // The idle count (day end after wind-down) runs from the moment the phone was last put down. Only an
                // unlocked phone is in use: a wake that shows just the lock screen (a notification lighting it up,
                // lifting the phone in bed) pauses the count while the screen is on but does not start it again.
                if (!input.on) {
                    s = s.copy(screenOn = false, screenUnlocked = false, screenOffSince = s.screenOffSince ?: now, unlockedSince = null)
                    onForeground(null, false)
                } else {
                    s = s.copy(
                        screenOn = true, screenUnlocked = input.unlocked, screenOffSince = s.screenOffSince.takeIf { !input.unlocked },
                        unlockedSince = if (input.unlocked) s.unlockedSince ?: now else null,
                    )
                    // The wake alarm was never answered: the first unlock after it is waking up.
                    if (input.unlocked && s.wakeAlarm.morningAtUnlock != null) wakeUp("unlock")
                }
            }
            is Input.Foreground -> onForeground(input.app, input.isPool)
            is Input.PoolWaitStarted -> onWaitStarted(input.app)
            is Input.PoolWaitDone -> onWaitDone(input.app)
            is Input.PoolWaitAbandoned -> onWaitAbandoned(input.app)
            is Input.GoingToSleep -> goToSleep(input.ishaPrayed)
            is Input.Suspend -> onSuspend(input.minutes)
            Input.Resume -> if (s.suspendedUntil != null) {
                s = s.copy(suspendedUntil = null)
                log(EntryType.KILL_SWITCH, reason = "resumed", detail = "ended early")
                regateDue = true
            }
            is Input.EditMyTimes -> {
                val first = input.applyNow && !s.times.edited
                s = s.copy(times = s.times.edit(input.times, input.applyNow))
                log(EntryType.MY_TIMES, detail = if (first) "applied now (first setup)" else "applies from tomorrow")
            }
            is Input.AppExit -> log(
                EntryType.APP_EXIT, reason = input.reason,
                detail = listOfNotNull(input.at?.let { "at ${WallClock.stamp(it, zone)}" }, input.detail).joinToString("; ").ifEmpty { null },
            )
            is Input.SpeedBump -> log(EntryType.SPEED_BUMP, app = input.target, detail = input.detail)
            is Input.Promise -> onPromise(input.text, input.minutes, input.test)
            is Input.Snooze -> onSnooze(input.runId)
            is Input.Dismiss -> onDismiss(input.runId)
            is Input.AnswerOffer -> answerOffer(input.start)
            is Input.TestJummah -> testJummah(input.part)
            is Input.LogSet -> onLogSet(input.runId, input.exercise, input.reps, input.weight, input.restS)
            is Input.FinishWorkout -> run(input.runId)?.let { r ->
                if (r.phase == RunPhase.ACTIVE && stepsOf(r).getOrNull(r.step)?.type == StepType.WORKOUT) finishWorkout(r, auto = false)
            }
            Input.DndNoAccess -> log(
                EntryType.DND, reason = "no_access",
                detail = "Do Not Disturb access is not granted (SETUP): the lock shows, the phone is not silenced",
            )
            is Input.Steps -> onSteps(input.total)
            is Input.PlayAudio -> playAudio(input.sound, input.bpm, input.loop, input.name, "audio")
            is Input.StopAudio -> stopAudio(input.reason.take(MAX_REASON_CHARS))
        }
    }

    /**
     * GOING TO SLEEP (home, or the wind-down's last step): Isha confirmed first when the user said so, the wind-down
     * completes, the day ends now and the night lock starts. Not before the day's own wake time: the day then started
     * last night (an earlier tap, idle, the first start), and ending it again would skip a whole day (its key, MY TIMES
     * weekday and windows). Returns true when the day ended.
     */
    private fun goToSleep(ishaPrayed: Boolean): Boolean {
        val wake = dayTimes().wake
        if (!now.isAfter(wake)) {
            log(
                EntryType.SKIPPED, reason = "sleep_tap",
                detail = "GOING TO SLEEP before ${hhmm(wake)}: the day ${day.key} began at ${hhmm(day.startedAt)} and goes on",
            )
            return false
        }
        if (ishaPrayed) confirmIsha()
        s.runs.firstOrNull { it.kind == ProtocolKind.SLEEP && !it.test && it.phase != RunPhase.NIGHT }?.let {
            // Like the day end: nothing starts after the wind-down at this moment.
            dayEnding = true
            finishWindDown(it)
            dayEnding = false
        }
        endDay(DayEndReason.SLEEP_TAP, now)
        return true
    }

    /** GOING TO SLEEP finishes the wind-down: its last step is done and it completes (not clean when steps were skipped). */
    private fun finishWindDown(r: Run) {
        val steps = stepsOf(r)
        val last = steps.lastIndex
        val skipped = r.phase != RunPhase.REMINDER && r.step < last
        if (last >= 0 && r.step <= last) {
            logRun(EntryType.STEP_DONE, r, step = last, detail = steps[last].text, reason = if (skipped) "going_to_sleep" else null)
        }
        complete(r.copy(confirmed = true, steppedDown = r.steppedDown || skipped))
    }

    /** "I prayed Isha" at GOING TO SLEEP: it counts, even while its done step is still locked. */
    private fun confirmIsha() {
        val isha = ishaToConfirm() ?: return
        val confirmed = isha.copy(confirmed = true)
        put(confirmed)
        logConfirmed(confirmed)
    }

    private fun onTodo(index: Int, done: Boolean) {
        val list = s.todos[day.key] ?: return
        val todo = list.getOrNull(index) ?: return
        if (todo.done == done) return
        s = s.copy(todos = s.todos + (day.key to list.mapIndexed { i, t -> if (i == index) t.copy(done = done) else t }))
        log(EntryType.TODO_DONE, day = day.key, step = index, text = todo.text, reason = if (done) "done" else "undone")
    }

    /** A wind-down's to-dos step: its lines become the to-dos of the day the next wake alarm belongs to (tomorrow). */
    private fun setTodos(r: Run, st: Step, text: String) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(st.lines ?: 1)
        if (lines.isEmpty()) return
        val day = alarmKey()
        val key = day.toString()
        // Friday's Jummah prep stays on the list.
        val prep = prepTodos(day).filter { it.text !in lines }
        s = s.copy(todos = s.todos + (key to lines.map { Todo(it) } + prep))
        log(EntryType.TODO_SET, protocol = r.protocol, run = r.id, day = key, text = lines.joinToString("\n"), detail = "${lines.size} for $key")
    }

    /** The Jummah prep to-dos (the jummah protocol's `prep_todos`) when [day] is a Friday, else none. */
    private fun prepTodos(day: LocalDate): List<Todo> {
        val p = jummahProtocol ?: return emptyList()
        if (WallClock.dayCode(day) != "fri") return emptyList()
        return p.textListParam("prep_todos").map { Todo(it) }
    }

    /** Thursday's wind-down adds the Jummah prep to Friday's to-dos (once, whatever else is written there). */
    private fun addPrepTodos() {
        val day = alarmKey()
        val key = day.toString()
        val list = s.todos[key] ?: emptyList()
        val added = prepTodos(day).filter { t -> list.none { it.text == t.text } }
        if (added.isEmpty()) return
        s = s.copy(todos = s.todos + (key to list + added))
        log(EntryType.TODO_SET, protocol = jummahProtocol?.id, day = key, text = added.joinToString("\n") { it.text }, detail = "Jummah prep for $key")
    }

    private fun onToggle(name: ToggleName, on: Boolean) = setToggle(name, on)

    /**
     * A home switch changes at [at]. Walking and friends start their protocols when they go on ([slot]: the commute row
     * that switched walking on) and complete them when they go off. [why] is the walk's journal reason: switch, commute or
     * jummah when it starts; off, still, time, arrived, cap or day_end when it ends.
     */
    private fun setToggle(
        name: ToggleName,
        on: Boolean,
        at: Instant = now,
        detail: String = if (on) "on" else "off",
        why: String = if (on) "switch" else "off",
        slot: SlotInfo? = null,
    ) {
        val t = s.toggles
        when (name) {
            ToggleName.QUIET -> {
                if (t.quiet == on) return
                s = s.copy(toggles = t.copy(quiet = on))
                log(EntryType.QUIET, at = at, detail = detail)
                return
            }
            ToggleName.WALKING -> {
                if (t.walking == on) return
                // Without a step counter: a commute ends its length + commute_grace_min after its start, any other walk
                // after manual_max_min.
                val until = when {
                    !on -> null
                    slot != null -> slot.start.plus(Duration.ofMinutes((slot.minutes ?: s.times.current.commuteMin).toLong() + walkParam("commute_grace_min")))
                    else -> now.plus(Duration.ofMinutes(walkParam("manual_max_min").toLong()))
                }
                s = s.copy(toggles = t.copy(walking = on, walkingSince = if (on) now else null, walkingUntil = until))
            }
            ToggleName.FRIENDS -> {
                if (t.friends == on) return
                s = s.copy(toggles = t.copy(friends = on, friendsSince = if (on) now else null))
            }
        }
        val since = if (name == ToggleName.WALKING) t.walkingSince else t.friendsSince
        val minutes = if (on) null else since?.let { WallClock.minutesCeil(it, at) }
        log(EntryType.TOGGLE, at = at, reason = name.json, detail = detail, minutes = minutes)
        if (name == ToggleName.WALKING) {
            if (on) {
                val ends = if (s.steps.available) "ends after ${walkParam("still_end_min")} minutes without steps" else
                    "no step counter: ends at ${s.toggles.walkingUntil?.let { hhmm(it) }} at the latest"
                log(EntryType.WALK_START, protocol = walkProtocol?.id, reason = why, detail = ends)
            } else {
                log(EntryType.WALK_END, at = at, protocol = walkProtocol?.id, reason = why, minutes = minutes, detail = detail)
            }
        }
        val started = file.protocols.filter { (it.parsedTrigger as? Trigger.Toggle)?.toggle == name }
        if (on) {
            started.forEach { fire(it, if (why == "commute") "commute" else "toggle:${name.json}", planned = slot?.start, slot = slot) }
        } else {
            started.forEach { p -> s.runs.filter { it.protocol == p.id && !it.test }.forEach { complete(it, at) } }
        }
    }

    /** The home switch that starts and ends runs of [kind] (walk, friends), or null. */
    private fun switchOf(kind: ProtocolKind): ToggleName? = when (kind) {
        ProtocolKind.WALK -> ToggleName.WALKING
        ProtocolKind.FRIENDS -> ToggleName.FRIENDS
        else -> null
    }

    private fun switchOn(name: ToggleName): Boolean = when (name) {
        ToggleName.QUIET -> s.toggles.quiet
        ToggleName.WALKING -> s.toggles.walking
        ToggleName.FRIENDS -> s.toggles.friends
    }

    private fun onManual(id: String, test: Boolean, minutes: Int?, label: String?) {
        val p = file.find(id) ?: return
        val prayer = (p.parsedTrigger as? Trigger.Adhan)?.prayer
        when {
            prayer != null && !test -> onAdhan(prayer, manual = true)
            prayer != null -> {
                val (end, estimated) = estimateWindowEnd(prayer)
                place(newRun(p, 1, "test", null, null, true).copy(prayer = prayer, windowEndsAt = end, windowEstimated = estimated), p)
            }
            else -> fire(p, if (test) "test" else "manual", test = test, minutes = minutes, label = label)
        }
    }

    private fun onStepDone(runId: String, index: Int, text: String?) {
        val r = run(runId) ?: return
        if (r.phase != RunPhase.ACTIVE && r.phase != RunPhase.RETURNED && r.phase != RunPhase.OPEN) return
        // A walk or friends run ends with its switch, a nudge with its seconds: their steps are only shown.
        if (r.kind in NEUTRAL) return
        val steps = stepsOf(r)
        if (index != r.step || index !in steps.indices) return
        val st = steps[index]
        val forced = r.stage == 1 && r.phase == RunPhase.ACTIVE
        when (st.type) {
            StepType.CHECK -> {
                val until = st.min?.let { m -> r.stepStartedAt?.plus(Duration.ofMinutes(m.toLong())) }
                if (forced && until != null && runNow.isBefore(until)) return
            }
            StepType.TIMER -> if (forced) return
            // A to-dos step takes up to its lines (at least one); an optional one may stay empty; others need every line.
            StepType.TEXT -> if (nonBlankLines(text) < (if (st.optional) 0 else if (st.todos) 1 else st.lines ?: 1)) return
            StepType.DONE -> if ((forced && r.doneAfterAt != null && runNow.isBefore(r.doneAfterAt)) || !callDone(r)) return
            // Rounds never reach a run as such (they become timers); a workout step is finished like FINISH WORKOUT.
            StepType.ROUNDS -> return
            StepType.WORKOUT -> {
                if (r.phase == RunPhase.ACTIVE) finishWorkout(r, auto = false)
                return
            }
        }
        if (r.jummah != null) {
            jummahStep(r, steps, text)
            return
        }
        if (r.promise != null && st.type == StepType.DONE) {
            keepPromise(r)
            return
        }
        // The wind-down's last step is GOING TO SLEEP: the day ends (before the day's wake time only the run finishes).
        if (r.kind == ProtocolKind.SLEEP && !r.test && index == steps.lastIndex && goToSleep(ishaPrayed = false)) return
        stepDone(r, steps, text = text?.trim()?.ifEmpty { null })
    }

    private fun nonBlankLines(text: String?): Int = text?.lines()?.count { it.isNotBlank() } ?: 0

    private fun onConfirm(runId: String) {
        val r = run(runId) ?: return
        // A nudge ends by itself when its seconds are over (DONE on its screen comes after that).
        if (r.kind == ProtocolKind.NUDGE) return
        if (r.promise != null) {
            keepPromise(r)
            return
        }
        // A call card: DONE once a call (or, short, a message) unlocked it.
        if (r.call != null && r.phase != RunPhase.REMINDER) {
            if (r.phase == RunPhase.ACTIVE && callDone(r)) complete(r.copy(confirmed = true))
            return
        }
        val j = r.jummah
        if (j != null) {
            // On the way it is I'M AT UNI; from the lock on it is "I prayed Jummah".
            when (j.part) {
                JummahPart.LEAVE, JummahPart.TRAVEL -> confirmArrival(r)
                JummahPart.LOCK, JummahPart.CONFIRM -> confirmJummah(r)
            }
            return
        }
        if (r.prayer == null) {
            if (r.phase == RunPhase.REMINDER) complete(r.copy(confirmed = true))
            return
        }
        if (doneLockedUntil(r) != null) return
        var cur = r
        if (!cur.confirmed) {
            cur = cur.copy(confirmed = true)
            logConfirmed(cur)
        }
        val lockEnd = cur.lockEndsAt
        if (cur.phase == RunPhase.ACTIVE && lockEnd != null && runNow.isBefore(lockEnd)) {
            val steps = stepsOf(cur)
            val doneAt = steps.indexOfFirst { it.type == StepType.DONE }
            if (doneAt >= cur.step) cur = cur.copy(step = doneAt + 1, stepStartedAt = now)
            put(cur)
            if (cur.step >= steps.size) complete(cur)
        } else {
            complete(cur)
        }
    }

    private fun onNotOkay(runId: String) {
        val r = run(runId) ?: return
        // NOT OKAY on a nudge ends it, at no cost.
        if (r.kind == ProtocolKind.NUDGE) {
            endNudge(r, "not_okay")
            return
        }
        if (r.phase != RunPhase.ACTIVE && r.phase != RunPhase.QUEUED) return
        val p = protocol(r) ?: return
        if (p.kind == ProtocolKind.PRAYER) {
            if (r.shortened) return
            val minimum = p.lock.doneAfterMin ?: return
            val shortEnd = r.lockStartedAt?.let { r.doneAfterAt ?: it.plus(Duration.ofMinutes(minimum.toLong())) }
            val lockEnd = r.lockEndsAt
            val ends = if (shortEnd != null && lockEnd != null) minOf(lockEnd, shortEnd) else lockEnd
            put(r.copy(shortened = true, lockEndsAt = ends))
            logRun(EntryType.STEPPED_DOWN, r, until = ends?.plus(callShift(r)), detail = "lock shortened to the $minimum-minute minimum")
            return
        }
        if (p.kind.ladder.isEmpty()) return
        val next = p.nextVersion(r.version, r.weekday) ?: return
        // A study block keeps only what is left of it: the shorter rounds fill the rest, not the whole block again.
        val length = if (r.kind == ProtocolKind.STUDY) (studyBlock(r, p) - roundMinutesSpent(r)).coerceAtLeast(0) else r.lengthMin
        val moved = r.copy(version = next, step = 0, stepStartedAt = now, steppedDown = true, lengthMin = length)
        put(moved)
        logRun(EntryType.STEPPED_DOWN, moved, detail = if (next == Version.SINGLE) "minimum" else if (moved.call != null) "a message instead" else null)
        if (moved.call != null) notifyCall(moved) else if (moved.lock != LockType.SCREEN && moved.phase == RunPhase.ACTIVE) notifyRun(moved)
    }

    private fun onBailOut(runId: String) {
        val r = run(runId) ?: return
        val p = protocol(r) ?: return
        if (r.phase == RunPhase.NIGHT) {
            // Leaving the night lock costs the usual penalty; the night itself was judged when the day ended.
            endNight(r, "left")
            penalty(r)
            return
        }
        if (r.jummah != null) {
            bailJummah(r)
            return
        }
        // A nudge is left at no cost; a switch's run (its notification's STOP) turns the switch off, a TEST one just ends.
        if (r.kind == ProtocolKind.NUDGE) {
            endNudge(r, "left")
            return
        }
        val switch = switchOf(r.kind)
        if (switch != null) {
            if (r.test) complete(r) else setToggle(switch, false)
            return
        }
        r.promise?.let { logRun(EntryType.PROMISE, r, reason = "bailed", text = it.text) }
        if (r.prayer != null) {
            if (r.phase != RunPhase.ACTIVE && r.phase != RunPhase.QUEUED && r.phase != RunPhase.RETURNED) return
            val minimumEnd = r.doneAfterAt ?: r.lockStartedAt?.plus(Duration.ofMinutes((p.lock.doneAfterMin ?: 0).toLong()))
            val afterMinimum = r.phase == RunPhase.RETURNED || (minimumEnd != null && !runNow.isBefore(minimumEnd))
            if (afterMinimum) {
                val released = r.copy(phase = RunPhase.OPEN, released = true)
                put(released)
                logRun(EntryType.RELEASED, released, detail = "left after the minimum: no penalty; CONFIRM when prayed")
                notifyConfirm(released, p)
                if (!r.afterFired) fireAfter(released)
            } else {
                val bailed = r.copy(phase = RunPhase.OPEN, bailed = true)
                put(bailed)
                logRun(EntryType.BAILED, bailed, detail = "left the prayer lock before its minimum; CONFIRM when prayed")
                if (!r.test) {
                    penalty(bailed)
                    recordOutcome(bailed, RunOutcome.BAILED)
                }
                notifyConfirm(bailed, p)
            }
            promoteQueue()
            return
        }
        if (r.phase == RunPhase.REMINDER) return
        // Leaving tonight's wind-down makes the night a missed one for sleep shifting.
        if (r.kind == ProtocolKind.SLEEP && !r.test) updateDay { it.copy(sleepBailed = true) }
        remove(r.id)
        logRun(EntryType.BAILED, r)
        cancelRunNotices(r)
        if (!r.test) {
            penalty(r)
            recordOutcome(r, RunOutcome.BAILED)
        }
        promoteQueue()
    }

    private fun onCall(on: Boolean) {
        if (on) {
            if (s.callSince != null) return
            // A nudge never stays over a call.
            s.runs.filter { it.kind == ProtocolKind.NUDGE }.forEach { endNudge(it, "call") }
            s = s.copy(callSince = now, callWrapped = false, callWrapAt = wrapAtFor(now))
            val paused = s.runs.filter { it.phase == RunPhase.ACTIVE }.map { it.protocol }
            log(EntryType.CALL_PAUSE, detail = paused.joinToString(", ").ifEmpty { null })
            return
        }
        endCall(now)
    }

    /** The call ended at [at]: every active run's lock, done lock and current step move on by the call's length. */
    private fun endCall(at: Instant, detail: String? = null) {
        val since = s.callSince ?: return
        for (r in s.runs.filter { it.phase == RunPhase.ACTIVE }) {
            val base = maxOf(since, r.lockStartedAt ?: since)
            val shift = Duration.between(base, at).takeIf { !it.isNegative } ?: Duration.ZERO
            val stepStart = r.stepStartedAt?.let { if (it.isBefore(base)) it.plus(shift) else maxOf(it, at) }
            put(r.copy(lockEndsAt = r.lockEndsAt?.plus(shift), doneAfterAt = r.doneAfterAt?.plus(shift), stepStartedAt = stepStart))
        }
        creditCall(since, at)
        if (s.callWrapped) effects += Effect.CancelNotify(WRAP_NOTICE)
        s = s.copy(callSince = null, callWrapAt = null, callWrapped = false)
        val length = Duration.between(since, at).seconds.coerceAtLeast(0)
        log(EntryType.CALL_RESUME, at = at, minutes = WallClock.minutesCeil(length), seconds = length, detail = detail)
    }

    private fun onSuspend(minutes: Int) {
        val until = now.plus(Duration.ofMinutes(minutes.coerceAtLeast(1).toLong()))
        s = s.copy(suspendedUntil = until)
        log(EntryType.KILL_SWITCH, minutes = minutes, until = until, reason = "suspended")
        for (id in s.runs.map { it.id }) {
            // Fresh each time: closing one run can start a queued one.
            val r = run(id) ?: continue
            val p = protocol(r)
            if (r.phase == RunPhase.NIGHT) {
                endNight(r, "kill_switch")
                continue
            }
            if (r.kind == ProtocolKind.NUDGE) {
                endNudge(r, "kill_switch")
                continue
            }
            // A switch stays on (the pool is open anyway while suspended), and its run with it.
            if (r.kind in SWITCHES && !r.test) continue
            // Like a prayer, Jummah stays open (no screen, the phone's sound back) and goes on once the suspension ends.
            val j = r.jummah
            if (j != null && !r.test) {
                when (j.part) {
                    JummahPart.LEAVE -> toTravel(r, "VIGIL was suspended")
                    JummahPart.TRAVEL -> if (r.asking) put(r.copy(asking = false))
                    JummahPart.LOCK -> toConfirm(r, "VIGIL was suspended")
                    JummahPart.CONFIRM -> Unit
                }
                continue
            }
            if (r.prayer != null && p != null) {
                when {
                    r.confirmed -> complete(r)
                    r.phase == RunPhase.ACTIVE || r.phase == RunPhase.QUEUED || r.phase == RunPhase.RETURNED || r.phase == RunPhase.CARD -> {
                        val open = r.copy(phase = RunPhase.OPEN, classEndsAt = null)
                        put(open)
                        notifyConfirm(open, p)
                    }
                }
            } else {
                remove(r.id)
                logRun(EntryType.DROPPED, r, reason = "kill_switch")
                cancelRunNotices(r)
            }
        }
        clearOffer("expired", "VIGIL was suspended")
        updatePool { it.copy(wait = null) }
        // The pool is simply open while suspended: a live session ends now (its minutes and cooldown count as usual), so
        // the suspended time is never counted, and never turns into heavy use the moment the suspension ends.
        s.pool.session?.let { endSession(if (it.inSince != null) now else it.leftAt ?: now, cooldown = true) }
    }

    // ------------------------------------------------------------------ Jummah (design M4)

    /**
     * Starts the Jummah run of [p]: today's times, or for a TEST of one part ([only]) times that put that part now. It
     * joins at the part the clock is in, so a late start (the phone was off at leave-by) skips what is over.
     */
    private fun startJummah(p: Protocol, reason: String, planned: Instant?, test: Boolean, only: JummahPart?): Run? {
        val times = if (only != null) testJummahTimes(p, only) else jummahToday()
        if (times == null || !now.isBefore(times.missedAt)) {
            val why = if (times == null) "not_friday" else "over"
            log(EntryType.SKIPPED, protocol = p.id, reason = why, scheduled = planned, detail = "Jummah runs on Fridays, until its missed time", test = test)
            return null
        }
        if (!test) s = s.copy(lastFired = s.lastFired + (p.id to now))
        val r = newRun(p, 1, reason, planned, null, test).copy(jummah = times.copy(only = only))
        add(r)
        val lockEnd = times.lockAt.plus(Duration.ofMinutes((p.lock.minutes ?: 0).toLong()))
        val holder = screenHolder()
        val leave = now.isBefore(times.arriveAt)
        logRun(
            EntryType.FIRED, r, reason = reason, scheduled = planned,
            detail = "leave by ${hhmm(times.leaveAt)}, I'M AT UNI by ${hhmm(times.arriveAt)}, lock ${hhmm(times.lockAt)}-${hhmm(lockEnd)}" +
                if (leave && holder != null) "; waits behind ${title(holder)}" else "",
        )
        when {
            !now.isBefore(times.lockAt) -> startLock(r)
            !leave -> toTravel(r, null)
            holder == null -> activateJummah(r.id)
        }
        return run(r.id) ?: r
    }

    /** A TEST part's times: as on a Friday without classes, shifted so that [part] starts now. */
    private fun testJummahTimes(p: Protocol, part: JummahPart): JummahInfo {
        fun param(name: String): Duration = Duration.ofMinutes(p.intParam(name).toLong())
        val commute = Duration.ofMinutes(s.times.current.commuteMin.toLong())
        val lockAt = when (part) {
            JummahPart.LEAVE -> now.plus(commute).plus(param("leave_buffer_min"))
            JummahPart.TRAVEL -> now.plus(param("arrive_before_min"))
            JummahPart.LOCK, JummahPart.CONFIRM -> now
        }
        val jummahAt = lockAt.plus(param("lock_before_min"))
        val leaveAt = minOf(now, lockAt.minus(commute).minus(param("leave_buffer_min")))
        return JummahInfo(
            jummahAt = jummahAt, leaveAt = leaveAt, arriveAt = maxOf(leaveAt, lockAt.minus(param("arrive_before_min"))),
            lockAt = lockAt, missedAt = jummahAt.plus(param("missed_after_min")),
        )
    }

    /** Moves a Jummah run on by the clock: the leave-by screen's end, the I'M AT UNI asks, the card, the lock, missed. */
    private fun settleJummah(id: String) {
        var r = run(id) ?: return
        var j = r.jummah ?: return
        // Nothing confirmed by its time (a lock that a call made longer finishes first).
        val lockRunning = j.part == JummahPart.LOCK && r.phase == RunPhase.ACTIVE
        if (!now.isBefore(j.missedAt) && !lockRunning) {
            missJummah(r)
            return
        }
        sendCard(r)
        r = run(id) ?: return
        j = r.jummah ?: return
        when (j.part) {
            JummahPart.LEAVE -> when {
                !now.isBefore(j.lockAt) -> startLock(r)
                !now.isBefore(j.arriveAt) -> toTravel(r, if (r.phase == RunPhase.ACTIVE) "the leave-by takeover ended at the arrival time" else null)
                r.phase == RunPhase.ACTIVE && s.callSince == null -> {
                    val moved = runTimers(r) ?: return
                    if (moved.step >= arrivalIndex(stepsOf(moved))) toTravel(moved, null)
                }
            }
            JummahPart.TRAVEL -> when {
                !now.isBefore(j.lockAt) -> startLock(r)
                !j.arrived && !suspended && !now.isBefore(j.askedAt?.plus(askRepeat(r)) ?: j.arriveAt) -> ask(r)
            }
            JummahPart.LOCK -> when {
                r.phase == RunPhase.QUEUED -> r.lockEndsAt?.let { if (!now.isBefore(it)) lockOver(r, it) }
                r.phase == RunPhase.ACTIVE && s.callSince == null -> {
                    val moved = runTimers(r) ?: return
                    moved.lockEndsAt?.let { if (!now.isBefore(it)) lockOver(moved, it) }
                }
            }
            JummahPart.CONFIRM -> Unit
        }
    }

    private fun askRepeat(r: Run): Duration = Duration.ofMinutes((protocol(r)?.intParam("arrive_repeat_min") ?: 10).toLong())

    /** The index of the I'M AT UNI step (the parser makes sure there is one). */
    private fun arrivalIndex(steps: List<Step>): Int = steps.indexOfFirst { it.arrival }.takeIf { it >= 0 } ?: steps.size

    /** The index of the confirming done step. */
    private fun doneIndex(steps: List<Step>): Int = steps.indexOfLast { it.type == StepType.DONE }.takeIf { it >= 0 } ?: steps.lastIndex

    /** The run takes the screen for its part: the leave-by takeover, or the Jummah lock (Do Not Disturb on). */
    private fun activateJummah(id: String) {
        val r = run(id) ?: return
        val j = r.jummah ?: return
        when (j.part) {
            JummahPart.LEAVE -> {
                if (!now.isBefore(j.arriveAt)) {
                    toTravel(r, null)
                    return
                }
                put(r.copy(phase = RunPhase.ACTIVE, startedAt = r.startedAt ?: now, lockStartedAt = now, lockEndsAt = j.arriveAt, doneAfterAt = null, stepStartedAt = now))
                if (!r.test) recordStart(r.protocol)
                effects += Effect.ShowTakeover(id)
            }
            JummahPart.LOCK -> {
                val end = r.lockEndsAt
                if (end != null && !now.isBefore(end)) {
                    lockOver(r, end)
                    return
                }
                val on = r.copy(phase = RunPhase.ACTIVE, startedAt = r.startedAt ?: now, lockStartedAt = now, stepStartedAt = now, jummah = j.copy(dnd = true))
                put(on)
                if (!r.test) recordStart(r.protocol)
                logRun(EntryType.JUMMAH_LOCK, on, until = end, detail = "the phone silent and the pool blocked until ${end?.let { hhmm(it) }}")
                effects += Effect.SetDnd(true)
                logRun(EntryType.DND, on, reason = "on", detail = "the Jummah lock")
                effects += Effect.CancelNotify(runNotice(r))
                effects += Effect.ShowTakeover(id)
            }
            JummahPart.TRAVEL, JummahPart.CONFIRM -> Unit
        }
    }

    /** On the way: the screen is free; I'M AT UNI comes at the arrival time ([why] says why the leave-by screen ended). */
    private fun toTravel(r: Run, why: String?) {
        val j = r.jummah ?: return
        val p = protocol(r) ?: return
        if (why != null) logRun(EntryType.LOCK_ENDED, r, detail = why)
        val a = arrivalIndex(stepsOf(r))
        val t = r.copy(
            phase = RunPhase.OPEN, asking = false, step = if (j.arrived) a + 1 else a, stepStartedAt = now, lockEndsAt = null,
            doneAfterAt = null, jummah = j.copy(part = JummahPart.TRAVEL),
        )
        put(t)
        if (j.only == JummahPart.LEAVE) {
            complete(t)
            return
        }
        effects += Effect.Notify(
            runNotice(t), "${p.title}: ON THE WAY", "Tap I'M AT UNI once you are there. The phone goes silent at ${hhmm(j.lockAt)}.",
            silent = true, kind = NotifyKind.CONFIRM, runId = t.id,
        )
    }

    /** I'M AT UNI? A full takeover now, and again every arrive_repeat_min until it is confirmed or the lock starts. */
    private fun ask(r: Run) {
        val j = r.jummah ?: return
        val p = protocol(r) ?: return
        val asked = r.copy(asking = true, jummah = j.copy(askedAt = now))
        put(asked)
        logRun(EntryType.ARRIVAL_CHECK, asked, detail = if (j.askedAt == null) "I'M AT UNI? The lock starts at ${hhmm(j.lockAt)}" else "asked again")
        if (screenOwner()?.id == r.id) effects += Effect.ShowTakeover(r.id)
        effects += Effect.Notify(
            runNotice(r), "${p.title}: I'M AT UNI?", "Tap I'M AT UNI once you are there. The phone goes silent at ${hhmm(j.lockAt)}.",
            silent = true, kind = NotifyKind.CONFIRM, runId = r.id,
        )
    }

    /** I'M AT UNI: the asks stop, the walking switch goes off, and the run waits for the lock. */
    private fun confirmArrival(r: Run) {
        val j = r.jummah ?: return
        if (j.arrived || (j.part != JummahPart.LEAVE && j.part != JummahPart.TRAVEL)) return
        val p = protocol(r) ?: return
        val late = Duration.between(j.arriveAt, now).toMinutes().toInt()
        val detail = when {
            late > 0 -> "$late min after ${hhmm(j.arriveAt)}"
            late < 0 -> "${-late} min before ${hhmm(j.arriveAt)}"
            else -> "on time"
        }
        logRun(EntryType.ARRIVED, r, minutes = late, detail = detail)
        if (!r.test && s.toggles.walking) setToggle(ToggleName.WALKING, false, detail = "off (arrived at uni)", why = "arrived")
        val arrived = r.copy(
            phase = RunPhase.OPEN, asking = false, step = arrivalIndex(stepsOf(r)) + 1, stepStartedAt = now, lockEndsAt = null,
            doneAfterAt = null, jummah = j.copy(part = JummahPart.TRAVEL, arrived = true),
        )
        put(arrived)
        if (j.only == JummahPart.TRAVEL) {
            complete(arrived)
            return
        }
        effects += Effect.Notify(
            runNotice(r), "${p.title} AT ${hhmm(j.jummahAt)}", "At uni. The phone goes silent at ${hhmm(j.lockAt)}: go straight to the prayer place.",
            silent = true, kind = NotifyKind.RUN, runId = r.id,
        )
    }

    /** The silent card before the last Friday class ends (once; not when it is late or the lock has begun). */
    private fun sendCard(r: Run) {
        val j = r.jummah ?: return
        val at = j.cardAt ?: return
        if (j.cardSent || now.isBefore(at) || r.test) return
        put(r.copy(jummah = j.copy(cardSent = true)))
        if (Duration.between(at, now) > Machine.PLANNED_GRACE || !now.isBefore(j.lockAt) || suspended) return
        val p = protocol(r) ?: return
        logRun(EntryType.JUMMAH_CARD, r, detail = cardText(p))
        effects += Effect.Notify(JUMMAH_CARD_NOTICE, p.title, cardText(p), silent = true, kind = NotifyKind.CARD, runId = r.id)
    }

    private fun cardText(p: Protocol): String =
        "Class ends in ${p.intParam("card_before_end_min")} minutes. Go straight to the prayer place."

    /**
     * The Jummah lock begins (jummah_time - lock_before_min, for lock.minutes): Do Not Disturb, the pool blocked and a
     * subtle silent screen; DONE unlocks lock.done_after_min after its start. Behind a prayer's lock it waits; any
     * other protocol holding the screen pauses for it. A TEST of another part ends here.
     */
    private fun startLock(r: Run) {
        val j = r.jummah ?: return
        val p = protocol(r) ?: return
        when (j.only) {
            JummahPart.LEAVE -> {
                complete(r)
                return
            }
            JummahPart.TRAVEL -> {
                if (j.arrived) complete(r) else missRun(r, "not_confirmed", detail = "I'M AT UNI was not confirmed before the lock")
                return
            }
            else -> Unit
        }
        if (suspended) return
        val steps = stepsOf(r)
        val lockEnd = j.lockAt.plus(Duration.ofMinutes((p.lock.minutes ?: 0).toLong()))
        val doneAfter = p.lock.doneAfterMin?.let { j.lockAt.plus(Duration.ofMinutes(it.toLong())) }
        val locked = r.copy(
            phase = RunPhase.QUEUED, asking = false, silent = true, step = (arrivalIndex(steps) + 1).coerceAtMost(steps.lastIndex),
            stepStartedAt = null, lockEndsAt = lockEnd, doneAfterAt = doneAfter, jummah = j.copy(part = JummahPart.LOCK),
        )
        put(locked)
        effects += Effect.CancelNotify(JUMMAH_CARD_NOTICE)
        if (!now.isBefore(lockEnd)) {
            lockOver(locked, lockEnd)
            return
        }
        val holder = screenHolder()
        if (holder != null && holder.prayerLike) {
            logRun(EntryType.JUMMAH_LOCK, locked, detail = "waits behind ${title(holder)}")
            return
        }
        if (holder != null) yieldScreenToPrayer()
        activateJummah(locked.id)
    }

    /** The lock ended unconfirmed at [end]: the phone's sound comes back; CONFIRM until the missed time. */
    private fun lockOver(r: Run, end: Instant) {
        logRun(EntryType.JUMMAH_LOCK, r, at = end, reason = "ended", detail = "ended unconfirmed: CONFIRM by ${r.jummah?.missedAt?.let { hhmm(it) }}")
        toConfirm(r, "the Jummah lock ended")
        // Like a prayer whose lock ends: what follows the lock (the attention reset) starts.
        run(r.id)?.let { if (!it.afterFired && !it.bailed) fireAfter(it) }
    }

    /** No lock and no screen any more: Do Not Disturb off, CONFIRM on home and in a notification until the missed time. */
    private fun toConfirm(r: Run, why: String) {
        val j = r.jummah ?: return
        val p = protocol(r) ?: return
        if (j.dnd) dndOff(r, why)
        val c = r.copy(
            phase = RunPhase.OPEN, asking = false, silent = false, step = doneIndex(stepsOf(r)), stepStartedAt = now, lockEndsAt = null,
            doneAfterAt = null, jummah = j.copy(part = JummahPart.CONFIRM, dnd = false),
        )
        put(c)
        effects += Effect.Notify(
            runNotice(c), "${p.title} NOT CONFIRMED", "Prayed? Tap CONFIRM by ${hhmm(j.missedAt)}.",
            silent = true, kind = NotifyKind.CONFIRM, runId = c.id,
        )
    }

    /** DONE (or CONFIRM): Jummah was prayed. Not while DONE is still locked. */
    private fun confirmJummah(r: Run) {
        if (doneLockedUntil(r) != null) return
        val c = r.copy(confirmed = true)
        put(c)
        if (!r.test) updateDay { it.copy(jummah = DayState.JUMMAH_CONFIRMED) }
        logRun(EntryType.CONFIRMED, c)
        complete(c)
    }

    /**
     * A tap on a Jummah step: the leave-by checklist (the walking step switches walking on), I'M AT UNI, or the lock's
     * steps (DONE confirms Jummah).
     */
    private fun jummahStep(r: Run, steps: List<Step>, text: String?) {
        val j = r.jummah ?: return
        val st = steps[r.step]
        val typed = text?.trim()?.ifEmpty { null }
        when {
            st.arrival -> confirmArrival(r)
            j.part == JummahPart.LEAVE && r.phase == RunPhase.ACTIVE -> {
                val moved = stepDone(r, steps, text = typed) ?: return
                if (moved.step >= arrivalIndex(steps)) toTravel(moved, null)
            }
            st.type == StepType.DONE && (j.part == JummahPart.LOCK || j.part == JummahPart.CONFIRM) -> confirmJummah(r)
            j.part == JummahPart.LOCK && r.phase == RunPhase.ACTIVE -> stepDone(r, steps, text = typed)
        }
    }

    /**
     * LEAVE on Jummah: the leave-by takeover or the lock is left (the usual penalty; the run goes on: the lock still
     * comes, CONFIRM still counts). On the I'M AT UNI ask there is nothing to leave: it closes like NOT YET.
     */
    private fun bailJummah(r: Run) {
        val j = r.jummah ?: return
        if (asking(r)) {
            onDismiss(r.id)
            return
        }
        if (r.phase != RunPhase.ACTIVE || (j.part != JummahPart.LEAVE && j.part != JummahPart.LOCK)) return
        logRun(EntryType.BAILED, r, detail = if (j.part == JummahPart.LEAVE) "left the leave-by takeover" else "left the Jummah lock; CONFIRM by ${hhmm(j.missedAt)}")
        if (!r.test) {
            penalty(r)
            if (!r.bailed) recordOutcome(r, RunOutcome.BAILED)
        }
        val bailed = r.copy(bailed = true)
        put(bailed)
        if (j.part == JummahPart.LEAVE) toTravel(bailed, null) else toConfirm(bailed, "the Jummah lock was left")
        promoteQueue()
    }

    /**
     * Nothing confirmed by the missed time: missed (it counts for the reset offer), the Dhuhr prayer runs instead (until
     * Asr) and the reset is offered.
     */
    private fun missJummah(r: Run) {
        val j = r.jummah ?: return
        if (!r.test) updateDay { it.copy(jummah = DayState.JUMMAH_MISSED) }
        remove(r.id)
        logRun(EntryType.MISSED, r, at = j.missedAt, reason = "not_confirmed", detail = "nothing confirmed by ${hhmm(j.missedAt)}")
        cancelRunNotices(r)
        if (!r.test && !r.bailed) recordOutcome(r, RunOutcome.MISSED)
        promoteQueue()
        if (r.test) return
        dhuhrAfterJummah()
        resetProtocol?.let { offer(it, "jummah_missed") }
    }

    /** Jummah was missed: today's Dhuhr runs after all, its window until Asr (not once Asr has come). */
    private fun dhuhrAfterJummah() {
        val p = file.prayerProtocol(Prayer.DHUHR) ?: return
        if (Prayer.ASR in day.adhans) {
            log(EntryType.SKIPPED, protocol = p.id, prayer = Prayer.DHUHR, reason = "window_closed", detail = "Jummah was missed after Asr came: the Dhuhr window is over")
            return
        }
        if (s.runs.any { it.prayer == Prayer.DHUHR && !it.test }) return
        startPrayer(Prayer.DHUHR, p, "jummah_missed")
    }

    /** TEST > JUMMAH: one part now as a preview (the card is only its notification). */
    private fun testJummah(part: JummahTestPart) {
        val p = jummahProtocol ?: return
        val only = when (part) {
            JummahTestPart.LEAVE -> JummahPart.LEAVE
            JummahTestPart.ARRIVAL -> JummahPart.TRAVEL
            JummahTestPart.LOCK -> JummahPart.LOCK
            JummahTestPart.CARD -> {
                log(EntryType.JUMMAH_CARD, protocol = p.id, detail = cardText(p), test = true)
                effects += Effect.Notify(JUMMAH_CARD_NOTICE, p.title, cardText(p), silent = true, kind = NotifyKind.CARD)
                return
            }
        }
        if (s.runs.any { it.jummah != null && it.test }) return
        startJummah(p, "test", null, test = true, only = only)
    }

    // ------------------------------------------------------------------ the 10-minute promise (design M5)

    /** LATER: one line and its minutes; the timer runs in the background. One promise at a time. */
    private fun onPromise(text: String, minutes: Int, test: Boolean) {
        val p = promiseProtocol ?: return
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(MAX_PROMISE_CHARS) ?: return
        if (!test && suspended) {
            log(EntryType.SKIPPED, protocol = p.id, reason = "suspended", text = line, detail = "VIGIL is suspended")
            return
        }
        if (s.runs.any { it.promise != null }) {
            log(EntryType.SKIPPED, protocol = p.id, reason = "one_at_a_time", text = line, detail = "a promise is already running", test = test)
            return
        }
        val m = minutes.coerceIn(1, MAX_PROMISE_MIN)
        val due = now.plus(Duration.ofMinutes(m.toLong()))
        val r = newRun(p, 1, "later", null, null, test).copy(phase = RunPhase.WAITING, promise = PromiseInfo(line, m, due))
        if (!test) s = s.copy(lastFired = s.lastFired + (p.id to now))
        add(r)
        logRun(EntryType.FIRED, r, reason = "later", detail = "due at ${hhmm(due)}")
        logRun(EntryType.PROMISE, r, reason = "promised", text = line, minutes = m, until = due)
        notifyPromise(r)
    }

    /** The promise is due: its full takeover asks (exactly the line), and the pool is locked until DONE. */
    private fun promiseDue(r: Run) {
        val promise = r.promise ?: return
        val due = r.copy(phase = RunPhase.ACTIVE, asking = true, startedAt = r.startedAt ?: now, lockStartedAt = now, lockEndsAt = null, doneAfterAt = null, stepStartedAt = now)
        put(due)
        if (!r.test) recordStart(r.protocol)
        logRun(EntryType.PROMISE, due, at = promise.dueAt, reason = "due", text = promise.text)
        notifyPromise(due)
    }

    private fun notifyPromise(r: Run) {
        val promise = r.promise ?: return
        val p = protocol(r) ?: return
        val title = if (r.phase == RunPhase.WAITING) "${p.title} AT ${hhmm(promise.dueAt)}" else "${p.title}: NOW. THE POOL IS LOCKED UNTIL DONE."
        effects += Effect.Notify(runNotice(r), title, promise.text, silent = true, kind = NotifyKind.PROMISE, runId = r.id)
    }

    /** SNOOZE: once per promise, half its minutes (at least snooze_min_min); the takeover and the pool lock wait too. */
    private fun onSnooze(id: String) {
        val r = run(id) ?: return
        val promise = r.promise ?: return
        val m = Machine.snoozeMinutes(r, file) ?: return
        val due = now.plus(Duration.ofMinutes(m.toLong()))
        val waiting = r.copy(phase = RunPhase.WAITING, asking = false, lockStartedAt = null, promise = promise.copy(dueAt = due, snoozed = true))
        put(waiting)
        logRun(EntryType.PROMISE, waiting, reason = "snoozed", text = promise.text, minutes = m, until = due)
        notifyPromise(waiting)
    }

    /** DONE: the promise was kept (also before it was due). */
    private fun keepPromise(r: Run) {
        val promise = r.promise ?: return
        logRun(
            EntryType.PROMISE, r, reason = "kept", text = promise.text, minutes = WallClock.minutesCeil(r.firedAt, now),
            detail = if (r.phase == RunPhase.WAITING) "done before it was due" else null,
        )
        complete(r.copy(confirmed = true))
    }

    /** ON IT (a promise: the pool lock stays) or NOT YET (I'M AT UNI: it asks again later): the asking takeover closes. */
    private fun onDismiss(id: String) {
        val r = run(id) ?: return
        if (!asking(r)) return
        put(r.copy(asking = false))
        val j = r.jummah
        when {
            r.call != null -> logRun(EntryType.CALL_CARD, r, reason = "not_now", detail = who(r), until = r.call.dueAt)
            r.promise != null -> logRun(EntryType.PROMISE, r, reason = "on_it", text = r.promise.text, detail = "the pool stays locked until DONE")
            j != null -> logRun(EntryType.ARRIVAL_CHECK, r, reason = "not_yet", detail = "asks again at ${hhmm((j.askedAt ?: now).plus(askRepeat(r)))}")
        }
    }

    // ------------------------------------------------------------------ the gym (design M7)

    /**
     * LOG SET: the set joins the workout (an exercise the gym knows in another case is that one), the journal gets it
     * with whether it beat the best, the pre-fill and best move on (not for a TEST) and the rest timer starts.
     */
    private fun onLogSet(runId: String, exercise: String, reps: Int, weight: Double, restS: Int?) {
        val r = run(runId) ?: return
        if (r.phase != RunPhase.ACTIVE || stepsOf(r).getOrNull(r.step)?.type != StepType.WORKOUT) return
        val p = protocol(r) ?: return
        val typed = exercise.trim().replace(Regex("\\s+"), " ").take(Machine.MAX_EXERCISE_CHARS)
        if (typed.isEmpty() || reps !in 1..MAX_REPS || weight.isNaN() || weight < 0.0 || weight > MAX_WEIGHT) return
        val known = (p.textListParam("exercises") + s.gym.last.keys + (r.gym?.sets ?: emptyList()).map { it.exercise })
        val name = known.firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed
        val set = GymSet(name, reps, weight, now)
        val best = s.gym.best[name]
        val better = best == null || weight > best.weight || (weight == best.weight && reps > best.reps)
        val rest = (restS ?: s.gym.restS[name] ?: p.intParam("rest_s")).coerceIn(MIN_REST_S, MAX_REST_S)
        val gym = r.gym ?: GymInfo()
        put(r.copy(gym = gym.copy(sets = gym.sets + set, restUntil = now.plusSeconds(rest.toLong()))))
        if (!r.test) {
            val g = s.gym
            s = s.copy(
                gym = g.copy(
                    last = g.last + (name to set),
                    best = if (better) g.best + (name to set) else g.best,
                    restS = if (restS != null) g.restS + (name to rest) else g.restS,
                ),
            )
        }
        val detail = when {
            best == null -> "first time"
            better -> "new best (was ${setText(best)})"
            else -> "best ${setText(best)}"
        }
        logRun(EntryType.GYM_SET, r, detail = detail, exercise = name, reps = reps, weight = weight)
    }

    /**
     * FINISH WORKOUT (or, [auto], nobody finished it an hour after its planned minutes): the workout is journaled and the
     * next step follows. Returns the run, or null when that finished it.
     */
    private fun finishWorkout(r: Run, auto: Boolean, at: Instant = now): Run? {
        val sets = r.gym?.sets ?: emptyList()
        val exercises = sets.map { it.exercise }.distinct().size
        logRun(
            EntryType.WORKOUT, r, at = at, minutes = r.stepStartedAt?.let { WallClock.minutesCeil(it, at) },
            reason = if (auto) "auto" else null,
            detail = "${sets.size} sets of $exercises exercises" + if (auto) " (it ended by itself: FINISH WORKOUT was not tapped)" else "",
        )
        val stopped = r.copy(gym = r.gym?.copy(restUntil = null))
        put(stopped)
        return stepDone(stopped, stepsOf(stopped), auto = auto, at = at)
    }

    /** 5 x 80 kg, or 12 reps for body weight. */
    private fun setText(set: GymSet): String =
        if (set.weight == 0.0) "${set.reps} reps" else "${set.reps} x ${kg(set.weight)} kg"

    // ------------------------------------------------------------------ walk, friends, calls, audio, nudges (design M8)

    /**
     * A walk or friends run lasts while its switch is on (the switch completes it when it goes off). A TEST one (a
     * preview: no switch, nothing blocked) ends by itself after [Machine.TEST_SWITCH].
     */
    private fun settleSwitch(r: Run) {
        if (r.test) {
            val end = r.firedAt.plus(Machine.TEST_SWITCH)
            if (!now.isBefore(end)) complete(r, end)
            return
        }
        val switch = switchOf(r.kind)?.takeIf { protocol(r)?.parsedTrigger == Trigger.Toggle(it) } ?: return
        if (!switchOn(switch)) complete(r)
    }

    /** The switch's silent, ongoing notice with STOP: what is blocked and when it ends by itself. */
    private fun notifySwitch(r: Run) {
        val (title, text) = when {
            r.test -> "TEST · ${title(r)}" to "A preview: nothing is blocked. It ends in ${Machine.TEST_SWITCH.toMinutes()} minutes."
            r.kind == ProtocolKind.WALK -> "WALKING · POOL BLOCKED" to
                if (s.steps.available) "Maps, WhatsApp and calls stay usable. Ends after ${walkParam("still_end_min")} minutes without steps."
                else "Maps, WhatsApp and calls stay usable. Ends by ${walkEnd()?.let { hhmm(it) } ?: "itself"} at the latest."
            else -> "WITH FRIENDS · POOL BLOCKED" to "Calls, Maps and WhatsApp stay usable. Ends by ${friendsEnd()?.let { hhmm(it) } ?: "itself"}."
        }
        effects += Effect.Notify(runNotice(r), title, text, silent = true, kind = NotifyKind.SWITCH, runId = r.id)
    }

    /**
     * The step counter's total (null = no step counter or no permission: walks end by the time rules). A new total, or
     * the first one, counts as a step now: no stillness is assumed before the counter was read.
     */
    private fun onSteps(total: Long?) {
        val st = s.steps
        if (total == null) {
            if (st.available || st.total != null) s = s.copy(steps = StepState())
            return
        }
        val moved = !st.available || st.total != total
        s = s.copy(steps = StepState(available = true, total = total, lastStepAt = if (moved) now else st.lastStepAt ?: now))
    }

    /** Who a call run is for: its MY TIMES row's label (MOM), else the protocol's title. */
    private fun who(r: Run): String = r.label?.ifBlank { null } ?: title(r)

    /**
     * A call run's card appears: a full-intensity takeover without a lock (an asking takeover: it yields to any screen
     * lock and is not brought back once left, except once after `return_after_min`), and its notification.
     */
    private fun activateCall(id: String) {
        val r = run(id) ?: return
        val c = r.call ?: return
        val a = r.copy(
            phase = RunPhase.ACTIVE, asking = true, startedAt = r.startedAt ?: now, stepStartedAt = now,
            call = c.copy(cardAt = c.cardAt ?: now),
        )
        put(a)
        if (!r.test) {
            recordStart(r.protocol)
            updateDay { it.copy(callCardAt = it.callCardAt ?: now, callWrapMin = c.wrapUpMin) }
        }
        logRun(EntryType.CALL_CARD, a, reason = "card", detail = who(a), until = c.dueAt)
        notifyCall(a)
        // A call already going on when the card appears gets its "time to wrap up" too.
        val since = s.callSince
        if (since != null && s.callWrapAt == null && !s.callWrapped) s = s.copy(callWrapAt = since.plus(Duration.ofMinutes(c.wrapUpMin.toLong())))
    }

    private fun notifyCall(r: Run) {
        val c = r.call ?: return
        val p = protocol(r) ?: return
        val text = when {
            r.version != Version.FULL -> "Send a message instead (MESSAGE), then DONE."
            callDone(r) -> "Call done: tap DONE."
            else -> "By ${hhmm(c.dueAt)}. DONE appears after a call of ${p.intParam("min_call_min")} minutes."
        }
        effects += Effect.Notify(runNotice(r), "${p.title} ${who(r)}", text, silent = true, kind = NotifyKind.CALL, runId = r.id)
    }

    /** A call run: missed at its deadline; the card comes back once after `return_after_min` (not during a call). */
    private fun settleCall(r: Run) {
        val c = r.call ?: return
        if (!r.confirmed && !now.isBefore(c.dueAt)) {
            missCall(r, "deadline", c.dueAt)
            return
        }
        val back = callReturnAt(r) ?: return
        if (now.isBefore(back) || s.callSince != null) return
        val returned = r.copy(asking = true, returned = true)
        put(returned)
        logRun(EntryType.CALL_CARD, returned, reason = "returned", detail = who(r), until = c.dueAt)
        effects += Effect.ShowTakeover(r.id)
    }

    /** Not done by its deadline (or the day ended first): missed, like any run. */
    private fun missCall(r: Run, why: String, at: Instant) {
        val c = r.call ?: return
        logRun(EntryType.CALL, r, at = at, reason = "missed", detail = who(r), minutes = c.callS.takeIf { it > 0 }?.let { WallClock.minutesCeil(it) })
        missRun(r, why, at, detail = "${who(r)}: not done by ${hhmm(minOf(c.dueAt, at))}")
    }

    /**
     * A call from [since] to [at] ended: each call card that was up counts the part after it appeared (the longest one
     * is kept). A call long enough unlocks DONE: the card comes back with it.
     */
    private fun creditCall(since: Instant, at: Instant) {
        for (r in s.runs.filter { it.call != null && it.phase == RunPhase.ACTIVE }) {
            val c = r.call ?: continue
            val card = c.cardAt ?: continue
            val length = Duration.between(maxOf(since, card), at).seconds
            if (length <= c.callS) continue
            val before = callDone(r)
            val longer = r.copy(call = c.copy(callS = length))
            put(longer)
            if (!before && callDone(longer)) {
                logRun(EntryType.CALL, longer, at = at, reason = "called", detail = who(r), minutes = WallClock.minutesCeil(length))
                put(longer.copy(asking = true))
                notifyCall(longer)
                effects += Effect.ShowTakeover(r.id)
            }
        }
    }

    /**
     * When "time to wrap up" is due for a call starting at [start]: while a call card is up, its wrap-up minutes; else,
     * after today's first call card appeared, the latest card's. Null otherwise.
     */
    private fun wrapAtFor(start: Instant): Instant? {
        val active = s.runs.filter { it.call != null && it.phase == RunPhase.ACTIVE }.mapNotNull { it.call?.wrapUpMin }.minOrNull()
        val minutes = active ?: day.callWrapMin?.takeIf { day.callCardAt?.let { card -> !start.isBefore(card) } == true } ?: return null
        return start.plus(Duration.ofMinutes(minutes.toLong()))
    }

    /** "Time to wrap up": a silent notification once per call, when it has gone on for the wrap-up minutes. */
    private fun settleWrapUp() {
        val at = s.callWrapAt ?: return
        val since = s.callSince ?: return
        if (s.callWrapped || now.isBefore(at)) return
        s = s.copy(callWrapped = true, callWrapAt = null)
        val m = WallClock.minutesCeil(since, at)
        log(EntryType.WRAP_UP, at = at, minutes = m)
        effects += Effect.Notify(WRAP_NOTICE, "TIME TO WRAP UP", "$m minutes on the call.", silent = true, kind = NotifyKind.CARD)
    }

    /**
     * Plays [sound] (one at a time: it replaces any other). The same sound again only updates it (the metronome's new
     * speed) without a journal line; the effect is sent all the same, so a sound lost with the app comes back.
     */
    private fun playAudio(sound: String, bpm: Int?, loop: Boolean, name: String?, reason: String) {
        val ok = Effect.Audio.valid(sound) ?: return
        val beats = if (ok == Effect.Audio.METRONOME) (bpm ?: Effect.Audio.DEFAULT_BPM).coerceIn(Effect.Audio.MIN_BPM, Effect.Audio.MAX_BPM) else null
        val repeat = Effect.Audio.fileId(ok) != null && loop
        val label = name?.trim()?.take(MAX_LABEL_CHARS)?.ifEmpty { null }
        val old = s.audio
        if (old != null && old.sound == ok) {
            s = s.copy(audio = old.copy(bpm = beats, loop = repeat, name = label ?: old.name))
        } else {
            if (old != null) logAudioStop(old, "replaced")
            s = s.copy(audio = AudioState(ok, now, beats, repeat, label))
            log(EntryType.AUDIO_START, reason = reason, detail = audioName(ok, beats, label))
        }
        effects += Effect.Audio(Effect.Audio.PLAY, ok, beats, repeat)
    }

    /**
     * Stops VIGIL's sound ([only]: just that sound, if it is the one playing). The effect is always sent: the app may
     * still play something the state does not know of (a sound started before an update).
     */
    private fun stopAudio(reason: String, only: String? = null) {
        val a = s.audio
        if (a != null && (only == null || a.sound == only)) {
            logAudioStop(a, reason)
            s = s.copy(audio = null)
        }
        effects += Effect.Audio(Effect.Audio.STOP, only)
    }

    private fun logAudioStop(a: AudioState, reason: String) {
        log(EntryType.AUDIO_STOP, reason = reason, minutes = WallClock.minutesCeil(a.since, now), detail = audioName(a.sound, a.bpm, a.name))
    }

    /** white_noise, metronome 100 bpm, or the file's name. */
    private fun audioName(sound: String, bpm: Int?, name: String?): String = when {
        sound == Effect.Audio.METRONOME -> "metronome ${bpm ?: Effect.Audio.DEFAULT_BPM} bpm"
        Effect.Audio.fileId(sound) != null -> name ?: sound
        else -> sound
    }

    /**
     * Today's timed nudges as they come due (each once: shown, or skipped when something else is going on, the phone is
     * not in use, another nudge came less than `min_gap_min` ago, or it is late), then the movement nudge after
     * `still_min` without steps while the phone is in use.
     */
    private fun settleNudges() {
        val p = nudgeProtocol ?: return
        val times = nudgeTimes(p)
        while (day.nudgesDue < times.size) {
            val (at, line) = times[day.nudgesDue]
            if (now.isBefore(at)) break
            updateDay { it.copy(nudgesDue = it.nudgesDue + 1, moveFrom = now) }
            val gap = Duration.ofMinutes(p.intParam("min_gap_min").toLong())
            val why = when {
                Duration.between(at, now) > Machine.NUDGE_GRACE -> "late"
                else -> nudgeBlocked() ?: day.nudgedAt?.takeIf { Duration.between(it, now) < gap }?.let { "gap" }
            }
            if (why == null) showNudge(p, "timed", line, p.intParam("seconds"))
            else log(EntryType.NUDGE_SKIPPED, protocol = p.id, reason = why, scheduled = at, detail = line)
        }
        val move = moveNudgeAt(p) ?: return
        if (now.isBefore(move)) return
        updateDay { it.copy(moveFrom = now) }
        val lines = p.textListParam("move_nudges")
        val line = lines[Math.floorMod(now.epochSecond / 60, lines.size.toLong()).toInt()]
        val why = nudgeBlocked()
        if (why == null) showNudge(p, "still", line, p.intParam("move_seconds"))
        else log(EntryType.NUDGE_SKIPPED, protocol = p.id, reason = why, detail = line)
    }

    /** A nudge from a button (TEST): now, unless another protocol holds the screen. */
    private fun nudgeNow(p: Protocol, reason: String, test: Boolean): Run? {
        val holder = screenHolder()
        if (holder != null) {
            log(EntryType.NUDGE_SKIPPED, protocol = p.id, reason = "busy", detail = "${title(holder)} holds the screen", test = test)
            return null
        }
        val lines = p.textListParam("nudges").ifEmpty { listOf(p.steps.first().text) }
        return showNudge(p, reason, lines[Math.floorMod(now.epochSecond / 60, lines.size.toLong()).toInt()], p.intParam("seconds"), test)
    }

    /** A nudge takes the screen for [seconds] with [line]; it ends by itself (never a miss, never a penalty). */
    private fun showNudge(p: Protocol, reason: String, line: String, seconds: Int, test: Boolean = false): Run {
        val r = newRun(p, 1, reason, null, null, test).copy(
            phase = RunPhase.ACTIVE, lock = LockType.SCREEN, startedAt = now, lockStartedAt = now, stepStartedAt = now,
            lockEndsAt = now.plusSeconds(seconds.toLong()), label = line,
        )
        add(r)
        if (!test) updateDay { it.copy(nudgedAt = now) }
        log(EntryType.NUDGE_SHOWN, protocol = p.id, run = r.id, reason = reason, detail = line, seconds = seconds.toLong(), test = test)
        return r
    }

    /** A nudge's seconds are over. */
    private fun settleNudge(r: Run) {
        val end = r.lockEndsAt ?: return
        if (!now.isBefore(end)) endNudge(r, "time", end)
    }

    /** A nudge ends ([reason] time, not_okay, left, gave_way, call, kill_switch, day_end): no outcome, no cost. */
    private fun endNudge(r: Run, reason: String, at: Instant = now) {
        remove(r.id)
        log(
            EntryType.NUDGE_DONE, at = at, protocol = r.protocol, run = r.id, reason = reason, detail = r.label,
            seconds = Duration.between(r.firedAt, at).seconds.coerceAtLeast(0), test = r.test,
        )
        cancelRunNotices(r)
        promoteQueue()
    }

    // ------------------------------------------------------------------ the reset and its offer (design M5)

    /** Offers [p] (START / NOT NOW) instead of starting it: a full-intensity card on home and in a notification. */
    private fun offer(p: Protocol, reason: String) {
        val skip = when {
            dayEnding -> "day_end"
            suspended -> "suspended"
            s.runs.any { it.protocol == p.id && !it.test } -> "already_running"
            s.offer?.protocol == p.id -> return
            else -> null
        }
        if (skip != null) {
            log(EntryType.SKIPPED, protocol = p.id, reason = skip, detail = "offered ($reason)")
            return
        }
        s = s.copy(offer = Offer(p.id, now, reason))
        val text = if (reason == "jummah_missed") {
            "Jummah was missed. Pray Dhuhr, then ${p.title} brings the day back. Or NOT NOW: it costs nothing."
        } else {
            "${reason.removePrefix("misses:")} missed or left in a row. ${p.title} brings the day back. Or NOT NOW: it costs nothing."
        }
        log(EntryType.OFFER, protocol = p.id, reason = reason, detail = text)
        effects += Effect.Notify(OFFER_NOTICE, "${p.title}?", text, silent = s.toggles.quiet, kind = NotifyKind.OFFER)
    }

    /** START or NOT NOW on the offer. NOT NOW costs nothing; the next offer comes after as many misses again. */
    private fun answerOffer(start: Boolean) {
        val o = s.offer ?: return
        clearOffer(if (start) "started" else "not_now", if (start) "START" else "NOT NOW: it costs nothing")
        if (start) file.find(o.protocol)?.let { fire(it, "offer") }
    }

    private fun clearOffer(reason: String, detail: String, at: Instant = now) {
        val o = s.offer ?: return
        s = s.copy(offer = null)
        effects += Effect.CancelNotify(OFFER_NOTICE)
        log(EntryType.OFFER, at = at, protocol = o.protocol, reason = reason, detail = detail)
    }

    /**
     * The reset's last step: re-entering the day. Today's missed and waiting runs are dropped (no catch-up, no penalty,
     * no stage change), the miss count starts again; then an open prayer comes first, else the next planned protocol
     * starts now if it is due within next_within_min, else it is named ([View.reentryLine]).
     */
    private fun reenter(r: Run) {
        val p = protocol(r) ?: return
        val dropped = s.runs.filter {
            it.id != r.id && !it.test && it.prayer == null && it.jummah == null && it.promise == null && it.phase in DROPPED_AT_REENTRY
        }
        for (old in dropped) {
            remove(old.id)
            logRun(EntryType.DROPPED, old, reason = "reset", detail = "dropped at re-entry: no catch-up")
            cancelRunNotices(old)
        }
        s = s.copy(missesInARow = 0)
        val plan = reentryPlan(p.intParam("next_within_min"))
        val line = reentryLine(plan)
        val why = when (plan) {
            is Reentry.Prayer -> {
                val prayer = run(plan.run.id)
                val pp = prayer?.let { protocol(it) }
                when {
                    prayer == null || pp == null -> Unit
                    // Its takeover starts as soon as the reset is over (prayers go first).
                    prayer.phase == RunPhase.OPEN || prayer.phase == RunPhase.CARD ->
                        put(prayer.copy(phase = RunPhase.QUEUED, classEndsAt = null, silent = false))
                    prayer.phase == RunPhase.REMINDER -> effects += Effect.Notify(
                        prayerNotice(prayer.prayer!!), pp.title, "Time to pray. Tap CONFIRM when done.",
                        silent = s.toggles.quiet, kind = NotifyKind.REMINDER, runId = prayer.id,
                    )
                }
                "prayer"
            }
            is Reentry.Start -> {
                val next = file.find(plan.item.protocolId)
                val started = next?.let { fire(it, "reenter", plan.item.at, plan.item.slot?.let(::slotInfo)) }
                updateDay { it.copy(handled = it.handled + (plan.item.key to if (started != null) "fired" else "skipped")) }
                "started"
            }
            is Reentry.Next -> "next"
            Reentry.Nothing -> "nothing"
        }
        log(
            EntryType.REENTRY, protocol = r.protocol, run = r.id, reason = why, detail = line,
            text = r.texts.values.filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { null },
        )
    }

    // ------------------------------------------------------------------ sleep and wake

    private fun ringMax(): Duration = Duration.ofMinutes(alarmParam("ring_max_min").toLong())

    /**
     * Sleep shifting's verdict on the night of day [d], which ends at [at] for [reason]: good when GOING TO SLEEP came at
     * most `late_min` after tonight's target and the wind-down was not left early. Journals the sleep time (GOING TO
     * SLEEP, or the moment the phone was put down) and a missed night. True without a sleep protocol.
     */
    private fun judgeNight(d: DayState, reason: DayEndReason, at: Instant): Boolean {
        val p = sleepProtocol ?: return true
        val target = dayTimes().bedtime
        val after = Duration.between(target, at).toMinutes().toInt()
        if (reason == DayEndReason.SLEEP_TAP || reason == DayEndReason.IDLE) {
            val detail = when {
                after > 0 -> "$after min after the target ${hhmm(target)}"
                after < 0 -> "${-after} min before the target ${hhmm(target)}"
                else -> "at the target ${hhmm(target)}"
            }
            log(EntryType.SLEEP, at = at, protocol = p.id, reason = reason.json, scheduled = target, minutes = after, detail = detail)
        }
        val late = p.intParam("late_min")
        val (why, detail) = when {
            d.sleepBailed -> "bailed" to "the wind-down was left early"
            reason != DayEndReason.SLEEP_TAP -> "no_sleep_tap" to "the day ended without GOING TO SLEEP (${reason.json})"
            after > late -> "late" to "GOING TO SLEEP $after min after the target ${hhmm(target)} (more than $late)"
            else -> return true
        }
        log(EntryType.NIGHT_MISSED, at = at, protocol = p.id, reason = why, scheduled = target, detail = detail)
        return false
    }

    /**
     * The new day's target bedtime: `shift_min` earlier than last night's after a good night, and after a missed one too
     * unless it is the `hold_after_missed`th missed night in a row (then it holds); never before MY TIMES bedtime_goal,
     * never after bedtime_start (a MY TIMES change applies from today, so the target is kept inside the new range).
     */
    private fun shiftTarget(good: Boolean) {
        val p = sleepProtocol ?: return
        val (goal, start) = bedtimeRange(s.times.current)
        val prev = nightMinutes(WallClock.parseTime(s.sleep.target) ?: start)
        val missed = if (good) 0 else s.sleep.missedInARow + 1
        val holdAfter = p.intParam("hold_after_missed")
        val hold = !good && holdAfter > 0 && missed >= holdAfter
        val wanted = if (hold) prev else maxOf(nightMinutes(goal), prev - p.intParam("shift_min"))
        val next = clampTarget(wanted, goal, start)
        val nextMin = nightMinutes(next)
        val reason = when {
            nextMin != wanted || prev < nightMinutes(goal) -> "clamped"
            hold -> "held"
            nextMin == prev -> "goal"
            else -> "shifted"
        }
        setTarget(next, missed, reason, prev - nextMin)
    }

    private fun setTarget(target: LocalTime, missed: Int, reason: String, earlier: Int) {
        val p = sleepProtocol ?: return
        s = s.copy(sleep = SleepState("%02d:%02d".format(target.hour, target.minute), missed))
        val tonight = dayTimes().bedtime
        val detail = when (reason) {
            "start" -> "tonight's target ${hhmm(tonight)}: MY TIMES bedtime start"
            "held" -> "tonight's target ${hhmm(tonight)}: held after $missed missed nights in a row"
            "goal" -> "tonight's target ${hhmm(tonight)}: the goal"
            "clamped" -> "tonight's target ${hhmm(tonight)}: kept within MY TIMES bedtimes"
            else -> "tonight's target ${hhmm(tonight)}: $earlier min earlier"
        }
        log(EntryType.SLEEP_TARGET, protocol = p.id, scheduled = tonight, minutes = earlier, reason = reason, detail = detail)
    }

    /**
     * The night lock begins (at [at]: GOING TO SLEEP, or when the phone was put down / the fallback for an idle or
     * fallback day end): a run of the sleep protocol in phase NIGHT with its lock (screen = a silent dark takeover and
     * the pool blocked), until the wake alarm is answered and at the latest wake + `night_lock_grace_min`. Not at stage 3
     * (one notification, no lock) and not while suspended.
     */
    private fun startNight(reason: DayEndReason, at: Instant) {
        val p = sleepProtocol ?: return
        val stage = stageOf(p)
        if (suspended || stage >= 3 || p.lock.type == LockType.NONE) return
        val alarm = alarmAt(alarmKey())
        // GOING TO SLEEP in the morning (just after waking) is no night: no lock until tomorrow's alarm.
        if (Duration.between(at, alarm) > Machine.MAX_NIGHT) return
        val end = alarm.plus(Duration.ofMinutes(p.intParam("night_lock_grace_min").toLong()))
        if (!end.isAfter(now)) return
        s.runs.filter { it.phase == RunPhase.NIGHT }.forEach { remove(it.id) }
        val id = "run-${s.nextRun}"
        s = s.copy(nextRun = s.nextRun + 1)
        val night = Run(
            id = id, protocol = p.id, kind = p.kind, stage = stage, phase = RunPhase.NIGHT, firedAt = at, reason = "night",
            weekday = WallClock.dayCode(day.date), lock = p.lock.type, startedAt = at, lockStartedAt = at, lockEndsAt = end,
        )
        add(night)
        log(
            EntryType.NIGHT_LOCK, at = at, protocol = p.id, run = id, stage = stage, reason = reason.json, until = end,
            detail = "until the wake alarm is answered (${hhmm(end)} at the latest)",
        )
        if (p.boolParam("white_noise")) playAudio(Effect.Audio.WHITE_NOISE, null, false, null, "night_lock")
    }

    /** The night lock is over ([reason] wake, unlock, timeout, left, kill_switch, day_end). */
    private fun endNight(r: Run, reason: String, at: Instant = now) {
        remove(r.id)
        log(
            EntryType.NIGHT_LOCK, at = at, protocol = r.protocol, run = r.id, stage = r.stage, reason = reason,
            minutes = WallClock.minutesCeil(r.firedAt, at),
        )
        cancelRunNotices(r)
        if (reason == "wake" || reason == "unlock" || reason == "timeout") stopAudio("wake", only = Effect.Audio.WHITE_NOISE)
    }

    /**
     * The wake alarm: it rings at MY TIMES wake of the day it belongs to ([alarmKey]); unanswered after `ring_max_min`
     * it rings again `snooze_min` later, at most `max_rings` times, then gives up (the morning starts at the next unlock).
     * One more than [Machine.PLANNED_GRACE] late (the phone was off) does not ring at all: not_fired.
     */
    private fun settleAlarm() {
        if (!alarmOn) return
        val a = s.wakeAlarm
        val key = a.ringingFor
        if (key != null) {
            val since = a.ringingSince
            val again = a.nextRingAt
            when {
                since != null -> since.plus(ringMax()).let { if (!now.isBefore(it)) unanswered(it) }
                again == null || Duration.between(again, now) > Machine.PLANNED_GRACE -> giveUp(again ?: now)
                !now.isBefore(again) -> ring(key, a.scheduled ?: again, a.rings + 1)
            }
            return
        }
        val due = alarmKey()
        val at = alarmAt(due)
        if (now.isBefore(at)) return
        if (Duration.between(at, now) > Machine.PLANNED_GRACE) {
            s = s.copy(wakeAlarm = a.copy(handledFor = due.toString()))
            log(
                EntryType.NOT_FIRED, protocol = morningProtocol?.id, scheduled = at, reason = "wake_alarm",
                detail = "the wake alarm (${hhmm(at)}) did not ring: the phone was off or VIGIL was stopped",
            )
            return
        }
        ring(due.toString(), at, 1)
    }

    private fun ring(key: String, scheduled: Instant, n: Int) {
        val stop = now.plus(ringMax())
        // The alarm, not white noise (or anything else VIGIL plays).
        stopAudio("wake_alarm")
        s = s.copy(wakeAlarm = s.wakeAlarm.copy(ringingFor = key, scheduled = scheduled, ringingSince = now, rings = n, nextRingAt = null))
        log(EntryType.WAKE_ALARM, protocol = morningProtocol?.id, scheduled = scheduled, detail = "ring $n of ${alarmParam("max_rings")}")
        effects += Effect.RingWakeAlarm(scheduled, n, stop)
    }

    /** A ring ended unanswered at [at]: it rings again `snooze_min` later, or gives up after `max_rings`. */
    private fun unanswered(at: Instant) {
        val a = s.wakeAlarm
        if (a.rings < alarmParam("max_rings")) {
            effects += Effect.StopWakeAlarm
            s = s.copy(wakeAlarm = a.copy(ringingSince = null, nextRingAt = at.plus(Duration.ofMinutes(alarmParam("snooze_min").toLong()))))
        } else {
            giveUp(at)
        }
    }

    private fun giveUp(at: Instant) {
        val a = s.wakeAlarm
        val key = a.ringingFor ?: return
        s = s.copy(wakeAlarm = WakeAlarmState(handledFor = key, morningAtUnlock = key))
        log(
            EntryType.WAKE_ALARM_UNANSWERED, at = at, protocol = morningProtocol?.id, scheduled = a.scheduled,
            detail = "rang ${a.rings} time(s) without an answer; the morning starts at the next unlock",
        )
        effects += Effect.StopWakeAlarm
    }

    /**
     * Waking up: the wake alarm was dismissed ([reason] dismissed), or the phone was unlocked after it gave up (unlock).
     * The day ends if it has not (the alarm the morning after), the night lock ends, the wake time is journaled (minutes
     * after the alarm) and the morning starts.
     */
    private fun wakeUp(reason: String) {
        val a = s.wakeAlarm
        val key = (a.ringingFor ?: a.morningAtUnlock)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: alarmKey()
        val scheduled = a.scheduled?.takeIf { a.ringingFor != null } ?: alarmAt(key)
        if (DayRules.wakeAlarmEndsDay(day.date, now, zone)) endDay(DayEndReason.WAKE_ALARM, now)
        s.runs.filter { it.phase == RunPhase.NIGHT }.forEach { endNight(it, if (reason == "unlock") "unlock" else "wake") }
        if (day.key == key.toString() && day.woke == null) updateDay { it.copy(woke = now) }
        val after = Duration.between(scheduled, now).toMinutes().toInt()
        log(
            EntryType.WAKE, protocol = morningProtocol?.id, reason = reason, scheduled = scheduled, minutes = after,
            detail = listOfNotNull(
                if (after > 0) "$after min after the alarm" else "on time",
                a.rings.takeIf { it > 1 }?.let { "after $it rings" },
            ).joinToString(", "),
        )
        s = s.copy(wakeAlarm = WakeAlarmState(handledFor = key.toString()))
        effects += Effect.StopWakeAlarm
        file.protocols.filter { it.parsedTrigger == Trigger.WakeAlarm }.forEach { fire(it, "wake_alarm") }
    }

    /** A TEST ring was dismissed: the morning starts as a TEST preview; nothing else changes. */
    private fun wakeTest() {
        log(EntryType.WAKE, protocol = morningProtocol?.id, reason = "test", detail = "TEST ring dismissed", test = true)
        file.protocols.filter { it.parsedTrigger == Trigger.WakeAlarm }.forEach { fire(it, "test", test = true) }
    }

    /** At a day start: a ringing alarm or a waiting morning from before the new day [newKey] is over. */
    private fun expireAlarm(newKey: String) {
        val a = s.wakeAlarm
        var b = a
        if (a.morningAtUnlock != null && a.morningAtUnlock < newKey) b = b.copy(morningAtUnlock = null)
        if (a.ringingFor != null && a.ringingFor < newKey) {
            b = b.copy(ringingFor = null, scheduled = null, ringingSince = null, rings = 0, nextRingAt = null, handledFor = a.ringingFor)
            effects += Effect.StopWakeAlarm
        }
        if (b != a) s = s.copy(wakeAlarm = b)
    }

    companion object {
        private val WARNABLE = setOf(RunPhase.OPEN, RunPhase.CARD, RunPhase.REMINDER)

        /** Phases in which a run no longer holds its protocol against a new start. */
        private val REPLACEABLE = setOf(RunPhase.OPEN, RunPhase.REMINDER)

        /** Runs a reset's re-entry drops: waiting, left unfinished after their lock, or unconfirmed reminders. */
        private val DROPPED_AT_REENTRY = setOf(RunPhase.QUEUED, RunPhase.OPEN, RunPhase.REMINDER)

        /** The journal's `prayer` for Jummah entries. */
        const val JUMMAH_PRAYER = "jummah"

        /** The most repetitions and kilograms a set may log (a typo guard). */
        const val MAX_REPS = 1000
        const val MAX_WEIGHT = 1000.0

        /** The rest timer's range in seconds. */
        const val MIN_REST_S = 10
        const val MAX_REST_S = 600

        /** 80 for 80.0, 77.5 for 77.5. */
        fun kg(weight: Double): String = if (weight % 1.0 == 0.0) weight.toLong().toString() else weight.toString()

        /** A promise's line is cut to this many characters (it must fit the takeover). */
        const val MAX_PROMISE_CHARS = 120

        /** The longest promise delay accepted. */
        const val MAX_PROMISE_MIN = 240

        /** What a manual start's label (START STUDY's "what") is cut to. */
        const val MAX_LABEL_CHARS = 60

        /** Reasons that start an attention reset at once: a button, the reset's offer, a reset's re-entry. */
        private val STARTS_AT_ONCE = setOf("manual", "offer", "reenter")

        const val OFFER_NOTICE = "offer"
        const val JUMMAH_CARD_NOTICE = "jummah-card"

        /** "Time to wrap up" during a call. */
        const val WRAP_NOTICE = "wrap-up"

        /** A StopAudio reason is cut to this many characters. */
        private const val MAX_REASON_CHARS = 24

        fun headsUpNotice(prayer: Prayer) = "heads-up-${prayer.json}"

        fun prayerNotice(prayer: Prayer) = "prayer-${prayer.json}"

        fun runNotice(r: Run) = "run-${r.id}"
    }
}
