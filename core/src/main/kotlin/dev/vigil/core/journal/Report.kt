package dev.vigil.core.journal

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Counts for one protocol within a day. */
data class ProtocolCounts(
    val fired: Int = 0,
    val completed: Int = 0,
    val clean: Int = 0,
    val steppedDown: Int = 0,
    val bailed: Int = 0,
    val missed: Int = 0,
    val notFired: Int = 0,
    val skipped: Int = 0,
)

/** One day of the journal, summed up. */
data class DaySummary(
    /** The day key; null for entries written before the first day_start. */
    val key: String?,
    val startedAt: String?,
    /** How the previous day ended (or first_start). */
    val startReason: String?,
    val endedAt: String?,
    val endReason: String?,
    val poolOpens: Int,
    val poolSeconds: Long,
    /** pool_blocked count per reason. */
    val poolBlocks: Map<String, Int>,
    val waitsAbandoned: Int,
    val heavyUse: Int,
    val penaltyMinutes: Int,
    val prayersConfirmed: List<String>,
    val prayersMissed: List<String>,
    /** Prayers whose adhan never arrived (self-check). */
    val prayersNoAdhan: List<String>,
    val protocols: Map<String, ProtocolCounts>,
    val bailOuts: List<Entry>,
    val killSwitch: List<Entry>,
    val speedBumps: List<Entry>,
    val appExits: List<Entry>,
    val lateAlarms: List<Entry>,
    val stageChanges: List<Entry>,
    /** Entries with typed text (text steps). */
    val notes: List<Entry>,
    val entries: List<Entry>,
    /** Tonight's target bedtime (the day's sleep_target `scheduled`), or null without a sleep protocol. */
    val sleepTarget: String? = null,
    /** When the day's night began (GOING TO SLEEP, or the phone put down) with minutes after the target. */
    val slept: Entry? = null,
    /** This morning's wake (the alarm dismissed, or the unlock after it went unanswered) with minutes after the alarm. */
    val woke: Entry? = null,
    /** Why this day's night was a missed one for sleep shifting (late, no_sleep_tap, bailed), or null. */
    val nightMissed: String? = null,
    val alarmsUnanswered: Int = 0,
    /** To-dos ticked off this day. */
    val todosDone: Int = 0,
    /** Jummah: "confirmed", "missed" or null (not a Friday, or nothing happened). */
    val jummah: String? = null,
    /** Jummah's I'M AT UNI confirmation (minutes after the arrival time), or null. */
    val arrived: Entry? = null,
    /** Resets that re-entered the day (detail = the line, text = what derailed the day). */
    val resets: List<Entry> = emptyList(),
    /** Reset offers made (reason misses:<n> or jummah_missed). */
    val offers: Int = 0,
    /** Offers declined with NOT NOW. */
    val offersDeclined: Int = 0,
    /** 10-minute promises: every `promise` entry (reason promised, due, snoozed, on_it, kept, bailed, expired_at_day_end). */
    val promises: List<Entry> = emptyList(),
    /** Study blocks finished (detail = what, minutes = work minutes, text = the closing line). */
    val study: List<Entry> = emptyList(),
    /** Gym sets logged (exercise, reps, weight). */
    val gymSets: List<Entry> = emptyList(),
    /** Workouts finished. */
    val workouts: List<Entry> = emptyList(),
    /** Walks that ended (minutes, reason). */
    val walks: List<Entry> = emptyList(),
    /** Minutes with the friends switch on (summed over the times it went off). */
    val friendsMinutes: Int = 0,
    /** Call protocol outcomes: `call` entries with reason done or missed (detail = who, minutes = the longest call). */
    val calls: List<Entry> = emptyList(),
    /** Wellness nudges shown. */
    val nudges: Int = 0,
)

/** Facts written at the top of the report. */
data class ReportMeta(val generatedAt: Instant, val zone: ZoneId, val appVersion: String? = null)

