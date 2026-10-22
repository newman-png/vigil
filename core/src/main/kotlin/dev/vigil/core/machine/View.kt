package dev.vigil.core.machine

import dev.vigil.core.common.WallClock
import dev.vigil.core.day.DayRules
import dev.vigil.core.pool.Block
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolRules
import dev.vigil.core.protocol.Trigger
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.ParamSpec
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.Version
import dev.vigil.core.protocol.MAX_BLOCK_MIN
import dev.vigil.core.run.GymSet
import dev.vigil.core.run.JummahInfo
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import dev.vigil.core.run.SlotInfo
import dev.vigil.core.timeline.PlannedStart
import dev.vigil.core.timeline.StartSource
import dev.vigil.core.timeline.Timeline
import dev.vigil.core.times.DayTimes
import dev.vigil.core.times.MyTimes
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Random

/** Read-only rules over a state at `env.now`, shared by the reducer ([Txn]) and the queries ([Machine]). */
internal open class View(var s: MachineState, val env: MachineEnv) {
    val now: Instant get() = env.now
    val zone get() = env.zone
    val file get() = env.protocols
    val cfg get() = file.pool

    val nightEdge: LocalTime = WallClock.parseTime(env.protocols.day.nightEdge) ?: LocalTime.of(5, 0)

    /** The current day (a fresh one when nothing has started a day yet). */
    val day: DayState
        get() = s.day ?: DayState(DayRules.firstKey(now, zone, file.day).toString(), now, Machine.FIRST_START)

    val suspended: Boolean get() = s.suspendedUntil?.isAfter(now) == true

    /** The clock for run timers and locks: frozen while a call is connected. */
    val runNow: Instant get() = s.callSince?.takeIf { it.isBefore(now) } ?: now

    fun dayTimes(): DayTimes = DayTimes(day.date, zone, nightEdge, s.times.current, sleepTarget(), day.woke)

    /** The next day's times (MY TIMES as tomorrow will have them): its wake alarm and its early starts. */
    fun nextDayTimes(): DayTimes = DayTimes(day.date.plusDays(1), zone, nightEdge, s.times.upcoming)

    /** The file's sleep protocol (wind-down, night lock, sleep shifting), or null. */
    val sleepProtocol: Protocol? get() = file.protocols.firstOrNull { it.kind == ProtocolKind.SLEEP }

    /** The file's morning protocol (its params set the wake alarm's rings), or null. */
    val morningProtocol: Protocol? get() = file.protocols.firstOrNull { it.kind == ProtocolKind.MORNING }

    /** A sleep param: from the sleep protocol, else the kind's default. */
    fun sleepParam(name: String): Int = sleepProtocol?.intParam(name) ?: kindDefault(ProtocolKind.SLEEP, name)

    /** A wake alarm param (`ring_max_min`, `snooze_min`, `max_rings`): from the morning protocol, else the default. */
    fun alarmParam(name: String): Int = morningProtocol?.intParam(name) ?: kindDefault(ProtocolKind.MORNING, name)

    private fun kindDefault(kind: ProtocolKind, name: String): Int = (kind.params[name] as? ParamSpec.IntRange)?.default ?: 0

    /** Minutes after the night edge: orders a night's times (23:00 before 00:30). */
    fun nightMinutes(t: LocalTime): Int = Math.floorMod(t.hour * 60 + t.minute - (nightEdge.hour * 60 + nightEdge.minute), 1440)

    /** The local time [m] minutes after the night edge. */
    fun fromNightMinutes(m: Int): LocalTime =
        LocalTime.of(0, 0).plusMinutes((Math.floorMod(m, 1440) + nightEdge.hour * 60 + nightEdge.minute).toLong())

    /**
     * Tonight's target bedtime (sleep shifting): the saved target, kept between MY TIMES bedtime_goal and bedtime_start
     * (never later than the start); the start before the first night. Null when the file has no sleep protocol (bedtime
     * is then bedtime_goal).
     */
    fun sleepTarget(): LocalTime? {
        if (sleepProtocol == null) return null
        val (goal, start) = bedtimeRange(s.times.current)
        return clampTarget(nightMinutes(WallClock.parseTime(s.sleep.target) ?: start), goal, start)
    }

    /** MY TIMES bedtime_goal and bedtime_start (23:00 and 00:30 when unreadable). */
    fun bedtimeRange(times: MyTimes): Pair<LocalTime, LocalTime> =
        (WallClock.parseTime(times.bedtimeGoal) ?: LocalTime.of(23, 0)) to (WallClock.parseTime(times.bedtimeStart) ?: LocalTime.of(0, 30))

