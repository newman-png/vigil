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
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.protocol.Trigger
import dev.vigil.core.protocol.Version
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
    ) {
        entries += Entry(
            ts = WallClock.stamp(at, zone), type = type, protocol = protocol, run = run, step = step, version = version,
            stage = stage, app = app, minutes = minutes, seconds = seconds, reason = reason, detail = detail, text = text,
            day = day, prayer = prayer?.json, until = until?.let { WallClock.stamp(it, zone) },
            scheduled = scheduled?.let { WallClock.stamp(it, zone) }, test = test.takeIf { it },
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
    ) = log(
        type, at = at, protocol = r.protocol, run = r.id, step = step, version = r.version.label, stage = r.stage,
        minutes = minutes, reason = reason, detail = detail, text = text, prayer = r.prayer, until = until, scheduled = scheduled,
        test = r.test,
    )

    private fun hhmm(at: Instant) = WallClock.hhmm(at, zone)

    // ------------------------------------------------------------------ state helpers

    private fun put(r: Run) {
        s = s.copy(runs = s.runs.map { if (it.id == r.id) r else it })
    }

    private fun add(r: Run) {
        s = s.copy(runs = s.runs + r)
    }

    private fun remove(id: String) {
        s = s.copy(runs = s.runs.filterNot { it.id == id })
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
        settleRuns()
        settleDay()
        prunePool()
        settlePool()
        settleHeadsUps()
        fireDue()
        settleRuns()
    }

    private fun ensureDay() {
        if (s.day != null) return
        val key = DayRules.firstKey(now, zone, file.day).toString()
        s = s.copy(day = DayState(key, now, Machine.FIRST_START), times = s.times.atDayStart())
        log(EntryType.DAY_START, day = key, reason = Machine.FIRST_START)
    }

    private fun expireSuspension() {
        val until = s.suspendedUntil ?: return
        if (now.isBefore(until)) return
        s = s.copy(suspendedUntil = null)
        log(EntryType.KILL_SWITCH, at = until, reason = "resumed", detail = "the suspension ended")
    }

    private fun settleDay() {
        repeat(Machine.MAX_CATCH_UP_DAYS) {
            val end = DayRules.dueEnd(day.date, now, zone, file.day, s.screenOffSince.takeIf { !s.screenOn }, windDownAt()) ?: return
            endDay(end.reason, end.at)
        }
    }

    /** Ends the current day at [at]: self-check, runs that close with the day, counters reset, and the next day starts. */
    fun endDay(reason: DayEndReason, at: Instant) {
        val d = day
        selfCheck(d, at)
        closeRunsAtDayEnd(at)
        log(
            EntryType.DAY_END, at = at, day = d.key, reason = reason.json,
            detail = if (at != now) "noticed at ${hhmm(now)}" else null,
        )
        val key = DayRules.keyAfter(at, zone, d.date).toString()
        val applied = s.times.pending != null
        s = s.copy(day = DayState(key, at, reason.json), times = s.times.atDayStart(), pool = s.pool.newDay())
        log(
            EntryType.DAY_START, at = at, day = key, reason = reason.json,
            detail = if (applied) "the MY TIMES changes apply from today" else null,
        )
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
    }

    private fun closeRunsAtDayEnd(at: Instant) {
        for (r in s.runs.toList()) {
            when {
                r.test -> {
                    remove(r.id)
                    logRun(EntryType.DROPPED, r, at = at, reason = "day_end", detail = "test run")
                    cancelRunNotices(r)
                }
                r.prayer == Prayer.ISHA -> if (r.confirmed) complete(r) else missPrayer(r, "day_end", at)
                r.prayer != null -> Unit
                r.kind.survivesDayEnd -> Unit
                r.phase == RunPhase.REMINDER -> {
                    remove(r.id)
                    logRun(EntryType.DROPPED, r, at = at, reason = "day_end", detail = "reminder not confirmed")
                    cancelRunNotices(r)
                }
                else -> missRun(r, "day_end", at)
            }
        }
    }

    // ------------------------------------------------------------------ runs

    private fun settleRuns() {
        for (id in s.runs.map { it.id }) settleRun(id)
        promoteQueue()
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
            else -> Unit
        }
    }

    /** Timer steps that ran out, completion, and the lock's end. */
    private fun settleActive(start: Run, p: Protocol) {
        var r = start
        var guard = 0
        while (guard++ < 200) {
            val steps = stepsOf(r)
            if (r.step >= steps.size) {
                complete(r)
                return
            }
            val st = steps[r.step]
            if (st.type != StepType.TIMER) break
            val end = timerEnd(r, st) ?: break
            if (now.isBefore(end)) break
            r = stepDone(r, steps, auto = true, at = end) ?: return
        }
        val lockEnd = r.lockEndsAt
        if (lockEnd != null && !now.isBefore(lockEnd)) lockEnded(r, p, lockEnd)
    }

    /**
     * Marks the run's current step done. A following timer that runs "until the lock ends" is over at once when the
     * lock has already ended. Returns the updated run, or null when that finished it.
     */
    private fun stepDone(r: Run, steps: List<Step>, text: String? = null, auto: Boolean = false, at: Instant = now): Run? {
        val st = steps[r.step]
        logRun(EntryType.STEP_DONE, r, at = at, step = r.step, detail = st.text, text = text, reason = if (auto) "timer" else null)
        var next = r.copy(
            step = r.step + 1,
            stepStartedAt = at,
            texts = if (text != null) r.texts + (r.step.toString() to text) else r.texts,
        )
        if (st.type == StepType.DONE && !r.confirmed) {
            next = next.copy(confirmed = true)
            if (r.prayer != null) logConfirmed(next)
        }
        while (next.step < steps.size) {
            val following = steps[next.step]
            val lockOver = next.lockEndsAt == null || !runNow.isBefore(next.lockEndsAt)
            if (following.type != StepType.TIMER || following.min != null || !lockOver) break
            logRun(EntryType.STEP_DONE, next, at = at, step = next.step, detail = following.text, reason = "timer")
            next = next.copy(step = next.step + 1)
        }
        if (next.step >= steps.size) {
            complete(next)
            return null
        }
        put(next)
        return next
    }

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

    /** The run finished: journal, stage outcome, `after:` triggers, the queue moves on. */
    private fun complete(r: Run) {
        remove(r.id)
        logRun(EntryType.COMPLETED, r, minutes = WallClock.minutesCeil(r.firedAt, now), reason = if (r.clean) "clean" else "done")
        cancelRunNotices(r)
        if (!r.test) {
            updateDay { it.copy(completed = it.completed + (r.protocol to now)) }
            if (!r.bailed) recordOutcome(r, if (r.clean) RunOutcome.CLEAN else RunOutcome.DONE)
            if (!r.afterFired && !r.bailed) fireAfter(r)
        }
        promoteQueue()
    }

    private fun missRun(r: Run, why: String, at: Instant = now) {
        remove(r.id)
        logRun(EntryType.MISSED, r, at = at, reason = why)
        cancelRunNotices(r)
        if (!r.test && !r.bailed) recordOutcome(r, RunOutcome.MISSED)
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
    }

    /** Stage memory, stage changes, and the misses-in-a-row trigger. */
    private fun recordOutcome(r: Run, outcome: RunOutcome) {
        val p = protocol(r) ?: return
        val (mem, change) = Stages.record(s.stages[p.id] ?: StageMemory(), outcome, file.stages, pinned = !p.stages)
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
            val offers = file.protocols.filter { (it.parsedTrigger as? Trigger.Misses)?.count == n }
            if (offers.isNotEmpty()) {
                s = s.copy(missesInARow = 0)
                offers.forEach { fire(it, "misses:$n") }
            }
        } else {
            s = s.copy(missesInARow = 0)
        }
    }

    /** Starts the protocols that wait for [r] (`event:after:<id>`), once per run. */
    private fun fireAfter(r: Run) {
        run(r.id)?.let { put(it.copy(afterFired = true)) }
        if (r.test) return
        file.protocols.filter { (it.parsedTrigger as? Trigger.After)?.protocolId == r.protocol }.forEach { fire(it, "after:${r.protocol}") }
    }

    private fun newRun(p: Protocol, stage: Int, reason: String, planned: Instant?, slot: SlotInfo?, test: Boolean): Run {
        val id = "run-${s.nextRun}"
        s = s.copy(nextRun = s.nextRun + 1)
        return Run(
            id = id, protocol = p.id, kind = p.kind, stage = stage, phase = RunPhase.QUEUED, firedAt = now, reason = reason,
            weekday = WallClock.dayCode(day.date), lock = if (stage >= 3) LockType.NONE else p.lock.type,
            planned = planned, slot = slot, test = test,
        )
    }

    /** A trigger fired for [p]: start a run (or say why not). Returns the run, or null when nothing started. */
    private fun fire(p: Protocol, reason: String, planned: Instant? = null, slot: SlotInfo? = null, test: Boolean = false): Run? {
        if (suspended && !test) {
            log(EntryType.SKIPPED, protocol = p.id, reason = "suspended", scheduled = planned, detail = "VIGIL is suspended")
            return null
        }
        if (!test && s.runs.any { it.protocol == p.id && !it.test }) {
            log(EntryType.SKIPPED, protocol = p.id, reason = "already_running", scheduled = planned)
            return null
        }
        val r = newRun(p, if (test) 1 else stageOf(p), reason, planned, slot, test)
        place(r, p)
        return r
    }

    /** Puts a new run into play: stage 3 = one notification; a screen run waits while another owns the screen. */
    private fun place(r: Run, p: Protocol) {
        if (!r.test) s = s.copy(lastFired = s.lastFired + (p.id to now))
        val owner = screenOwner()
        when {
            r.stage >= 3 -> {
                add(r.copy(phase = RunPhase.REMINDER))
                logRun(EntryType.FIRED, r, reason = r.reason, scheduled = r.planned, detail = "stage 3: one notification")
                val text = if (r.prayer != null) "Time to pray. Tap CONFIRM when done." else currentStepText(r)
                val id = r.prayer?.let { prayerNotice(it) } ?: runNotice(r)
                effects += Effect.Notify(id, p.title, text, silent = s.toggles.quiet || r.silent, NotifyKind.REMINDER, r.id)
            }
            r.lock == LockType.SCREEN && owner != null -> {
                add(r.copy(phase = RunPhase.QUEUED))
                logRun(EntryType.FIRED, r, reason = r.reason, scheduled = r.planned, detail = "waits behind ${title(owner)}")
            }
            else -> {
                add(r)
                logRun(EntryType.FIRED, r, reason = r.reason, scheduled = r.planned)
                activate(r.id)
            }
        }
    }

    /** The run becomes active now: its lock, done lock and first step start. */
    private fun activate(id: String) {
        val r = run(id) ?: return
        val p = protocol(r) ?: return
        val lockEnd = p.lock.minutes?.takeIf { r.lock != LockType.NONE }?.let { now.plus(Duration.ofMinutes(it.toLong())) }
        val doneAfter = if (r.stage == 1) p.lock.doneAfterMin?.let { now.plus(Duration.ofMinutes(it.toLong())) } else null
        var a = r.copy(
            phase = RunPhase.ACTIVE, startedAt = r.startedAt ?: now, lockStartedAt = now, lockEndsAt = lockEnd,
            doneAfterAt = doneAfter, stepStartedAt = now,
        )
        val minimum = p.lock.doneAfterMin
        if (a.shortened && minimum != null && lockEnd != null) a = a.copy(lockEndsAt = minOf(lockEnd, now.plus(Duration.ofMinutes(minimum.toLong()))))
        put(a)
        if (!r.test && p.id !in day.started) updateDay { it.copy(started = it.started + (p.id to now)) }
        if (a.lock == LockType.SCREEN) {
            effects += Effect.ShowTakeover(a.id)
        } else {
            effects += Effect.Notify(runNotice(a), p.title, currentStepText(a), silent = true, NotifyKind.RUN, a.id)
        }
    }

    /** While nothing owns the screen, the next queued run (prayers first) starts. */
    private fun promoteQueue() {
        var guard = 0
        while (screenOwner() == null && guard++ < 50) {
            val queued = s.runs.filter { it.phase == RunPhase.QUEUED }
            val next = queued.firstOrNull { it.prayer != null } ?: queued.firstOrNull() ?: return
            activate(next.id)
        }
    }

    // ------------------------------------------------------------------ prayers

    private fun onAdhan(prayer: Prayer, manual: Boolean) {
        val last = s.prayers.lastAdhan[prayer]
        if (last != null && Duration.between(last, now) < Machine.ADHAN_DEDUPE) return
        s = s.copy(prayers = s.prayers.copy(lastAdhan = s.prayers.lastAdhan + (prayer to now)))
        updateDay { it.copy(adhans = it.adhans + (prayer to now)) }
        log(EntryType.ADHAN, prayer = prayer, reason = if (manual) "manual" else null)
        effects += Effect.CancelNotify(headsUpNotice(prayer))
        // The previous prayer's window ends with this adhan.
        for (r in s.runs.filter { it.prayer != null && !it.test }) {
            if (r.confirmed) complete(r) else missPrayer(r, "next_adhan", now)
        }
        val p = file.prayerProtocol(prayer) ?: return
        val (windowEnd, estimated) = estimateWindowEnd(prayer)
        val stage = stageOf(p)
        val base = newRun(p, stage, "adhan", null, null, false).copy(prayer = prayer, windowEndsAt = windowEnd, windowEstimated = estimated)
        if (suspended) {
            s = s.copy(lastFired = s.lastFired + (p.id to now))
            val open = base.copy(phase = RunPhase.OPEN, lock = LockType.NONE)
            add(open)
            logRun(EntryType.FIRED, open, reason = "adhan", detail = "VIGIL is suspended: no takeover; CONFIRM when prayed")
            notifyConfirm(open, p)
            return
        }
        val classEnd = if (stage <= 2) dayTimes().classBlockEnd(now) else null
        if (classEnd != null) {
            s = s.copy(lastFired = s.lastFired + (p.id to now))
            val card = base.copy(phase = RunPhase.CARD, classEndsAt = classEnd, silent = true)
            add(card)
            logRun(EntryType.FIRED, card, reason = "adhan", detail = "in class until ${hhmm(classEnd)}: silent card")
            logRun(EntryType.CLASS_CARD, card, until = classEnd, detail = "silent card; the takeover starts when the class ends")
            effects += Effect.Notify(
                prayerNotice(prayer), p.title, "Class now: pray in the first break or right after class.",
                silent = true, kind = NotifyKind.CLASS_CARD, runId = card.id,
            )
            return
        }
        place(base, p)
    }

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
        if (r.stage <= 2 && !suspended) {
            val inClass = dayTimes().classAt(now) != null
            put(r.copy(warned = true, returned = true, phase = RunPhase.RETURNED, classEndsAt = null, silent = inClass))
            effects += Effect.CancelNotify(prayerNotice(r.prayer!!))
            if (screenOwner()?.id == r.id) effects += Effect.ShowTakeover(r.id)
        } else {
            put(r.copy(warned = true))
            val left = end?.let { WallClock.minutesCeil(now, it) } ?: 0
            effects += Effect.Notify(
                prayerNotice(r.prayer!!), "${p.title}: $left MIN LEFT", "Pray, then tap CONFIRM. The window closes at ${end?.let { hhmm(it) }}.",
                silent = s.toggles.quiet, kind = NotifyKind.WARNING, runId = r.id,
            )
        }
    }

    private fun classOver(r: Run) {
        logRun(EntryType.CLASS_CARD, r, detail = "class over: the takeover starts")
        effects += Effect.CancelNotify(prayerNotice(r.prayer!!))
        put(r.copy(classEndsAt = null, silent = false, phase = RunPhase.QUEUED))
        if (screenOwner() == null) activate(r.id)
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
            if (stageOf(p) >= 3) continue
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
            if (item.at.isAfter(now) || Duration.between(item.at, now) > Machine.PLANNED_GRACE) continue
            val p = file.find(item.protocolId) ?: continue
            val reason = (p.parsedTrigger as? Trigger.Slot)?.let { "slot:${it.type.json}" } ?: "time"
            val r = fire(p, reason, item.at, item.slot?.let(::slotInfo))
            updateDay { it.copy(handled = it.handled + (item.key to if (r != null) "fired" else "skipped")) }
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
        val cool = PoolRules.cooldownSeconds(cfg, recent)
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
        when (val d = decision(true)) {
            is PoolDecision.Blocked -> {
                logBlocked(app, d)
                effects += Effect.PoolGate(app, d)
            }
            else -> startSession(app, w)
        }
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
            Input.WakeAlarm -> {
                if (DayRules.wakeAlarmEndsDay(day.date, now, zone)) endDay(DayEndReason.WAKE_ALARM, now)
                file.protocols.filter { it.parsedTrigger == Trigger.WakeAlarm }.forEach { fire(it, "wake_alarm") }
            }
            is Input.Toggle -> onToggle(input.name, input.on)
            is Input.Manual -> onManual(input.protocolId, input.test)
            is Input.StepDone -> onStepDone(input.runId, input.index, input.text)
            is Input.Confirm -> onConfirm(input.runId)
            is Input.NotOkay -> onNotOkay(input.runId)
            is Input.BailOut -> onBailOut(input.runId)
            is Input.CallActive -> onCall(input.on)
            is Input.Screen -> {
                if (!input.on) {
                    if (s.screenOn || s.screenOffSince == null) s = s.copy(screenOn = false, screenUnlocked = false, screenOffSince = now)
                    onForeground(null, false)
                } else {
                    s = s.copy(screenOn = true, screenUnlocked = input.unlocked, screenOffSince = null)
                }
            }
            is Input.Foreground -> onForeground(input.app, input.isPool)
            is Input.PoolWaitStarted -> onWaitStarted(input.app)
            is Input.PoolWaitDone -> onWaitDone(input.app)
            is Input.PoolWaitAbandoned -> onWaitAbandoned(input.app)
            Input.GoingToSleep -> endDay(DayEndReason.SLEEP_TAP, now)
            is Input.Suspend -> onSuspend(input.minutes)
            Input.Resume -> if (s.suspendedUntil != null) {
                s = s.copy(suspendedUntil = null)
                log(EntryType.KILL_SWITCH, reason = "resumed", detail = "ended early")
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
        }
    }

    private fun onToggle(name: ToggleName, on: Boolean) {
        val t = s.toggles
        when (name) {
            ToggleName.QUIET -> {
                if (t.quiet == on) return
                s = s.copy(toggles = t.copy(quiet = on))
                log(EntryType.QUIET, detail = if (on) "on" else "off")
                return
            }
            ToggleName.WALKING -> {
                if (t.walking == on) return
                s = s.copy(toggles = t.copy(walking = on, walkingSince = if (on) now else null))
            }
            ToggleName.FRIENDS -> {
                if (t.friends == on) return
                s = s.copy(toggles = t.copy(friends = on, friendsSince = if (on) now else null))
            }
        }
        log(EntryType.TOGGLE, reason = name.json, detail = if (on) "on" else "off")
        val started = file.protocols.filter { (it.parsedTrigger as? Trigger.Toggle)?.toggle == name }
        if (on) {
            started.forEach { fire(it, "toggle:${name.json}") }
        } else {
            started.forEach { p -> s.runs.filter { it.protocol == p.id }.forEach { complete(it) } }
        }
    }

    private fun onManual(id: String, test: Boolean) {
        val p = file.find(id) ?: return
        val prayer = (p.parsedTrigger as? Trigger.Adhan)?.prayer
        when {
            prayer != null && !test -> onAdhan(prayer, manual = true)
            prayer != null -> {
                val (end, estimated) = estimateWindowEnd(prayer)
                place(newRun(p, 1, "test", null, null, true).copy(prayer = prayer, windowEndsAt = end, windowEstimated = estimated), p)
            }
            else -> fire(p, if (test) "test" else "manual", test = test)
        }
    }

    private fun onStepDone(runId: String, index: Int, text: String?) {
        val r = run(runId) ?: return
        if (r.phase != RunPhase.ACTIVE && r.phase != RunPhase.RETURNED && r.phase != RunPhase.OPEN) return
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
            StepType.TEXT -> if (nonBlankLines(text) < (st.lines ?: 1)) return
            StepType.DONE -> if (forced && r.doneAfterAt != null && runNow.isBefore(r.doneAfterAt)) return
        }
        stepDone(r, steps, text = text?.trim())
    }

    private fun nonBlankLines(text: String?): Int = text?.lines()?.count { it.isNotBlank() } ?: 0

    private fun onConfirm(runId: String) {
        val r = run(runId) ?: return
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
        val moved = r.copy(version = next, step = 0, stepStartedAt = now, steppedDown = true)
        put(moved)
        logRun(EntryType.STEPPED_DOWN, moved, detail = if (next == Version.SINGLE) "minimum" else null)
    }

    private fun onBailOut(runId: String) {
        val r = run(runId) ?: return
        val p = protocol(r) ?: return
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
            s = s.copy(callSince = now)
            val paused = s.runs.filter { it.phase == RunPhase.ACTIVE }.map { it.protocol }
            log(EntryType.CALL_PAUSE, detail = paused.joinToString(", ").ifEmpty { null })
            return
        }
        val since = s.callSince ?: return
        for (r in s.runs.filter { it.phase == RunPhase.ACTIVE }) {
            val base = maxOf(since, r.lockStartedAt ?: since)
            val shift = Duration.between(base, now).takeIf { !it.isNegative } ?: Duration.ZERO
            val stepStart = r.stepStartedAt?.let { if (it.isBefore(base)) it.plus(shift) else now }
            put(r.copy(lockEndsAt = r.lockEndsAt?.plus(shift), doneAfterAt = r.doneAfterAt?.plus(shift), stepStartedAt = stepStart))
        }
        s = s.copy(callSince = null)
        val length = Duration.between(since, now).seconds.coerceAtLeast(0)
        log(EntryType.CALL_RESUME, minutes = WallClock.minutesCeil(length), seconds = length)
    }

    private fun onSuspend(minutes: Int) {
        val until = now.plus(Duration.ofMinutes(minutes.coerceAtLeast(1).toLong()))
        s = s.copy(suspendedUntil = until)
        log(EntryType.KILL_SWITCH, minutes = minutes, until = until, reason = "suspended")
        for (r in s.runs.toList()) {
            val p = protocol(r)
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
        updatePool { it.copy(wait = null) }
    }

    companion object {
        private val WARNABLE = setOf(RunPhase.OPEN, RunPhase.CARD, RunPhase.REMINDER)

        fun headsUpNotice(prayer: Prayer) = "heads-up-${prayer.json}"

        fun prayerNotice(prayer: Prayer) = "prayer-${prayer.json}"

        fun runNotice(r: Run) = "run-${r.id}"
    }
}