/**
 * The progress report (markdown) and the journal as CSV, exported on demand from home (design 2.3). Built only from
 * journal entries, so it can be rebuilt from any exported journal.
 */
object Report {
    /** CSV columns, in [Entry] field order. */
    val CSV_COLUMNS = listOf(
        "ts", "type", "protocol", "run", "step", "version", "stage", "app", "minutes", "seconds", "reason", "detail",
        "text", "day", "prayer", "until", "scheduled", "test", "exercise", "reps", "weight",
    )

    /** Splits [entries] into days at each day_start (entries before the first one form a day with a null key). */
    fun days(entries: List<Entry>): List<DaySummary> {
        val groups = mutableListOf<MutableList<Entry>>()
        for (e in entries) {
            if (e.type == EntryType.DAY_START || groups.isEmpty()) groups += mutableListOf<Entry>()
            groups.last() += e
        }
        return groups.map(::summarize)
    }

    private fun summarize(all: List<Entry>): DaySummary {
        // TEST-screen previews stay in the journal but never count.
        val list = all.filter { it.test != true }
        val start = list.firstOrNull { it.type == EntryType.DAY_START }
        val end = list.lastOrNull { it.type == EntryType.DAY_END }
        val counts = linkedMapOf<String, ProtocolCounts>()
        fun bump(id: String?, f: (ProtocolCounts) -> ProtocolCounts) {
            if (id == null) return
            counts[id] = f(counts[id] ?: ProtocolCounts())
        }
        // A study block's closing line has its own line (with what was studied), so it is not repeated as a note.
        val studyRuns = list.filter { it.type == EntryType.STUDY }.mapNotNull { it.run }.toSet()
        for (e in list) {
            when (e.type) {
                EntryType.FIRED -> bump(e.protocol) { it.copy(fired = it.fired + 1) }
                EntryType.COMPLETED -> bump(e.protocol) {
                    it.copy(completed = it.completed + 1, clean = it.clean + if (e.reason == "clean") 1 else 0)
                }
                EntryType.STEPPED_DOWN -> bump(e.protocol) { it.copy(steppedDown = it.steppedDown + 1) }
                EntryType.BAILED -> bump(e.protocol) { it.copy(bailed = it.bailed + 1) }
                EntryType.MISSED -> bump(e.protocol) { it.copy(missed = it.missed + 1) }
                EntryType.NOT_FIRED -> bump(e.protocol) { it.copy(notFired = it.notFired + 1) }
                EntryType.SKIPPED -> bump(e.protocol) { it.copy(skipped = it.skipped + 1) }
            }
        }
        return DaySummary(
            key = start?.day,
            startedAt = start?.ts,
            startReason = start?.reason,
            endedAt = end?.ts,
            endReason = end?.reason,
            poolOpens = list.count { it.type == EntryType.POOL_OPEN },
            poolSeconds = list.filter { it.type == EntryType.POOL_SESSION }.sumOf { it.seconds ?: ((it.minutes ?: 0) * 60L) },
            poolBlocks = list.filter { it.type == EntryType.POOL_BLOCKED }.groupingBy { it.reason ?: "unknown" }.eachCount(),
            waitsAbandoned = list.count { it.type == EntryType.POOL_WAIT_ABANDONED },
            heavyUse = list.count { it.type == EntryType.HEAVY_USE },
            penaltyMinutes = list.filter { it.type == EntryType.PENALTY }.sumOf { it.minutes ?: 0 },
            prayersConfirmed = list.filter { it.type == EntryType.CONFIRMED }.mapNotNull { it.prayer }.distinct(),
            prayersMissed = list.filter { it.type == EntryType.MISSED }.mapNotNull { it.prayer }.distinct(),
            prayersNoAdhan = list.filter { it.type == EntryType.NOT_FIRED && it.reason == "no_adhan" }.mapNotNull { it.prayer }.distinct(),
            protocols = counts,
            bailOuts = list.filter { it.type == EntryType.BAILED },
            killSwitch = list.filter { it.type == EntryType.KILL_SWITCH },
            speedBumps = list.filter { it.type == EntryType.SPEED_BUMP },
            appExits = list.filter { it.type == EntryType.APP_EXIT },
            lateAlarms = list.filter { it.type == EntryType.ALARM_LATE },
            stageChanges = list.filter { it.type == EntryType.STAGE_UP || it.type == EntryType.STAGE_DOWN },
            notes = list.filter { !it.text.isNullOrBlank() && it.type !in NOT_NOTES && !(it.type == EntryType.STEP_DONE && it.run in studyRuns) },
            entries = all,
            sleepTarget = list.lastOrNull { it.type == EntryType.SLEEP_TARGET }?.scheduled,
            slept = list.lastOrNull { it.type == EntryType.SLEEP },
            woke = list.firstOrNull { it.type == EntryType.WAKE },
            nightMissed = list.lastOrNull { it.type == EntryType.NIGHT_MISSED }?.reason,
            alarmsUnanswered = list.count { it.type == EntryType.WAKE_ALARM_UNANSWERED },
            todosDone = list.count { it.type == EntryType.TODO_DONE && it.reason == "done" },
            jummah = list.lastOrNull { it.prayer == JUMMAH && (it.type == EntryType.CONFIRMED || it.type == EntryType.MISSED) }
                ?.let { if (it.type == EntryType.CONFIRMED) "confirmed" else "missed" },
            arrived = list.lastOrNull { it.type == EntryType.ARRIVED },
            resets = list.filter { it.type == EntryType.REENTRY },
            offers = list.count { it.type == EntryType.OFFER && (it.reason?.startsWith("misses:") == true || it.reason == "jummah_missed") },
            offersDeclined = list.count { it.type == EntryType.OFFER && it.reason == "not_now" },
            promises = list.filter { it.type == EntryType.PROMISE },
            study = list.filter { it.type == EntryType.STUDY },
            gymSets = list.filter { it.type == EntryType.GYM_SET },
            workouts = list.filter { it.type == EntryType.WORKOUT },
            walks = list.filter { it.type == EntryType.WALK_END },
            friendsMinutes = list.filter { it.type == EntryType.TOGGLE && it.reason == "friends" }.sumOf { it.minutes ?: 0 },
            calls = list.filter { it.type == EntryType.CALL && (it.reason == "done" || it.reason == "missed") },
            nudges = list.count { it.type == EntryType.NUDGE_SHOWN },
        )
    }