    /** [minutes] (after the night edge) kept from [goal] to [start]: never later than the start, never before the goal. */
    fun clampTarget(minutes: Int, goal: LocalTime, start: LocalTime): LocalTime =
        fromNightMinutes(minOf(nightMinutes(start), maxOf(nightMinutes(goal), minutes)))

    /** VIGIL rings a wake alarm when the file has a sleep protocol or a protocol started by `event:wake_alarm`. */
    val alarmOn: Boolean get() = file.protocols.any { it.kind == ProtocolKind.SLEEP || it.parsedTrigger == Trigger.WakeAlarm }

    /**
     * The day whose wake alarm comes next: today's own (the day began the night before its wake time, and its alarm has
     * not been dealt with), else tomorrow's (else the day after, if tomorrow's was already dealt with).
     */
    fun alarmKey(): LocalDate {
        val handled = s.wakeAlarm.handledFor
        if (dayTimes().wake.isAfter(day.startedAt) && handled != day.key) return day.date
        val next = day.date.plusDays(1)
        return if (handled == next.toString()) next.plusDays(1) else next
    }

    /** When the wake alarm of day [key] is due: that day's MY TIMES wake (tomorrow's with tomorrow's times). */
    fun alarmAt(key: LocalDate): Instant =
        if (key == day.date) dayTimes().wake else DayTimes(key, zone, nightEdge, s.times.upcoming).wake

    /** When the wake alarm rings next: its due time, or a ring again after an unanswered one; null while it rings. */
    fun wakeAlarmAt(): Instant? {
        if (!alarmOn) return null
        val a = s.wakeAlarm
        if (a.ringingFor != null) return a.nextRingAt
        return alarmAt(alarmKey())
    }

    /** The night lock run while it holds. */
    fun nightRun(): Run? = s.runs.firstOrNull { it.phase == RunPhase.NIGHT && (it.lockEndsAt == null || now.isBefore(it.lockEndsAt)) }

    /** An Isha prayer still open and not confirmed (GOING TO SLEEP would miss it), or null. */
    fun ishaToConfirm(): Run? = s.runs.firstOrNull { it.prayer == Prayer.ISHA && !it.test && !it.confirmed }

    /** Today's to-dos (written in last night's wind-down). */
    fun todosToday(): List<Todo> = s.todos[day.key] ?: emptyList()

    /** The file's walk protocol (the walking switch, the commute rows), or null. */
    val walkProtocol: Protocol? get() = file.protocols.firstOrNull { it.kind == ProtocolKind.WALK }

    /** The file's nudge protocol (wellness nudges), or null. */
    val nudgeProtocol: Protocol? get() = file.protocols.firstOrNull { it.kind == ProtocolKind.NUDGE }

    /** A walk param: from the walk protocol, else the kind's default. */
    fun walkParam(name: String): Int = walkProtocol?.intParam(name) ?: kindDefault(ProtocolKind.WALK, name)

    /**
     * When the walking switch turns itself off, or null while it is off: `still_end_min` after the last step (never
     * counted from before the walk began) when the phone counts steps, else [Toggles.walkingUntil].
     */
    fun walkEnd(): Instant? {
        val t = s.toggles
        val since = t.walkingSince?.takeIf { t.walking } ?: return null
        if (s.steps.available) {
            val last = maxOf(since, s.steps.lastStepAt ?: since)
            return last.plus(Duration.ofMinutes(walkParam("still_end_min").toLong()))
        }
        return t.walkingUntil ?: since.plus(Duration.ofMinutes(walkParam("manual_max_min").toLong()))
    }

    /** True when call run [r]'s DONE may be tapped: after a call of `min_call_min` since its card appeared; the short version (a message) at once. */
    fun callDone(r: Run): Boolean {
        val c = r.call ?: return true
        if (r.version != Version.FULL) return true
        val p = protocol(r) ?: return true
        return c.callS >= p.intParam("min_call_min") * 60L
    }

    /** When call run [r]'s card comes back (once, `return_after_min` after it appeared, stage 1, nothing done), or null. */
    fun callReturnAt(r: Run): Instant? {
        val c = r.call ?: return null
        if (r.stage != 1 || r.phase != RunPhase.ACTIVE || r.asking || r.returned || r.confirmed) return null
        val p = protocol(r) ?: return null
        return c.cardAt?.plus(Duration.ofMinutes(p.intParam("return_after_min").toLong()))
    }

