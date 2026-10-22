package dev.vigil.core.pool

import dev.vigil.core.common.WallClock
import java.time.ZoneId

/** Plain uppercase text for pool blocks and decisions (block screen, home status, journal detail). */
object PoolText {
    /** What a reason says on screen, e.g. "BAIL-OUT PENALTY" or the run's title for a run lock. */
    fun reason(block: Block): String = when (block.reason) {
        BlockReason.RUN_LOCK -> block.detail?.uppercase() ?: "PROTOCOL LOCK"
        BlockReason.FRIENDS -> "YOU'RE WITH FRIENDS"
        BlockReason.WALKING -> "WALKING"
        BlockReason.NIGHT_LOCK -> "NIGHT LOCK"
        BlockReason.BUDGET -> block.detail?.let { "TODAY'S $it MINUTES ARE USED" } ?: "TODAY'S MINUTES ARE USED"
        BlockReason.PENALTY -> "BAIL-OUT PENALTY"
        BlockReason.HEAVY_USE -> "ENOUGH FOR NOW"
    }

    /** A block with its end, e.g. "BAIL-OUT PENALTY UNTIL 14:20", "TODAY'S 90 MINUTES ARE USED". */
    fun block(block: Block, zone: ZoneId): String {
        val until = block.until?.let { WallClock.hhmm(it, zone) }
        return reason(block) + (until?.let { " UNTIL $it" } ?: "")
    }
}
