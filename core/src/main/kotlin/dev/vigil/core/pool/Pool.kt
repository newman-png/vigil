@file:UseSerializers(InstantIso::class)

package dev.vigil.core.pool

import dev.vigil.core.common.InstantIso
import dev.vigil.core.protocol.PoolConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.math.roundToLong

/** Why the pool is blocked, in the order the decision checks them (design section 3). */
enum class BlockReason(val json: String) {
    /** A screen or pool lock of an active run. */
    RUN_LOCK("run_lock"),

    /** "I'm with friends" is on. */
    FRIENDS("friends"),

    /** "I'm walking" is on. */
    WALKING("walking"),

    /** The sleep protocol's night lock (M2). */
    NIGHT_LOCK("night_lock"),

    /** No free window is open now. */
    OUTSIDE_WINDOW("outside_window"),

    /** The open window's minutes are used up. */
    WINDOW_CAP("window_cap"),

    /** The lock after a session: factor x the minutes used, capped. */
    COOLDOWN("cooldown"),

    /** Bail-out penalty (stacking, daily cap). */
    PENALTY("penalty"),

    /** A heavy-use takeover just happened; its cooldown is running. */
    HEAVY_USE("heavy_use"),
    ;

    companion object {
        fun fromJson(value: String): BlockReason? = entries.firstOrNull { it.json == value }
    }
}

/** One reason the pool is blocked, until when (null = not known in advance), and a detail (e.g. the run's title). */
data class Block(val reason: BlockReason, val until: Instant?, val detail: String? = null)

/** The answer to "may this pool app open now?". */
sealed interface PoolDecision {
    /** Go ahead: a session is running (or VIGIL is suspended). */
    data object Allowed : PoolDecision

    /** No session: wait this many minutes on the wait screen first. */
    data class Wait(val minutes: Int) : PoolDecision

    /** Blocked: every reason that applies, first by design order. [reason] and [until] are the first one's. */
    data class Blocked(val blocks: List<Block>) : PoolDecision {
        init {
            require(blocks.isNotEmpty())
        }

        val reason: BlockReason get() = blocks.first().reason
        val until: Instant? get() = blocks.first().until
    }
}

/** A wait screen in progress for [app] since [startedAt], [minutes] long. */
@Serializable
data class PoolWait(val app: String, val startedAt: Instant, val minutes: Int)

/**
 * A pool session: it starts when a wait completes; switching pool apps keeps it; leaving the pool for less than
 * `session_gap_s` keeps it; otherwise it ends at the moment it was left.
 */
@Serializable
data class PoolSession(
    val startedAt: Instant,
    /** The free window it was opened in; its minutes count against that window's cap. */
    val windowId: String?,
    /** The pool app in front most recently. */
    val app: String,
    /** Seconds in the pool before [inSince]. */
    val usedSeconds: Long = 0,
    /** In a pool app since then; null while out of the pool (see [leftAt]). */
    val inSince: Instant? = startedAt,
    /** When the pool was left, while out of it. */
    val leftAt: Instant? = null,
) {
    /** Seconds used up to [now], counting the stretch in progress. */
    fun usedAt(now: Instant): Long = usedSeconds + (inSince?.let { seconds(it, now) } ?: 0)
}

/** A finished session: when it ended and how many seconds it used (for heavy use by recent minutes). */
@Serializable
data class UseMark(val at: Instant, val seconds: Long)

/** Everything the pool remembers. Counters reset when the day ends ([newDay]); until-times are moments and carry over. */
@Serializable
data class PoolState(
    /** Opens (completed waits) today: the next open waits min(cap, step x (opens + 1)). */
    val opens: Int = 0,
    /** Session starts within the heavy-use span, for "N opens within M minutes". */
    val openTimes: List<Instant> = emptyList(),
    /** Seconds used today. */
    val usedSeconds: Long = 0,
    /** Seconds used today per free window id. */
    val windowUsed: Map<String, Long> = emptyMap(),
    /** Finished sessions within the heavy-use span. */
    val recentUse: List<UseMark> = emptyList(),
    val penaltyMinutesToday: Int = 0,
    val penaltyUntil: Instant? = null,
    val cooldownUntil: Instant? = null,
    /** [CAUSE_SESSION] or [CAUSE_HEAVY_USE]. */
    val cooldownCause: String? = null,
    val wait: PoolWait? = null,
    val session: PoolSession? = null,
) {
    /** Counters back to zero for a new day; running locks (cooldown, penalty) and a live session stay. */
    fun newDay(): PoolState = copy(
        opens = 0, openTimes = emptyList(), usedSeconds = 0, windowUsed = emptyMap(), recentUse = emptyList(),
        penaltyMinutesToday = 0,
    )

    companion object {
        const val CAUSE_SESSION = "session"
        const val CAUSE_HEAVY_USE = "heavy_use"
    }
}

/** A free window placed on today: open from [opensAt] until [closesAt] (either null = not today). */
data class WindowSpan(val id: String, val opensAt: Instant?, val closesAt: Instant?, val capMin: Int) {
    fun isOpen(now: Instant): Boolean =
        opensAt != null && closesAt != null && !now.isBefore(opensAt) && now.isBefore(closesAt)

    val capSeconds: Long get() = capMin * 60L
}

/** The pool arithmetic of design section 3, as pure functions. The machine owns the state changes and the journal. */
object PoolRules {
    /** The Nth open of the day waits min(cap, step x N) minutes, N = [opens] + 1. */
    fun waitMinutes(cfg: PoolConfig, opens: Int): Int = min(cfg.waitCapMin, cfg.waitStepMin * (opens + 1))