    /**
     * Today's nudge span: from MY TIMES wake + `after_wake_min` to the planned wind-down (the sleep protocol's `time:`
     * start, else bedtime - 60).
     */
    fun nudgeSpan(p: Protocol): Pair<Instant, Instant> {
        val dt = dayTimes()
        val start = dt.wake.plus(Duration.ofMinutes(p.intParam("after_wake_min").toLong()))
        val end = (sleepProtocol?.parsedTrigger as? Trigger.Time)?.let { dt.resolve(it.expr) } ?: dt.bedtime.minus(Duration.ofMinutes(60))
        return start to end
    }

    /**
     * Today's timed nudges, in order: when and which line. Pseudo-random but fixed by the day key (a restart never
     * reshuffles them): `per_day` (fewer when the span is too short) at least `min_gap_min` apart inside [nudgeSpan],
     * the lines of `nudges` in turn from a random one (the first step's text when there are none).
     */
    fun nudgeTimes(p: Protocol): List<Pair<Instant, String>> {
        val (start, end) = nudgeSpan(p)
        val total = Duration.between(start, end).toMinutes()
        if (total <= 0) return emptyList()
        val gap = p.intParam("min_gap_min").toLong()
        var n = p.intParam("per_day")
        while (n > 0 && (n - 1) * gap > total) n--
        if (n <= 0) return emptyList()
        val free = total - (n - 1) * gap
        val random = Random("${day.key}/${p.id}".hashCode().toLong())
        val offsets = List(n) { (random.nextDouble() * (free + 1)).toLong().coerceAtMost(free) }.sorted()
        val lines = p.textListParam("nudges").ifEmpty { listOf(p.steps.first().text) }
        val first = random.nextInt(lines.size)
        return offsets.mapIndexed { i, o -> start.plus(Duration.ofMinutes(o + i * gap)) to lines[(first + i) % lines.size] }
    }

    /**
     * When the movement nudge is due: `still_min` after the last step (or the last nudge, or the span's start), once the
     * phone has been in use for [Machine.IN_USE]. Null without a step counter or `move_nudges`, while the phone is not in
     * use, or when that falls after the span.
     */
    fun moveNudgeAt(p: Protocol): Instant? {
        if (!s.steps.available || p.textListParam("move_nudges").isEmpty()) return null
        val unlocked = s.unlockedSince?.takeIf { s.screenOn && s.screenUnlocked } ?: return null
        val (start, end) = nudgeSpan(p)
        val from = listOfNotNull(start, s.steps.lastStepAt, day.moveFrom).max()
        val at = maxOf(from.plus(Duration.ofMinutes(p.intParam("still_min").toLong())), unlocked.plus(Machine.IN_USE))
        return at.takeIf { it.isBefore(end) }
    }

    /**
     * Why a nudge cannot show now (it is then skipped, not queued), or null: VIGIL suspended, QUIET, a call, friends,
     * walking, the night lock, a class, another protocol running (on the screen, waiting for it, or asking), or the
     * phone not in use (screen off or locked: a nudge is for a break from the phone).
     */
    fun nudgeBlocked(): String? = when {
        suspended -> "suspended"
        s.toggles.quiet -> "quiet"
        s.callSince != null -> "call"
        s.toggles.friends -> "friends"
        s.toggles.walking -> "walking"
        nightRun() != null -> "night_lock"
        dayTimes().classAt(now) != null -> "class"
        s.runs.any { it.phase in BUSY || asking(it) } -> "busy"
        !s.screenOn || !s.screenUnlocked -> "screen_off"
        else -> null
    }

    /** The latest moment the current day can last. */
    fun fallback(): Instant = DayRules.fallbackEnd(day.date, file.day, zone)

    fun run(id: String): Run? = s.runs.firstOrNull { it.id == id }

    fun protocol(r: Run): Protocol? = file.find(r.protocol)

    /**
     * The steps a run shows: its version on its weekday (the full steps if that version vanished), without the steps
     * marked `skip_on_class_days` on a class or commute day; a study block's `rounds` step becomes its work rounds and
     * breaks, an attention reset's timer without `min` lasts the ramp length, a workout without `min` the planned minutes.
     */
    fun stepsOf(r: Run): List<Step> = shaped(r).map { it.first }

    /** Which study round (or break after it) step [index] of [r] is, or null for any other step. */
    fun roundOf(r: Run, index: Int): Round? = shaped(r).getOrNull(index)?.second

