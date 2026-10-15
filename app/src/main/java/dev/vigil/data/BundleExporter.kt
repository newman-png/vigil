package dev.vigil.data

import android.content.Context
import android.net.Uri
import dev.vigil.BuildConfig
import dev.vigil.Graph
import dev.vigil.core.engine.PlanSource
import dev.vigil.core.log.BundleExport
import dev.vigil.core.log.BundleMeta
import dev.vigil.core.log.EventType
import dev.vigil.core.log.LogEvent
import dev.vigil.core.log.LogExport
import dev.vigil.engine.Device
import dev.vigil.engine.Notifications
import dev.vigil.trigger.UsageWatcher
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Writes the weekly bundle (see core BundleExport) to a file the user picked: the plan that ran, its log,
 * ratings and check-ins in one self-describing file for the planner.
 */
object BundleExporter {
    /** Suggested file name, following the /plans naming convention. */
    fun suggestedName(): String {
        val week = Graph.plans.plan?.weekOf ?: LocalDate.now(Graph.zone)
        return "vigil-week-$week.json"
    }

    /**
     * Builds the bundle. The period starts at the plan's week_of (or its import the night before, if earlier), so a
     * mid-week re-import of a corrected plan does not cut the week in half; without a plan it covers the whole log.
     */
    fun build(context: Context): String = build(context, Instant.now()).first

    /** The bundle text and how many events it holds. */
    private fun build(context: Context, now: Instant): Pair<String, Int> {
        val zone = Graph.zone
        val snapshot = Graph.plans.snapshot.value
        val weekStart = snapshot.plan?.weekOf?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        // Event times are whole seconds, so the import time is too: otherwise its own plan_imported event is left out.
        val importedAt = snapshot.importedAtMs?.let { it - it % 1000 }
        val all = Graph.eventLog.readAll()
        // Never before the previous plan ended: its last night (a takeover finished after midnight on the new
        // week_of, its rating) belongs to the previous bundle. A corrected re-import mid-week keeps the whole week,
        // because no plan expired between the two imports.
        val previousEnd = importedAt
            ?.let { at -> all.lastOrNull { e -> e.type == EventType.PLAN_EXPIRED && (tsMs(e) ?: Long.MAX_VALUE) < at } }
            ?.let { e -> tsMs(e) }
        val since = listOfNotNull(listOfNotNull(importedAt, weekStart).minOrNull(), previousEnd).maxOrNull()
        val events = if (since == null) all else all.filter { e -> (tsMs(e) ?: Long.MAX_VALUE) >= since }
        val status = when {
            snapshot.plan == null -> "none"
            Graph.activeSet(now).source == PlanSource.PLAN -> "running"
            else -> "complete"
        }
        val s = Graph.settings
        val meta = BundleMeta(
            exportedAt = format(now),
            appVersion = BuildConfig.VERSION_NAME,
            timezone = zone.id,
            since = since?.let { format(Instant.ofEpochMilli(it)) },
            until = format(now),
            planText = Graph.plans.planText(),
            planStatus = status,
            steppedDown = Graph.state.current.steppedDown,
            settings = mapOf(
                LogExport.SETTING_ALLOW_QUIET_OVERRIDE to s.allowQuietOverride.toString(),
                "sound" to s.soundEnabled.toString(),
                "vibration" to s.vibrationEnabled.toString(),
                "glyph" to s.glyphEnabled.toString(),
                "adhan_apps" to s.adhanPackages.sorted().joinToString().ifEmpty { "any app" },
                "adhan_phrase" to s.adhanPhrase,
                "app_usage_detection" to when {
                    UsageWatcher.accessibilityConnected -> "accessibility (instant)"
                    Device.hasUsageAccess(context) -> "usage stats polling (~5 s)"
                    else -> "off (no usage access)"
                },
            ),
        )
        return BundleExport.build(events, meta) to events.size
    }

    /** Writes the bundle; on success logs bundle_exported and remembers it (PLAN COMPLETE then goes away). */
    fun write(context: Context, uri: Uri): Boolean = runCatching {
        val now = Instant.now()
        val (text, count) = build(context, now)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("no stream")
        val snapshot = Graph.plans.snapshot.value
        Graph.settings.lastBundleExportImportedAtMs = snapshot.importedAtMs ?: -1L
        Graph.settings.lastBundleExportAtMs = now.toEpochMilli()
        Notifications.cancelPlanComplete(context)
        Graph.eventLog.append(
            LogEvent(
                format(now), EventType.BUNDLE_EXPORTED,
                detail = "week_of ${snapshot.plan?.weekOf ?: "none"}, $count events",
            ),
        )
        true
    }.getOrDefault(false)

    /** An event's time as epoch ms (timestamps are ISO-8601 with offset, so they compare as instants), or null. */
    private fun tsMs(e: LogEvent): Long? = runCatching { OffsetDateTime.parse(e.ts).toInstant().toEpochMilli() }.getOrNull()

    private fun format(at: Instant): String =
        OffsetDateTime.ofInstant(at, Graph.zone).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
