package dev.vigil.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import dev.vigil.Graph
import java.time.Instant

/**
 * Reliability (design: "ApplicationExitInfo exit reasons on start"): on every process start, the exits Android
 * recorded since the last start (killed for memory, crashed, force-stopped, frozen ...) are journaled as app_exit, so
 * the report shows when and why VIGIL was not running.
 */
internal object ExitReasons {
    private const val TAG = "VigilExit"
    private const val MAX = 16

    /** The exits newer than the last one journaled, oldest first, as (reason, when, detail). */
    fun newExits(context: Context): List<Triple<String, Instant, String?>> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val all = try {
            am.getHistoricalProcessExitReasons(null, 0, MAX)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read the exit reasons: ${e.message}")
            return emptyList()
        }
        if (all.isEmpty()) return emptyList()
        val settings = Graph.settings
        val seen = settings.lastExitSeenMs
        val newest = all.maxOf { it.timestamp }
        if (newest > seen) settings.lastExitSeenMs = newest
        // The very first start of v2: only remember where the history ends (older exits belong to earlier versions).
        if (seen == 0L) return emptyList()
        return all.filter { it.timestamp > seen }
            .sortedBy { it.timestamp }
            .map { Triple(reasonName(it.reason), Instant.ofEpochMilli(it.timestamp), detail(context, it)) }
    }

    private fun detail(context: Context, info: ApplicationExitInfo): String? {
        val parts = mutableListOf<String>()
        info.description?.takeIf { it.isNotBlank() }?.let { parts += it }
        parts += "importance ${importanceName(info.importance)}"
        if (info.processName != context.packageName) parts += "process ${info.processName}"
        if (info.reason == ApplicationExitInfo.REASON_SIGNALED || info.reason == ApplicationExitInfo.REASON_EXIT_SELF) {
            parts += "status ${info.status}"
        }
        return parts.joinToString("; ").ifEmpty { null }
    }

    /** The reason as a journal word, e.g. low_memory or user_requested. */
    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        REASON_FREEZER -> "freezer"
        REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
        REASON_PACKAGE_UPDATED -> "package_updated"
        else -> "unknown"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground_service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
        else -> importance.toString()
    }

    // Android 14 reasons, by value (ApplicationExitInfo.REASON_FREEZER and friends need API 34; minSdk is 33).
    private const val REASON_FREEZER = 14
    private const val REASON_PACKAGE_STATE_CHANGE = 15
    private const val REASON_PACKAGE_UPDATED = 16
}