    private fun shaped(r: Run): List<Pair<Step, Round?>> {
        val p = protocol(r) ?: return emptyList()
        val version = p.stepsFor(r.version, r.weekday) ?: p.stepsFor(Version.FULL, r.weekday) ?: p.steps
        val steps = if (r.classDay) version.filterNot { it.skipOnClassDays } else version
        val out = mutableListOf<Pair<Step, Round?>>()
        for (st in steps) {
            when {
                st.type == StepType.ROUNDS -> out += rounds(st, studyBlock(r, p))
                st.type == StepType.TIMER && st.min == null && r.kind == ProtocolKind.ATTENTION ->
                    out += st.copy(min = r.lengthMin ?: attentionMinutes(p)) to null
                st.type == StepType.WORKOUT && st.min == null -> out += st.copy(min = r.lengthMin ?: Machine.DEFAULT_WORKOUT_MIN) to null
                else -> out += st to null
            }
        }
        return out
    }

    /**
     * A `rounds` step filling [block] minutes: work rounds of its `min` minutes with its `break_min` breaks between them,
     * the last round cut to fit. When less than a break and [Machine.MIN_ROUND] minutes would be left after a round, that
     * round takes them instead (no break at the end, no tiny last round). Each becomes a timer step.
     */
    private fun rounds(st: Step, block: Int): List<Pair<Step, Round?>> {
        val work = (st.min ?: return emptyList()).coerceAtLeast(1)
        val rest = (st.breakMin ?: 0).coerceAtLeast(0)
        val lengths = mutableListOf<Int>()
        var left = block
        while (left > 0) {
            var w = minOf(work, left)
            left -= w
            if (left <= rest + Machine.MIN_ROUND) {
                w += left
                left = 0
            }
            lengths += w
            if (left > 0) left -= rest
        }
        val out = mutableListOf<Pair<Step, Round?>>()
        lengths.forEachIndexed { i, w ->
            out += Step(st.text, StepType.TIMER, min = w, chime = st.chime) to Round(i + 1, lengths.size, false)
            if (i < lengths.lastIndex && rest > 0) {
                out += Step(st.breakText ?: DEFAULT_BREAK_TEXT, StepType.TIMER, min = rest, chime = st.chime) to Round(i + 1, lengths.size, true)
            }
        }
        return out
    }

    /** A study run's block length: fixed when it fired, else the protocol's `default_min`. */
    fun studyBlock(r: Run, p: Protocol): Int = r.lengthMin ?: p.intParam("default_min")

    /**
     * The attention reset's length today: `start_min` on the first `ramp_every_days` days it was completed in full, then
     * one minute more every `ramp_every_days` such days, up to `max_min`.
     */
    fun attentionMinutes(p: Protocol): Int {
        val every = p.intParam("ramp_every_days").coerceAtLeast(1)
        return minOf(p.intParam("max_min"), p.intParam("start_min") + s.attention.days / every).coerceAtLeast(1)
    }

    /**
     * The length a run of [p] gets when it fires: a study block from its MY TIMES row (minutes, or start to end) or the
     * manual [minutes], else `default_min`; the attention reset's ramp; a gym session's planned minutes from its row.
     */
    fun lengthFor(p: Protocol, slot: SlotInfo?, minutes: Int?): Int? {
        val row = slot?.minutes ?: slot?.end?.let { Duration.between(slot.start, it).toMinutes().toInt() }
        return when (p.kind) {
            ProtocolKind.STUDY -> (minutes ?: row ?: p.intParam("default_min")).coerceIn(1, MAX_BLOCK_MIN)
            ProtocolKind.ATTENTION -> attentionMinutes(p)
            ProtocolKind.GYM -> row?.coerceIn(1, MAX_BLOCK_MIN)
            else -> null
        }
    }

    /** The minutes of work in [r]'s current steps (the rounds, or plain timers that are not breaks), for its study entry. */
    fun workMinutes(r: Run): Int = shaped(r).filter { (st, round) -> st.type == StepType.TIMER && round?.isBreak != true }.sumOf { it.first.min ?: 0 }

    /** The whole minutes of study rounds and breaks [r] has been through (the current one counted so far). */
    fun roundMinutesSpent(r: Run): Int {
        val shaped = shaped(r)
        var spent = shaped.take(r.step).filter { it.second != null }.sumOf { it.first.min ?: 0 }
        if (shaped.getOrNull(r.step)?.second != null) {
            r.stepStartedAt?.let { spent += Duration.between(it, runNow).toMinutes().toInt().coerceAtLeast(0) }
        }
        return spent
    }