    /** The journal's `prayer` value for Jummah. */
    private const val JUMMAH = "jummah"

    /** Entries with text that have their own lines (to-dos, resets, promises), so they are not repeated as notes. */
    private val NOT_NOTES = setOf(
        EntryType.TODO_DONE, EntryType.TODO_SET, EntryType.REENTRY, EntryType.PROMISE, EntryType.SKIPPED, EntryType.STUDY,
    )

    /** The markdown progress report. */
    fun markdown(entries: List<Entry>, meta: ReportMeta): String {
        val days = days(entries).filter { it.entries.isNotEmpty() }
        val sb = StringBuilder()
        sb.appendLine("# VIGIL progress report")
        sb.appendLine()
        val keys = days.mapNotNull { it.key }
        val generated = OffsetDateTime.ofInstant(meta.generatedAt, meta.zone).format(DATE_TIME)
        sb.append("Generated $generated (${meta.zone.id})")
        meta.appVersion?.let { sb.append(", app $it") }
        sb.append(". ")
        sb.appendLine(if (keys.isEmpty()) "No days recorded yet." else "${keys.size} day${if (keys.size == 1) "" else "s"}, ${keys.first()} to ${keys.last()}.")
        sb.appendLine()

        val confirmed = days.sumOf { it.prayersConfirmed.size }
        val missed = days.sumOf { it.prayersMissed.size }
        val all = days.flatMap { it.protocols.values }
        sb.appendLine("## Totals")
        sb.appendLine()
        sb.appendLine("- Prayers: $confirmed confirmed, $missed missed, ${days.sumOf { it.prayersNoAdhan.size }} without an adhan notification")
        sb.appendLine(
            "- Pool: ${days.sumOf { it.poolOpens }} opens, ${duration(days.sumOf { it.poolSeconds })}; " +
                "blocked ${days.sumOf { it.poolBlocks.values.sum() }} times; ${days.sumOf { it.heavyUse }} heavy use; " +
                "penalty ${days.sumOf { it.penaltyMinutes }} min",
        )
        sb.appendLine(
            "- Protocols: ${all.sumOf { it.completed }} completed (${all.sumOf { it.clean }} clean), " +
                "${all.sumOf { it.steppedDown }} stepped down, ${all.sumOf { it.bailed }} bailed, ${all.sumOf { it.missed }} missed, " +
                "${all.sumOf { it.notFired }} not fired",
        )
        val nights = days.mapNotNull { it.slept }
        val wakes = days.mapNotNull { it.woke }
        if (nights.isNotEmpty() || wakes.isNotEmpty() || days.any { it.sleepTarget != null }) {
            val missed = days.mapNotNull { it.nightMissed }
            val why = missed.groupingBy { it }.eachCount().entries.joinToString(", ") { "${missedText(it.key)} ${it.value}" }
            val average = wakes.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() }
            sb.appendLine(
                "- Sleep: ${count(nights.size, "night")} logged, ${missed.size} missed" + (if (why.isEmpty()) "" else " ($why)") +
                    "; woke ${count(wakes.size, "time")}" + (average?.let { ", on average ${relative(it, "the alarm")}" } ?: "") +
                    "; ${count(days.sumOf { it.alarmsUnanswered }, "alarm")} unanswered",
            )
        }
        val jummahs = days.mapNotNull { it.jummah }
        if (jummahs.isNotEmpty()) {
            sb.appendLine("- Jummah: ${jummahs.count { it == "confirmed" }} confirmed, ${jummahs.count { it == "missed" }} missed")
        }
        val resets = days.sumOf { it.resets.size }
        val offers = days.sumOf { it.offers }
        if (resets > 0 || offers > 0) {
            sb.appendLine("- Resets: $resets done; offered ${count(offers, "time")}, ${days.sumOf { it.offersDeclined }} not now")
        }
        val promises = days.flatMap { it.promises }
        if (promises.isNotEmpty()) {
            fun n(reason: String) = promises.count { it.reason == reason }
            sb.appendLine(
                "- Promises: ${n("promised")} made, ${n("kept")} kept, ${n("snoozed")} snoozed, ${n("bailed")} bailed, " +
                    "${n("expired_at_day_end")} expired at the day end",
            )
        }
        val study = days.flatMap { it.study }
        if (study.isNotEmpty()) {
            sb.appendLine("- Study: ${count(study.size, "block")}, ${duration(study.sumOf { (it.minutes ?: 0) * 60L })} of work")
        }
        val sets = days.flatMap { it.gymSets }
        if (sets.isNotEmpty() || days.any { it.workouts.isNotEmpty() }) {
            sb.appendLine("- Gym: ${count(days.sumOf { it.workouts.size }, "workout")}, ${count(sets.size, "set")}")
        }
        val walks = days.flatMap { it.walks }
        val friends = days.sumOf { it.friendsMinutes }
        if (walks.isNotEmpty() || friends > 0) {
            sb.appendLine("- Walks: ${count(walks.size, "walk")}, ${duration(walks.sumOf { (it.minutes ?: 0) * 60L })}; with friends ${duration(friends * 60L)}")
        }
        val calls = days.flatMap { it.calls }
        if (calls.isNotEmpty()) {
            sb.appendLine("- Calls: ${calls.count { it.reason == "done" }} done, ${calls.count { it.reason == "missed" }} missed")
        }
        val nudges = days.sumOf { it.nudges }
        if (nudges > 0) sb.appendLine("- Nudges: ${count(nudges, "nudge")} shown")
        sb.appendLine(
            "- Kill switch: ${days.sumOf { d -> d.killSwitch.count { it.reason == "suspended" } }} · " +
                "speed bumps: ${days.sumOf { it.speedBumps.size }} · app exits: ${days.sumOf { it.appExits.size }} · " +
                "late alarms: ${days.sumOf { it.lateAlarms.size }}",
        )
        sb.appendLine()
        gymProgress(sb, days)

