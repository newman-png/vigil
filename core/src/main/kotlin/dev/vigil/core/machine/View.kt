package dev.vigil.core.machine

import dev.vigil.core.common.WallClock
import dev.vigil.core.day.DayRules
import dev.vigil.core.pool.Block
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.pool.PoolRules
import dev.vigil.core.pool.WindowSpan
import dev.vigil.core.protocol.Anchor
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Parsed
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import dev.vigil.core.timeline.PlannedStart
import dev.vigil.core.timeline.StartSource
import dev.vigil.core.timeline.Timeline
import dev.vigil.core.times.DayTimes
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

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

    fun dayTimes(): DayTimes = DayTimes(day.date, zone, nightEdge, s.times.current)

    /** The latest moment the current day can last. */
    fun fallback(): Instant = DayRules.fallbackEnd(day.date, file.day, zone)

    fun run(id: String): Run? = s.runs.firstOrNull { it.id == id }

    fun protocol(r: Run): Protocol? = file.find(r.protocol)

    /** The steps a run shows: its version on its weekday (the full steps if that version vanished). */
    fun stepsOf(r: Run): List<Step> {
        val p = protocol(r) ?: return emptyList()
        return p.stepsFor(r.version, r.weekday) ?: p.stepsFor(Version.FULL, r.weekday) ?: p.steps
    }

    /** The stage a protocol fires at now. */
    fun stageOf(p: Protocol): Int = if (!p.stages) 1 else (s.stages[p.id]?.stage ?: 1)

    /** True while the run's lock holds (active, a lock type, before its end on the run clock). */
    fun lockActive(r: Run): Boolean =
        r.phase == RunPhase.ACTIVE && r.lock != LockType.NONE && (r.lockEndsAt == null || runNow.isBefore(r.lockEndsAt))

    /** The run that owns the screen: an active run with a running screen lock, else a returned prayer takeover. */
    fun screenOwner(): Run? =
        s.runs.firstOrNull { it.lock == LockType.SCREEN && lockActive(it) } ?: s.runs.firstOrNull { it.phase == RunPhase.RETURNED }

    /** How much a connected call has extended [r] so far (what the call's end will add to its timers and lock). */
    fun callShift(r: Run): Duration {
        val since = s.callSince ?: return Duration.ZERO
        if (r.phase != RunPhase.ACTIVE) return Duration.ZERO
        val base = maxOf(since, r.lockStartedAt ?: since)
        return Duration.between(base, now).takeIf { !it.isNegative } ?: Duration.ZERO
    }

    /** When the run's lock ends as the user sees it (extended live during a call). */
    fun lockEndShown(r: Run): Instant? = r.lockEndsAt?.plus(callShift(r))

    /** Wind-down start: the sleep protocol's run start today, or bedtime - 60 while the file has no sleep protocol. */
    fun windDownAt(): Instant? {
        val sleep = file.protocols.firstOrNull { it.kind == ProtocolKind.SLEEP }
        return if (sleep != null) day.started[sleep.id] else dayTimes().bedtime.minus(Duration.ofMinutes(60))
    }

    /** The day's planned starts (time, slot, prayers) between its start and its latest end. */
    fun plan(): List<PlannedStart> =
        Timeline.plan(file, dayTimes(), s.prayers.lastAdhan, day.adhans, day.startedAt, fallback())

    /** The free windows placed on today. */
    fun windows(plan: List<PlannedStart> = plan()): List<WindowSpan> {
        val dt = dayTimes()
        return cfg.windows.map { w ->
            val opens = resolveAnchor(w.opens, false, dt, null, plan)
            val closes = if (opens == null) null else resolveAnchor(w.closes, true, dt, opens, plan)
            WindowSpan(w.id, opens, closes, w.capMin)
        }
    }

    private fun resolveAnchor(text: String, allowNext: Boolean, dt: DayTimes, opened: Instant?, plan: List<PlannedStart>): Instant? {
        val anchor = (Anchor.parse(text, allowNext) as? Parsed.Ok)?.value ?: return null
        return when (anchor) {
            is Anchor.At -> dt.resolve(anchor.expr)
            is Anchor.Done -> (if (file.find(anchor.protocolId) != null) day.completed[anchor.protocolId] else null) ?: dt.resolve(anchor.fallback)
            is Anchor.Start -> (if (file.find(anchor.protocolId) != null) day.started[anchor.protocolId] else null) ?: dt.resolve(anchor.fallback)
            Anchor.Next -> plan.firstOrNull { opened != null && it.at.isAfter(opened) }?.at ?: fallback()
        }
    }

    /** Run locks and toggles that block the pool, in design order (run lock, friends, walking; the night lock is M2). */
    fun lockBlocks(): List<Block> {
        val out = mutableListOf<Block>()
        val locking = s.runs.filter { (it.lock == LockType.SCREEN || it.lock == LockType.POOL) && lockActive(it) } +
            s.runs.filter { it.phase == RunPhase.RETURNED }
        if (locking.isNotEmpty()) {
            val ends = locking.map { if (it.phase == RunPhase.RETURNED) windowEnd(it) else lockEndShown(it) }
            val until = if (ends.any { it == null }) null else ends.filterNotNull().maxOrNull()
            out += Block(BlockReason.RUN_LOCK, until, locking.joinToString(", ") { protocol(it)?.title ?: it.protocol })
        }
        if (s.toggles.friends) out += Block(BlockReason.FRIENDS, null)
        if (s.toggles.walking) out += Block(BlockReason.WALKING, null)
        return out
    }

    fun decision(isPool: Boolean): PoolDecision {
        if (!isPool) return PoolDecision.Allowed
        return PoolRules.decide(cfg, s.pool, now, windows(), lockBlocks(), suspended)
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
            StepType.TEXT -> null
            StepType.DONE -> doneLockedUntil(r)?.plus(ext)
        }
    }

    /** Every moment the app should be awake for: the earliest after now, or null. */
    fun nextWake(): Instant? {
        val c = mutableListOf<Instant>()
        c += DayRules.nextCheck(day.date, zone, file.day, s.screenOffSince.takeIf { !s.screenOn }, windDownAt())
        s.suspendedUntil?.let { c += it }
        val plan = plan()
        plan.filter { it.source != StartSource.ADHAN && it.key !in day.handled }.forEach { c += it.at }
        if (!suspended) {
            for (prayer in Prayer.entries) {
                val p = file.prayerProtocol(prayer) ?: continue
                if (stageOf(p) >= 3) continue
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
            if (r.phase == RunPhase.ACTIVE && s.callSince == null) {
                r.lockEndsAt?.let { c += it }
                val steps = stepsOf(r)
                steps.getOrNull(r.step)?.takeIf { it.type == StepType.TIMER }?.let { st -> timerEnd(r, st)?.let { c += it } }
            }
        }
        val session = s.pool.session
        if (session != null) {
            val inSince = session.inSince
            if (inSince == null) {
                session.leftAt?.let { c += it.plusSeconds(cfg.sessionGapS.toLong()) }
            } else {
                c += inSince.plusSeconds(cfg.heavyUse.continuousMin * 60L - session.usedSeconds)
                val own = windows(plan).firstOrNull { it.id == session.windowId }
                if (own != null) {
                    own.closesAt?.let { c += it }
                    val before = (s.pool.windowUsed[own.id] ?: 0) + session.usedSeconds
                    c += inSince.plusSeconds(own.capSeconds - before)
                }
            }
        }
        return c.filter { it.isAfter(now) }.minOrNull()
    }
}
