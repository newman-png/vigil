package dev.vigil.core.pool

import dev.vigil.core.common.WallClock
import java.time.ZoneId

/** Plain uppercase text for pool blocks and decisions (block screen, home status, journal detail). */
object PoolText {
    /** What a reason says on screen, e.g. "COOLDOWN" or the run's title for a run lock. */
    fun reason(block: Block): String = when (block.reason) {
        BlockReason.RUN_LOCK -> block.detail?.uppercase() ?: "PROTOCOL LOCK"
        BlockReason.FRIENDS -> "YOU'RE WITH FRIENDS"
        BlockReason.WALKING -> "WALKING"
        BlockReason.NIGHT_LOCK -> "NIGHT LOCK"
        BlockReason.OUTSIDE_WINDOW -> "NO FREE WINDOW"
        BlockReason.WINDOW_CAP -> "WINDOW USED UP"
        BlockReason.COOLDOWN -> "COOLDOWN"
        BlockReason.PENALTY -> "BAIL-OUT PENALTY"
        BlockReason.HEAVY_USE -> "HEAVY USE"
    }

    /** A block with its end, e.g. "COOLDOWN UNTIL 14:20", "NO FREE WINDOW · NEXT 18:45". */
    fun block(block: Block, zone: ZoneId): String {
        val until = block.until?.let { WallClock.hhmm(it, zone) }
        return when (block.reason) {
            BlockReason.OUTSIDE_WINDOW, BlockReason.WINDOW_CAP ->
                reason(block) + if (until != null) " · NEXT WINDOW $until" else " · NO MORE WINDOWS TODAY"
            else -> reason(block) + (until?.let { " UNTIL $it" } ?: "")
        }
    }
}