        for (d in days) {
            sb.appendLine("## ${dayTitle(d.key)}")
            sb.appendLine()
            val started = d.startedAt?.let { "Started ${dateTime(it)} (${reasonText(d.startReason)})" } ?: "Before the first day start"
            val ended = d.endedAt?.let { "ended ${dateTime(it)} (${reasonText(d.endReason)})" } ?: "still running"
            sb.appendLine("$started · $ended")
            sb.appendLine()
            sb.appendLine(
                "- **Prayers** confirmed: ${d.prayersConfirmed.joinOrDash()} · missed: ${d.prayersMissed.joinOrDash()}" +
                    if (d.prayersNoAdhan.isNotEmpty()) " · no adhan: ${d.prayersNoAdhan.joinOrDash()}" else "",
            )
            val blocks = if (d.poolBlocks.isEmpty()) "0" else
                "${d.poolBlocks.values.sum()} (" + d.poolBlocks.entries.joinToString(", ") { "${it.key.replace('_', ' ')} ${it.value}" } + ")"
            sb.appendLine(
                "- **Pool** ${d.poolOpens} opens, ${duration(d.poolSeconds)} · blocked $blocks · waits abandoned ${d.waitsAbandoned} · " +
                    "heavy use ${d.heavyUse} · penalty ${d.penaltyMinutes} min",
            )
            val sleep = listOfNotNull(
                d.sleepTarget?.let { "target ${time(it)}" },
                d.woke?.let { "woke ${time(it.ts)} (${relative(it.minutes ?: 0, "the alarm")})" },
                d.slept?.let { "slept ${time(it.ts)} (${relative(it.minutes ?: 0, "the target")})" },
                d.nightMissed?.let { "missed night: ${missedText(it)}" },
                d.alarmsUnanswered.takeIf { it > 0 }?.let { "alarm unanswered" },
                d.todosDone.takeIf { it > 0 }?.let { "$it to-dos ticked" },
            )
            if (sleep.isNotEmpty()) sb.appendLine("- **Sleep** " + sleep.joinToString(" · "))
            if (d.jummah != null || d.arrived != null) {
                val parts = listOfNotNull(
                    d.jummah,
                    d.arrived?.let { "at uni ${time(it.ts)} (${relative(it.minutes ?: 0, "the arrival time")})" },
                )
                sb.appendLine("- **Jummah** " + parts.joinToString(" · "))
            }
            sb.appendLine()
            if (d.protocols.isNotEmpty()) {
                sb.appendLine("| Protocol | Fired | Completed | Clean | Stepped down | Bailed | Missed | Not fired | Skipped |")
                sb.appendLine("|---|---|---|---|---|---|---|---|---|")
                for ((id, c) in d.protocols) {
                    sb.appendLine("| $id | ${c.fired} | ${c.completed} | ${c.clean} | ${c.steppedDown} | ${c.bailed} | ${c.missed} | ${c.notFired} | ${c.skipped} |")
                }
                sb.appendLine()
            }
            listLine(sb, "Bail-outs", d.bailOuts) { "${time(it.ts)} ${it.protocol ?: ""}${it.detail?.let { x -> " ($x)" } ?: ""}" }
            listLine(sb, "Kill switch", d.killSwitch) {
                "${time(it.ts)} ${it.reason ?: ""}${it.minutes?.let { m -> " $m min" } ?: ""}${it.detail?.let { x -> " ($x)" } ?: ""}"
            }
            listLine(sb, "Speed bumps", d.speedBumps) { "${time(it.ts)} ${it.app ?: ""}${it.detail?.let { x -> " ($x)" } ?: ""}" }
            listLine(sb, "App exits", d.appExits) { "${time(it.ts)} ${it.reason ?: ""}${it.detail?.let { x -> " ($x)" } ?: ""}" }
            listLine(sb, "Late alarms", d.lateAlarms) {
                "${time(it.ts)} (planned ${it.scheduled?.let(::time) ?: "?"}, ${it.seconds ?: 0} s late)"
            }
            listLine(sb, "Stage changes", d.stageChanges) {
                "${time(it.ts)} ${it.protocol ?: ""} ${if (it.type == EntryType.STAGE_UP) "up" else "down"} to stage ${it.stage}"
            }
            listLine(sb, "Resets", d.resets) {
                "${time(it.ts)}" + (it.text?.let { t -> " \"$t\"" } ?: "") + (it.detail?.let { x -> ": $x" } ?: "")
            }
            listLine(sb, "Promises", d.promises.filter { it.reason != "promised" && it.reason != "due" }) {
                "${time(it.ts)} \"${it.text ?: ""}\" ${it.reason?.replace('_', ' ') ?: ""}"
            }
            if (d.walks.isNotEmpty() || d.friendsMinutes > 0) {
                val parts = listOfNotNull(
                    d.walks.takeIf { it.isNotEmpty() }?.let { w -> "walks " + w.joinToString(", ") { "${time(it.ts)} ${it.minutes ?: 0} min (${walkText(it.reason)})" } },
                    d.friendsMinutes.takeIf { it > 0 }?.let { "with friends $it min" },
                )
                sb.appendLine("- **Walks and friends** " + parts.joinToString(" · "))
                sb.appendLine()
            }
            listLine(sb, "Calls", d.calls) {
                "${it.detail ?: "?"} ${it.reason}" + (it.minutes?.let { m -> " ($m min call)" } ?: "") + " ${time(it.ts)}"
            }
            listLine(sb, "Study", d.study) {
                "${time(it.ts)} ${it.detail?.let { w -> "$w, " } ?: ""}${it.minutes ?: 0} min" + (it.text?.let { t -> ": \"$t\"" } ?: "")
            }
            if (d.gymSets.isNotEmpty()) {
                val byExercise = d.gymSets.groupBy { it.exercise ?: "?" }
                sb.appendLine("- **Gym**: " + byExercise.entries.joinToString("; ") { (name, list) -> "$name ${list.joinToString(", ") { setText(it) }}" })
                sb.appendLine()
            }
            listLine(sb, "Notes", d.notes) { "${time(it.ts)} ${it.protocol ?: ""}: \"${it.text!!.replace("\n", " / ")}\"" }
        }

