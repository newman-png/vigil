package dev.vigil.guard

import android.content.Context
import android.util.Log

/**
 * Opens the Guard's screens ([BlockActivity]): the pool's wait and blocked screens (from [GuardService]) and VIGIL's
 * own off switch.
 */
object GuardScreens {
    private const val TAG = "VigilGuard"

    /**
     * VIGIL's own off switch (seven taps on the takeover's eye or the home title): a 5-minute wait screen that must
     * stay in front (leaving it cancels), then VIGIL is suspended for an hour ([dev.vigil.engine.Vigil.suspend]): no
     * takeovers, the pool open.
     */
    fun killSwitch(context: Context) {
        start(context, GuardRequest(GuardMode.KILL, null))
    }

    /** The wait screen for pool app [app]: the user stays on it for the engine's wait, then [app] opens. */
    internal fun showWait(context: Context, app: String) {
        start(context, GuardRequest(GuardMode.WAIT, app))
    }

    /** The blocked screen for pool app [app], with the engine's reasons; [heavy] = the heavy-use "ENOUGH FOR NOW". */
    internal fun showBlocked(context: Context, app: String, heavy: Boolean) {
        start(context, GuardRequest(if (heavy) GuardMode.ENOUGH else GuardMode.BLOCKED, app))
    }

    private fun start(context: Context, request: GuardRequest) {
        val intent = BlockActivity.intent(context, request)
        // Through the Guard's own context when it runs (it may start screens from the background).
        if (GuardService.startActivity(intent)) return
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            // The system refused (rare: the Guard may start screens from the background). A blocked app was already
            // sent home; for a wait, GuardService sends the app home when the screen did not appear.
            Log.w(TAG, "Could not open the ${request.mode} screen: ${e.message}")
        }
    }
}
