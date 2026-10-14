package dev.vigil.data

import android.content.Context
import android.net.Uri
import dev.vigil.BuildConfig
import dev.vigil.Graph
import dev.vigil.core.log.ExportMeta
import dev.vigil.core.log.LogExport
import dev.vigil.engine.Device
import dev.vigil.trigger.UsageWatcher
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Writes log.json (see core LogExport) to a file the user picked. */
object LogExporter {
    /** Suggested file name, following the /plans naming convention. */
    fun suggestedName(): String {
        val week = Graph.plans.plan?.weekOf?.toString() ?: java.time.LocalDate.now().toString()
        return "log-$week.json"
    }

    /** Builds the export. With [thisWeek] only events since the current plan was imported are included. */
    fun build(context: Context, thisWeek: Boolean): String {
        val zone = Graph.zone
        val all = Graph.eventLog.readAll()
        val snapshot = Graph.plans.snapshot.value
        // This week = from the plan's week_of (or its import the night before, if earlier), so a mid-week
        // re-import of a corrected plan does not cut the week in half.
        val weekStart = snapshot.plan?.weekOf?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val since = if (thisWeek) listOfNotNull(snapshot.importedAtMs, weekStart).minOrNull() else null
        val sinceText = since?.let { format(Instant.ofEpochMilli(it)) }
        // Timestamps are ISO-8601 with offset, so compare as instants.
        val events = if (since == null) all else all.filter { e ->
            runCatching { OffsetDateTime.parse(e.ts).toInstant().toEpochMilli() >= since }.getOrDefault(true)
        }
        val plan = snapshot.plan
        val s = Graph.settings
        val meta = ExportMeta(
            exportedAt = format(Instant.now()),
            appVersion = BuildConfig.VERSION_NAME,
            timezone = zone.id,
            planWeekOf = plan?.weekOf?.toString(),
            planExpires = plan?.expires?.toString(),
            planGeneratedBy = plan?.generatedBy,
            since = sinceText,
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
        return LogExport.build(events, meta)
    }

    fun write(context: Context, uri: Uri, thisWeek: Boolean): Boolean = runCatching {
        val text = build(context, thisWeek)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("no stream")
        true
    }.getOrDefault(false)

    private fun format(at: Instant): String =
        OffsetDateTime.ofInstant(at, Graph.zone).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