        sb.appendLine("## How to read the journal")
        sb.appendLine()
        sb.appendLine("The CSV export has one row per journal entry (columns: ${CSV_COLUMNS.joinToString(", ")}). Entry types:")
        sb.appendLine()
        for ((type, text) in EntryType.DESCRIPTIONS) sb.appendLine("- `$type`: $text")
        return sb.toString()
    }

    /** The journal as CSV (RFC 4180: comma-separated, quoted when needed, CRLF line ends). */
    fun csv(entries: List<Entry>): String {
        val sb = StringBuilder()
        sb.append(CSV_COLUMNS.joinToString(",")).append("\r\n")
        for (e in entries) {
            val cells = listOf(
                e.ts, e.type, e.protocol, e.run, e.step?.toString(), e.version, e.stage?.toString(), e.app,
                e.minutes?.toString(), e.seconds?.toString(), e.reason, e.detail, e.text, e.day, e.prayer, e.until, e.scheduled,
                e.test?.toString(), e.exercise, e.reps?.toString(), e.weight?.let(::kg),
            )
            sb.append(cells.joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun csvCell(value: String?): String {
        if (value == null) return ""
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || value != value.trim()
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    /**
     * Gym progress per exercise: the best set of the last day it was trained against the best set before that day (weight
     * first, then reps).
     */
    private fun gymProgress(sb: StringBuilder, days: List<DaySummary>) {
        val sets = days.flatMap { d -> d.gymSets.map { (d.key ?: "") to it } }.filter { it.second.exercise != null }
        if (sets.isEmpty()) return
        sb.appendLine("## Gym progress")
        sb.appendLine()
        for ((name, list) in sets.groupBy { it.second.exercise!! }) {
            val lastDay = list.last().first
            val last = best(list.filter { it.first == lastDay }.map { it.second })
            val before = best(list.filter { it.first < lastDay }.map { it.second })
            val then = when {
                before == null -> "first time"
                score(last) > score(before) -> "previous best ${setText(before)}: better"
                score(last) == score(before) -> "previous best ${setText(before)}: the same"
                else -> "previous best ${setText(before)}"
            }
            sb.appendLine("- $name: ${last?.let { setText(it) } ?: "-"} on $lastDay ($then)")
        }
        sb.appendLine()
    }

    /** The best of [sets]: the heaviest, then the most reps. */
    private fun best(sets: List<Entry>): Entry? = sets.maxWithOrNull(compareBy({ it.weight ?: 0.0 }, { it.reps ?: 0 }))

    private fun score(e: Entry?): Pair<Double, Int> = (e?.weight ?: 0.0) to (e?.reps ?: 0)

    private operator fun Pair<Double, Int>.compareTo(other: Pair<Double, Int>): Int =
        compareValuesBy(this, other, { it.first }, { it.second })

    /** 5 x 80 kg, or 12 reps for body weight. */
    private fun setText(e: Entry): String {
        val w = e.weight ?: 0.0
        return if (w == 0.0) "${e.reps ?: 0} reps" else "${e.reps ?: 0} x ${kg(w)} kg"
    }

    /** 80 for 80.0, 77.5 for 77.5. */
    private fun kg(weight: Double): String = if (weight % 1.0 == 0.0) weight.toLong().toString() else weight.toString()

    /** 12 -> "12 min after the alarm", -5 -> "5 min before the alarm", 0 -> "at the alarm". */
    private fun relative(minutes: Int, what: String): String = when {
        minutes > 0 -> "$minutes min after $what"
        minutes < 0 -> "${-minutes} min before $what"
        else -> "at $what"
    }

    private fun count(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"

    /** Why a walk ended, for the report. */
    private fun walkText(reason: String?): String = when (reason) {
        "off" -> "switched off"
        "still" -> "stopped moving"
        "time" -> "time limit"
        "arrived" -> "at uni"
        "day_end" -> "day end"
        else -> reason ?: "?"
    }

    private fun missedText(reason: String): String = when (reason) {
        "late" -> "late"
        "no_sleep_tap" -> "no GOING TO SLEEP"
        "bailed" -> "wind-down left"
        else -> reason
    }

    private fun <T> listLine(sb: StringBuilder, label: String, items: List<T>, render: (T) -> String) {
        if (items.isEmpty()) return
        sb.appendLine("- **$label**: " + items.joinToString("; ", transform = render))
        sb.appendLine()
    }

    private fun List<String>.joinOrDash() = if (isEmpty()) "-" else joinToString(", ")

    private fun dayTitle(key: String?): String {
        val date = key?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return "Before the first day"
        return "$key (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)})"
    }

    private fun reasonText(reason: String?): String = when (reason) {
        null -> "unknown"
        "sleep_tap" -> "GOING TO SLEEP"
        "idle" -> "screen off after wind-down"
        "wake_alarm" -> "wake alarm"
        "fallback" -> "fallback end"
        "first_start" -> "first start"
        else -> reason
    }

    private fun duration(seconds: Long): String {
        val minutes = (seconds + 59) / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    private fun parse(ts: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(ts) }.getOrNull()

    private fun time(ts: String): String = parse(ts)?.format(TIME) ?: ts

    private fun dateTime(ts: String): String = parse(ts)?.format(DATE_TIME) ?: ts

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