    /** When the workout step [st] of [r] finishes by itself (forgotten FINISH): its planned end + [Machine.WORKOUT_GRACE]. */
    fun workoutEnd(r: Run, st: Step): Instant? =
        r.stepStartedAt?.plus(Duration.ofMinutes((st.min ?: Machine.DEFAULT_WORKOUT_MIN).toLong()))?.plus(Machine.WORKOUT_GRACE)

    /** The workout of gym run [r] on its workout step [st], as the takeover shows it. */
    fun workoutView(r: Run, st: Step): WorkoutView {
        val p = protocol(r)
        val sets = r.gym?.sets ?: emptyList()
        val names = mutableListOf<String>()
        for (name in (p?.textListParam("exercises") ?: emptyList()) + s.gym.last.keys + sets.map { it.exercise }) {
            if (names.none { it.equals(name, ignoreCase = true) }) names += name
        }
        val rest = p?.intParam("rest_s") ?: 90
        val exercises = names.map { name ->
            val mine = sets.filter { it.exercise.equals(name, ignoreCase = true) }
            val prefill = mine.lastOrNull() ?: s.gym.last[name]
            ExerciseView(name, prefill?.reps, prefill?.weight, s.gym.restS[name] ?: rest, mine, s.gym.best[name])
        }
        val planned = st.min ?: Machine.DEFAULT_WORKOUT_MIN
        return WorkoutView(
            exercises = exercises,
            plannedMin = planned,
            plannedEnd = r.stepStartedAt?.plus(Duration.ofMinutes(planned.toLong()))?.plus(callShift(r)),
            restUntil = r.gym?.restUntil,
            lastExercise = sets.lastOrNull()?.exercise,
        )
    }

    /** The stage a protocol fires at now. */
    fun stageOf(p: Protocol): Int = if (pinned(p)) 1 else (s.stages[p.id]?.stage ?: 1)

    /**
     * Stays at stage 1: `stages: false`, and always Jummah and the promise (their rules have no lighter stages), the
     * switches (walk, friends) and the nudges (their outcomes count for nothing).
     */
    fun pinned(p: Protocol): Boolean = !p.stages || p.kind == ProtocolKind.JUMMAH || p.kind == ProtocolKind.PROMISE || p.kind in NEUTRAL

    /** True while the run's lock holds (active, a lock type, before its end on the run clock). */
    fun lockActive(r: Run): Boolean =
        r.phase == RunPhase.ACTIVE && r.lock != LockType.NONE && (r.lockEndsAt == null || runNow.isBefore(r.lockEndsAt))

    /**
     * The run that owns the screen: an active run with a running screen lock, else a returned prayer takeover, else an
     * asking takeover (I'M AT UNI, a promise that came due), else the night lock (a prayer at night takes the screen
     * from it, and it comes back after).
     */
    fun screenOwner(): Run? =
        s.runs.firstOrNull { it.lock == LockType.SCREEN && lockActive(it) } ?: s.runs.firstOrNull { it.phase == RunPhase.RETURNED }
            ?: s.runs.firstOrNull { asking(it) } ?: nightRun()?.takeIf { it.lock == LockType.SCREEN }

    /** The screen owner that holds the screen against other runs: an asking takeover gives way to any screen lock. */
    fun screenHolder(): Run? = screenOwner()?.takeIf { !asking(it) }

    /** True while [r]'s asking takeover is up (see [Run.asking]). */
    fun asking(r: Run): Boolean = r.asking && (r.phase == RunPhase.ACTIVE || r.phase == RunPhase.OPEN)

    /** The file's Jummah protocol, or null. */
    val jummahProtocol: Protocol? get() = file.protocols.firstOrNull { it.kind == ProtocolKind.JUMMAH }

    /** The file's reset protocol (the home RESET button, the offer after a missed Jummah), or null. */
    val resetProtocol: Protocol? get() = file.protocols.firstOrNull { it.kind == ProtocolKind.RESET }

    /** The file's promise protocol (LATER), or null. */
    val promiseProtocol: Protocol? get() = file.protocols.firstOrNull { it.kind == ProtocolKind.PROMISE }

    /** Today's Jummah times when today is a Friday with a Jummah protocol, else null. */
    fun jummahToday(): JummahInfo? = jummahProtocol?.let { Timeline.jummah(it, dayTimes()) }