    /** The cooldown after using the pool for [usedSeconds]: min(cap, factor x used). */
    fun cooldownSeconds(cfg: PoolConfig, usedSeconds: Long): Long =
        min(cfg.cooldownCapMin * 60L, (cfg.cooldownFactor * usedSeconds).roundToLong())

    /** Seconds used in window [id] today, counting a live session opened in it. */
    fun windowUsedSeconds(id: String, pool: PoolState, now: Instant): Long {
        val live = pool.session?.takeIf { it.windowId == id }?.usedAt(now) ?: 0
        return (pool.windowUsed[id] ?: 0) + live
    }

    /** The first open window (in file order) with minutes left, or null. */
    fun openWindow(windows: List<WindowSpan>, pool: PoolState, now: Instant): WindowSpan? =
        windows.firstOrNull { it.isOpen(now) && windowUsedSeconds(it.id, pool, now) < it.capSeconds }

    /** The next moment after [now] a window with minutes left opens, or null. */
    fun nextOpening(windows: List<WindowSpan>, pool: PoolState, now: Instant): Instant? = windows
        .filter { it.opensAt != null && it.opensAt.isAfter(now) && it.closesAt != null && it.closesAt.isAfter(it.opensAt) }
        .filter { windowUsedSeconds(it.id, pool, now) < it.capSeconds }
        .minOfOrNull { it.opensAt!! }

    /** Seconds used in the pool within the last `heavy_use.within_min` minutes (finished sessions and the live one). */
    fun recentUsedSeconds(cfg: PoolConfig, pool: PoolState, now: Instant): Long {
        val since = now.minus(Duration.ofMinutes(cfg.heavyUse.withinMin.toLong()))
        return pool.recentUse.filter { it.at.isAfter(since) }.sumOf { it.seconds } + (pool.session?.usedAt(now) ?: 0)
    }

    /** Session starts within the last `heavy_use.within_min` minutes (inclusive of [now]). */
    fun opensWithin(cfg: PoolConfig, pool: PoolState, now: Instant): Int {
        val since = now.minus(Duration.ofMinutes(cfg.heavyUse.withinMin.toLong()))
        return pool.openTimes.count { it.isAfter(since) && !it.isAfter(now) }
    }

    /**
     * The time-based blocks, in design order: outside every free window, window cap used, cooldown, bail penalty, a
     * just-triggered heavy use. A live session is judged by its own window (closed or full = blocked).
     */
    fun timeBlocks(cfg: PoolConfig, pool: PoolState, now: Instant, windows: List<WindowSpan>): List<Block> {
        val out = mutableListOf<Block>()
        val session = pool.session
        val own = session?.windowId?.let { id -> windows.firstOrNull { it.id == id } }
        if (session != null && own != null) {
            when {
                !own.isOpen(now) -> out += Block(BlockReason.OUTSIDE_WINDOW, nextOpening(windows, pool, now), own.id)
                windowUsedSeconds(own.id, pool, now) >= own.capSeconds ->
                    out += Block(BlockReason.WINDOW_CAP, nextOpening(windows, pool, now), own.id)
            }
        } else {
            val open = windows.filter { it.isOpen(now) }
            when {
                open.isEmpty() -> out += Block(BlockReason.OUTSIDE_WINDOW, nextOpening(windows, pool, now))
                open.none { windowUsedSeconds(it.id, pool, now) < it.capSeconds } ->
                    out += Block(BlockReason.WINDOW_CAP, nextOpening(windows, pool, now), open.first().id)
            }
        }
        val cooling = pool.cooldownUntil?.takeIf { it.isAfter(now) }
        if (cooling != null && pool.cooldownCause != PoolState.CAUSE_HEAVY_USE) out += Block(BlockReason.COOLDOWN, cooling)
        pool.penaltyUntil?.takeIf { it.isAfter(now) }?.let { out += Block(BlockReason.PENALTY, it) }
        if (cooling != null && pool.cooldownCause == PoolState.CAUSE_HEAVY_USE) out += Block(BlockReason.HEAVY_USE, cooling)
        return out
    }

    /**
     * The pool decision (design section 3). [lockBlocks] are the run, toggle and night-lock blocks the machine found,
     * already in design order. Suspended = everything open.
     */
    fun decide(
        cfg: PoolConfig,
        pool: PoolState,
        now: Instant,
        windows: List<WindowSpan>,
        lockBlocks: List<Block>,
        suspended: Boolean,
    ): PoolDecision {
        if (suspended) return PoolDecision.Allowed
        val blocks = lockBlocks + timeBlocks(cfg, pool, now, windows)
        if (blocks.isNotEmpty()) return PoolDecision.Blocked(blocks)
        if (pool.session != null) return PoolDecision.Allowed
        return PoolDecision.Wait(waitMinutes(cfg, pool.opens))
    }

    /**
     * A bail-out's penalty: [BailOutConfig.extraLockMin] more minutes of pool lock, stacking on a running penalty,
     * but at most `daily_cap_min` minutes added per day. Returns the new state and the minutes actually added.
     */
    fun penalty(cfg: PoolConfig, pool: PoolState, now: Instant): Pair<PoolState, Int> {
        val added = min(cfg.bailOut.extraLockMin, maxOf(0, cfg.bailOut.dailyCapMin - pool.penaltyMinutesToday))
        if (added <= 0) return pool to 0
        val from = pool.penaltyUntil?.takeIf { it.isAfter(now) } ?: now
        return pool.copy(
            penaltyUntil = from.plus(Duration.ofMinutes(added.toLong())),
            penaltyMinutesToday = pool.penaltyMinutesToday + added,
        ) to added
    }
}

internal fun seconds(from: Instant, to: Instant): Long = maxOf(0L, Duration.between(from, to).seconds)
