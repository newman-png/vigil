@file:UseSerializers(InstantIso::class)

package dev.vigil.core.pool

import dev.vigil.core.common.InstantIso
import dev.vigil.core.protocol.PoolConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant
import kotlin.math.min

/** Why the pool is blocked, in the order the decision checks them. */
enum class BlockReason(val json: String) {
    /** A screen or pool lock of an active run. */
    RUN_LOCK("run_lock"),

    /** "I'm with friends" is on. */
    FRIENDS("friends"),

    /** "I'm walking" is on. */
    WALKING("walking"),

    /** The sleep protocol's night lock (M2). */
    NIGHT_LOCK("night_lock"),

    /** Today's minutes (`daily_budget_min`) are used up: blocked until the day ends. */
    BUDGET("budget"),

    /** Bail-out penalty (stacking, daily cap). */
    PENALTY("penalty"),

    /** One long session closed the app ("ENOUGH FOR NOW"); its break is running. */
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

    /** Blocked: every reason that applies, in order. [reason] and [until] are the first one's. */
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

/**
 * Everything the pool remembers (v2.1: a daily budget). Counters reset when the day ends ([newDay]); until-times are
 * moments and carry over. A state saved by v2.0 (free windows, cooldowns) still reads: its old fields are ignored.
 */
@Serializable
data class PoolState(
    /** Opens (completed waits) today, for the journal. */
    val opens: Int = 0,
    /** Seconds used today in finished sessions ([usedToday] adds the live one). */
    val usedSeconds: Long = 0,
    val penaltyMinutesToday: Int = 0,
    val penaltyUntil: Instant? = null,
    /** The break after heavy use ends then. */
    val breakUntil: Instant? = null,
    val wait: PoolWait? = null,
    val session: PoolSession? = null,
) {
    /** Seconds used today up to [now], the live session included. */
    fun usedToday(now: Instant): Long = usedSeconds + (session?.usedAt(now) ?: 0)

    /** Counters back to zero for a new day; running locks (break, penalty) and a live session stay. */
    fun newDay(): PoolState = copy(opens = 0, usedSeconds = 0, penaltyMinutesToday = 0)
}

/** The pool arithmetic (v2.1: a daily budget), as pure functions. The machine owns the state changes and the journal. */
object PoolRules {
    /** The day's budget in seconds. */
    fun budgetSeconds(cfg: PoolConfig): Long = cfg.dailyBudgetMin * 60L

    /** Seconds of today's budget left at [now] (never below 0). */
    fun leftSeconds(cfg: PoolConfig, pool: PoolState, now: Instant): Long = maxOf(0L, budgetSeconds(cfg) - pool.usedToday(now))

    /** The time-based blocks, in order: today's budget used up, a bail-out penalty, the break after heavy use. */
    fun timeBlocks(cfg: PoolConfig, pool: PoolState, now: Instant): List<Block> {
        val out = mutableListOf<Block>()
        if (leftSeconds(cfg, pool, now) <= 0) out += Block(BlockReason.BUDGET, null, cfg.dailyBudgetMin.toString())
        pool.penaltyUntil?.takeIf { it.isAfter(now) }?.let { out += Block(BlockReason.PENALTY, it) }
        pool.breakUntil?.takeIf { it.isAfter(now) }?.let { out += Block(BlockReason.HEAVY_USE, it) }
        return out
    }

    /**
     * The pool decision. [lockBlocks] are the run, toggle and night-lock blocks the machine found, already in order.
     * Suspended = everything open. Otherwise blocked by any of those or by [timeBlocks]; else a live session goes on,
     * and a new open first waits `wait_min` minutes.
     */
    fun decide(cfg: PoolConfig, pool: PoolState, now: Instant, lockBlocks: List<Block>, suspended: Boolean): PoolDecision {
        if (suspended) return PoolDecision.Allowed
        val blocks = lockBlocks + timeBlocks(cfg, pool, now)
        if (blocks.isNotEmpty()) return PoolDecision.Blocked(blocks)
        if (pool.session != null) return PoolDecision.Allowed
        return PoolDecision.Wait(cfg.waitMin)
    }

    /**
     * A bail-out's penalty: [dev.vigil.core.protocol.BailOutConfig.extraLockMin] more minutes of pool lock, stacking on
     * a running penalty, but at most `daily_cap_min` minutes added per day. Returns the new state and the minutes added.
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