    /** Friday: Jummah replaces the Dhuhr protocol (its adhan starts no run, no heads-up) unless Jummah was missed. */
    fun dhuhrReplaced(prayer: Prayer): Boolean =
        prayer == Prayer.DHUHR && jummahToday() != null && day.jummah != DayState.JUMMAH_MISSED

    /**
     * What re-entering the day (a reset's last step) does now: an open prayer comes first; else the next planned start
     * starts now when it is due within [withinMin] minutes (a prayer's adhan cannot be started early); else it is only
     * named.
     */
    fun reentryPlan(withinMin: Int): Reentry {
        val prayer = s.runs.filter { it.prayer != null && !it.test && !it.confirmed }.minByOrNull { it.firedAt }
        if (prayer != null) return Reentry.Prayer(prayer)
        val next = plan().firstOrNull { it.at.isAfter(now) && it.key !in day.handled } ?: return Reentry.Nothing
        val soon = next.source != StartSource.ADHAN && !next.at.isAfter(now.plus(Duration.ofMinutes(withinMin.toLong())))
        return if (soon) Reentry.Start(next) else Reentry.Next(next)
    }

    /** [plan] as the takeover and the journal say it, e.g. "NEXT: ASR AT ~15:20". */
    fun reentryLine(plan: Reentry): String = when (plan) {
        is Reentry.Prayer -> "${protocol(plan.run)?.title ?: plan.run.prayer?.title} COMES FIRST: PRAY IT NOW."
        is Reentry.Start -> "${plan.item.title} STARTS NOW."
        is Reentry.Next -> "NEXT: ${plan.item.title} AT ${if (plan.item.estimated) "~" else ""}${WallClock.hhmm(plan.item.at, zone)}."
        Reentry.Nothing -> "NOTHING ELSE IS PLANNED TODAY."
    }

    /** How much a connected call has extended [r] so far (what the call's end will add to its timers and lock). */
    fun callShift(r: Run): Duration {
        val since = s.callSince ?: return Duration.ZERO
        if (r.phase != RunPhase.ACTIVE) return Duration.ZERO
        val base = maxOf(since, r.lockStartedAt ?: since)
        return Duration.between(base, now).takeIf { !it.isNegative } ?: Duration.ZERO
    }

    /** When the run's lock ends as the user sees it (extended live during a call). */
    fun lockEndShown(r: Run): Instant? = r.lockEndsAt?.plus(callShift(r))

    /** How long "I'm with friends" may stay on: the friends protocol's `cap_min`, else the kind's default (4 h). */
    fun friendsCapMin(): Int = file.protocols.firstOrNull { it.kind == ProtocolKind.FRIENDS }?.intParam("cap_min")
        ?: (ProtocolKind.FRIENDS.params["cap_min"] as ParamSpec.IntRange).default

    /** When the friends switch turns itself off, or null while it is off. */
    fun friendsEnd(): Instant? =
        s.toggles.friendsSince?.takeIf { s.toggles.friends }?.plus(Duration.ofMinutes(friendsCapMin().toLong()))

    /**
     * Wind-down start: the sleep protocol's run start today, or bedtime - 60 while the file has no sleep protocol. A
     * start in the night before the day's wake time is not today's wind-down: it would end the day in that same night
     * (skipping a whole day) and arm the idle rule for the whole day after.
     */
    fun windDownAt(): Instant? {
        val sleep = file.protocols.firstOrNull { it.kind == ProtocolKind.SLEEP }
        return if (sleep != null) startedToday(sleep.id) else dayTimes().bedtime.minus(Duration.ofMinutes(60))
    }

    /** When protocol [id]'s run first started today, counting only starts after the day's wake time (not the night before it). */
    fun startedToday(id: String): Instant? = day.started[id]?.takeIf { it.isAfter(dayTimes().wake) }

    /** When protocol [id]'s run last completed today, after the day's wake time. */
    fun completedToday(id: String): Instant? = day.completed[id]?.takeIf { it.isAfter(dayTimes().wake) }

    /** The day's planned starts (time, slot, prayers) between its start and its latest end (tomorrow's early ones too). */
    fun plan(): List<PlannedStart> =
        Timeline.plan(file, dayTimes(), s.prayers.lastAdhan, day.adhans, day.startedAt, fallback(), nextDayTimes())

