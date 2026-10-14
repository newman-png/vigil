package dev.vigil.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * The current time (epoch ms), refreshed every [periodMs] while the screen is showing. For text that changes
 * with the clock alone: the plan expiring, the kill switch running out.
 */
@Composable
fun rememberNow(periodMs: Long = 15_000L): Long {
    val now by produceState(System.currentTimeMillis(), periodMs) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}