    /** Run locks, toggles and the night lock that block the pool, in design order. */
    fun lockBlocks(): List<Block> {
        val out = mutableListOf<Block>()
        // The switches block the pool with their own reason below; a nudge never blocks it (it would end a session).
        val locking = s.runs.filter { (it.lock == LockType.SCREEN || it.lock == LockType.POOL) && lockActive(it) && it.kind !in NEUTRAL } +
            s.runs.filter { it.phase == RunPhase.RETURNED }
        if (locking.isNotEmpty()) {
            val ends = locking.map { if (it.phase == RunPhase.RETURNED) windowEnd(it) else lockEndShown(it) }
            val until = if (ends.any { it == null }) null else ends.filterNotNull().maxOrNull()
            out += Block(BlockReason.RUN_LOCK, until, locking.joinToString(", ") { protocol(it)?.title ?: it.protocol })
        }
        // Friends: refused with no wait ("YOU'RE WITH FRIENDS"); the switch goes off when the user says so.
        if (s.toggles.friends) out += Block(BlockReason.FRIENDS, null)
        if (s.toggles.walking) out += Block(BlockReason.WALKING, null)
        // Until the wake alarm is answered (its latest end is a safety limit, not the usual end).
        if (nightRun() != null) out += Block(BlockReason.NIGHT_LOCK, null)
        return out
    }

    fun decision(isPool: Boolean): PoolDecision {
        if (!isPool) return PoolDecision.Allowed
        return PoolRules.decide(cfg, s.pool, now, lockBlocks(), suspended)
    }

    /** When a prayer's window closes: Isha at the day's end, the others at the next prayer's (estimated) adhan. */
    fun windowEnd(r: Run): Instant? = if (r.prayer == Prayer.ISHA) fallback() else r.windowEndsAt

    /** When the takeover comes back for an unconfirmed prayer (window end - window_warn_min); null without an estimate. */
    fun warnAt(r: Run, p: Protocol): Instant? {
        val end = windowEnd(r) ?: return null
        if (r.prayer != Prayer.ISHA && !r.windowEstimated) return null
        val lead = p.intParam("window_warn_min")
        if (lead <= 0) return null
        return end.minus(Duration.ofMinutes(lead.toLong()))
    }

    /** The next expected adhan after [last]: whole days later, at least 12 h after it and not more than 1 h in the past. */
    fun nextEstimate(last: Instant): Instant =
        Timeline.estimate(last, maxOf(last.plus(Duration.ofHours(12)), now.minus(Duration.ofHours(1))))

    /** When a timer step ends: its minutes from the step's start, or the lock's end (at once when there is no timed lock). */
    fun timerEnd(r: Run, st: Step): Instant? =
        if (st.min != null) r.stepStartedAt?.plus(Duration.ofMinutes(st.min.toLong())) else r.lockEndsAt ?: r.stepStartedAt

    /** The moment the done step unlocks for a forced (stage 1) active run, else null. */
    fun doneLockedUntil(r: Run): Instant? =
        r.doneAfterAt?.takeIf { r.stage == 1 && r.phase == RunPhase.ACTIVE && runNow.isBefore(it) }

    /** When the run's current step can be tapped (null = now), for the takeover. */
    fun stepLockedUntil(r: Run, st: Step): Instant? {
        if (r.stage != 1 || r.phase != RunPhase.ACTIVE) return null
        val ext = callShift(r)
        return when (st.type) {
            StepType.CHECK -> st.min?.let { m -> r.stepStartedAt?.plus(Duration.ofMinutes(m.toLong())) }?.takeIf { runNow.isBefore(it) }?.plus(ext)
            StepType.TIMER -> timerEnd(r, st)?.plus(ext)
            StepType.TEXT, StepType.ROUNDS, StepType.WORKOUT -> null
            StepType.DONE -> doneLockedUntil(r)?.plus(ext)
        }
    }

    /** Every moment the app should be awake for: the earliest after now, or null. */
    fun nextWake(): Instant? {
        val c = mutableListOf<Instant>()
        c += DayRules.nextCheck(day.date, zone, file.day, s.screenOffSince.takeIf { !s.screenOn }, windDownAt())
        s.suspendedUntil?.let { c += it }
        s.callSince?.let { c += it.plus(Machine.MAX_CALL_PAUSE) }
        friendsEnd()?.let { c += it }
        walkEnd()?.let { c += it }
        s.callWrapAt?.let { c += it }
        nudgeProtocol?.let { p ->
            nudgeTimes(p).getOrNull(day.nudgesDue)?.let { c += it.first }
            moveNudgeAt(p)?.let { c += it }
        }
        if (alarmOn) {
            s.wakeAlarm.ringingSince?.let { c += it.plus(Duration.ofMinutes(alarmParam("ring_max_min").toLong())) }
            wakeAlarmAt()?.let { c += it }
        }
        val plan = plan()
        plan.filter { it.source != StartSource.ADHAN && it.key !in day.handled }.forEach { c += it.at }
        for (d in day.deferred) {
            c += d.due
            c += d.due.plus(Machine.DEFER_LIMIT)
            dayTimes().classBlockEnd(now)?.let { c += it }
        }
        if (!suspended) {
            for (prayer in Prayer.entries) {
                val p = file.prayerProtocol(prayer) ?: continue
                if (stageOf(p) >= 3 || dhuhrReplaced(prayer)) continue
                val lead = p.intParam("heads_up_min")
                if (lead <= 0) continue
                val last = s.prayers.lastAdhan[prayer] ?: continue
                val est = nextEstimate(last)
                if (s.prayers.headsUpFor[prayer] != est) c += est.minus(Duration.ofMinutes(lead.toLong()))
            }
        }
        for (r in s.runs) {
            val p = protocol(r) ?: continue
            if (r.prayer != null && !r.confirmed) {
                windowEnd(r)?.let { c += it }
                if (!r.warned) warnAt(r, p)?.let { c += it }
            }
            if (r.phase == RunPhase.CARD) r.classEndsAt?.let { c += it }
            if (r.phase == RunPhase.NIGHT) r.lockEndsAt?.let { c += it }
            r.jummah?.let { j ->
                c += listOfNotNull(j.arriveAt, j.lockAt, j.missedAt, j.cardAt?.takeIf { !j.cardSent })
                j.askedAt?.let { c += it.plus(Duration.ofMinutes(p.intParam("arrive_repeat_min").toLong())) }
            }
            if (r.phase == RunPhase.WAITING) r.promise?.let { c += it.dueAt }
            r.call?.let { c += it.dueAt }
            callReturnAt(r)?.let { c += it }
            if (r.test && r.kind in SWITCHES) c += r.firedAt.plus(Machine.TEST_SWITCH)
            if (r.phase == RunPhase.ACTIVE && s.callSince == null) {
                r.lockEndsAt?.let { c += it }
                val st = stepsOf(r).getOrNull(r.step)
                if (st?.type == StepType.TIMER) timerEnd(r, st)?.let { c += it }
                if (st?.type == StepType.WORKOUT) workoutEnd(r, st)?.let { c += it }
            }
        }
        val session = s.pool.session
        if (session != null) {
            val inSince = session.inSince
            if (inSince == null) {
                session.leftAt?.let { c += it.plusSeconds(cfg.sessionGapS.toLong()) }
            } else {
                // Heavy use (one long session), and today's budget running out.
                c += inSince.plusSeconds(cfg.heavyUse.continuousMin * 60L - session.usedSeconds)
                c += inSince.plusSeconds(PoolRules.budgetSeconds(cfg) - s.pool.usedSeconds - session.usedSeconds)
            }
        }
        return c.filter { it.isAfter(now) }.minOrNull()
    }
}

/** Kinds whose outcomes count for nothing (no stages, no misses in a row) and whose runs never block the pool as a run lock. */
internal val NEUTRAL = setOf(ProtocolKind.WALK, ProtocolKind.FRIENDS, ProtocolKind.NUDGE)

/** The kinds started by a home switch: their run lasts while the switch is on. */
internal val SWITCHES = setOf(ProtocolKind.WALK, ProtocolKind.FRIENDS)

/** Phases of a run that is going on (a nudge waits for none of them to be left). */
internal val BUSY = setOf(RunPhase.ACTIVE, RunPhase.QUEUED, RunPhase.PAUSED, RunPhase.RETURNED, RunPhase.CARD, RunPhase.NIGHT)

/** The text of a study break when its rounds step has no `break_text`. */
internal const val DEFAULT_BREAK_TEXT = "Break: stand up, drink water, look out a window. The phone stays locked."

/** What re-entering the day does ([View.reentryPlan]). */
internal sealed interface Reentry {
    /** An open, unconfirmed prayer comes first: its takeover starts. */
    data class Prayer(val run: Run) : Reentry

    /** The next planned protocol starts now. */
    data class Start(val item: PlannedStart) : Reentry

    /** The next planned start, too far away to start now (or a prayer's adhan). */
    data class Next(val item: PlannedStart) : Reentry

    data object Nothing : Reentry
}
