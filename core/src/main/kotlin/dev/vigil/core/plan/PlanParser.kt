package dev.vigil.core.plan

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** A problem found in plan.json, phrased for a person (or an AI planner) to fix. */
data class Issue(
    /** JSON path to the problem, e.g. `directives[2].trigger.at`. Empty for whole-file problems. */
    val path: String,
    val message: String,
) {
    override fun toString(): String = if (path.isEmpty()) message else "$path: $message"
}

sealed interface ParseResult {
    val warnings: List<Issue>

    data class Success(val plan: Plan, override val warnings: List<Issue>) : ParseResult

    data class Failure(val errors: List<Issue>, override val warnings: List<Issue>) : ParseResult
}

/**
 * Reads and validates plan.json. Mirrors /spec/schema.json rule for rule (SchemaConformanceTest keeps the
 * two in step) and adds the checks a JSON Schema cannot express, such as bad_day ids that must exist.
 *
 * It never throws: every problem becomes an [Issue] so the import screen can list them all at once.
 * Unknown trigger types and unknown fields are warnings, not errors, so newer plans still load.
 */
object PlanParser {
    const val MAX_FILE_CHARS = 1_000_000
    const val MAX_STEP_SECONDS = 3600
    const val MAX_WINDOW_MIN = 1440

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,47}$")
    private val TIME_PATTERN = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    private val DATE_PATTERN = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val DATETIME_PATTERN = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$")
    private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val OFFSET_PATTERN = Regex("(?i)(z|[+-][0-9]{2}:?[0-9]{2})$")

    val DAY_CODES: Map<String, DayOfWeek> = linkedMapOf(
        "mon" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY,
    )

    private val TOP_LEVEL_KEYS = setOf(
        "\$schema", "schema_version", "week_of", "expires", "generated_by", "context",
        "day_boundary", "directives", "bad_day", "fallback_plan",
    )
    private val DIRECTIVE_KEYS = setOf("id", "tier", "title", "trigger", "steps", "why", "quiet")
    private val STEP_KEYS = setOf("text", "min_seconds")
    private val TIME_KEYS = setOf("type", "at", "days")
    private val ADHAN_KEYS = setOf("type", "prayer")
    private val APP_USAGE_KEYS = setOf("type", "apps", "opens", "window_min", "continuous_min", "cooldown_min")

    private val lenientJson = Json { isLenient = false }

    fun parse(text: String): ParseResult {
        val ctx = Ctx()
        if (text.length > MAX_FILE_CHARS) {
            ctx.error("", "This file is too large to be a plan (over 1 MB).")
            return ctx.failure()
        }
        if (text.isBlank()) {
            ctx.error("", "This file is empty.")
            return ctx.failure()
        }
        val root = try {
            lenientJson.parseToJsonElement(text.removePrefix("﻿"))
        } catch (e: SerializationException) {
            ctx.error("", describeSyntaxError(text, e))
            return ctx.failure()
        } catch (e: IllegalArgumentException) {
            ctx.error("", describeSyntaxError(text, e))
            return ctx.failure()
        }
        if (root !is JsonObject) {
            ctx.error("", "A plan must be a JSON object that starts with '{'.")
            return ctx.failure()
        }
        val plan = readPlan(root, ctx)
        return if (plan != null && ctx.errors.isEmpty()) {
            ParseResult.Success(plan, ctx.warnings.toList())
        } else {
            ctx.failure()
        }
    }

    // ---------------------------------------------------------------- plan

    private fun readPlan(obj: JsonObject, ctx: Ctx): Plan? {
        ctx.warnUnknownKeys(obj, "", TOP_LEVEL_KEYS)

        val schemaVersion = ctx.requireInt(obj, "", "schema_version")
        if (schemaVersion != null) {
            if (schemaVersion > Plan.SUPPORTED_SCHEMA_VERSION) {
                ctx.error(
                    "schema_version",
                    "This plan uses schema_version $schemaVersion, but this app only understands version " +
                        "${Plan.SUPPORTED_SCHEMA_VERSION}. Update the app, or ask the planner for a version " +
                        "${Plan.SUPPORTED_SCHEMA_VERSION} plan.",
                )
            } else if (schemaVersion < 1) {
                ctx.error("schema_version", "schema_version must be ${Plan.SUPPORTED_SCHEMA_VERSION}.")
            }
        }

        val dayBoundary = if (obj.containsKey("day_boundary")) {
            ctx.requireTime(obj, "", "day_boundary")
        } else {
            Plan.DEFAULT_DAY_BOUNDARY
        }

        val weekOf = ctx.requireDate(obj, "", "week_of")
        val expires = readExpires(obj, ctx, dayBoundary ?: Plan.DEFAULT_DAY_BOUNDARY)
        if (weekOf != null && expires != null && !expires.isAfter(weekOf.atStartOfDay())) {
            ctx.error("expires", "expires (${expires}) must be after week_of ($weekOf).")
        }

        val generatedBy = ctx.optionalString(obj, "", "generated_by")
        val context = ctx.optionalString(obj, "", "context")

        val ids = mutableMapOf<String, String>() // id -> path where it was first seen
        val directives = readDirectiveList(obj, "", "directives", ctx, ids, required = true)

        val badDay = readBadDay(obj, ctx, directives)

        val fallback = when (val raw = obj["fallback_plan"]) {
            null, JsonNull -> null
            !is JsonObject -> {
                ctx.error("fallback_plan", "fallback_plan must be an object with a \"directives\" list.")
                null
            }
            else -> {
                ctx.warnUnknownKeys(raw, "fallback_plan", setOf("directives"))
                val list = readDirectiveList(raw, "fallback_plan", "directives", ctx, ids, required = true)
                list?.let { FallbackPlan(it) }
            }
        }

        if (schemaVersion == null || weekOf == null || expires == null || dayBoundary == null || directives == null) {
            return null
        }
        return Plan(
            schemaVersion = schemaVersion,
            weekOf = weekOf,
            expires = expires,
            generatedBy = generatedBy,
            context = context,
            dayBoundary = dayBoundary,
            directives = directives,
            badDay = badDay,
            fallbackPlan = fallback,
        )
    }

    private fun readExpires(obj: JsonObject, ctx: Ctx, dayBoundary: LocalTime): LocalDateTime? {
        val raw = ctx.requireString(obj, "", "expires") ?: return null
        val value = raw.trim()
        return when {
            DATETIME_PATTERN.matches(value) -> try {
                LocalDateTime.parse(value)
            } catch (e: DateTimeParseException) {
                ctx.error("expires", "\"$raw\" is not a real date and time.")
                null
            }
            DATE_PATTERN.matches(value) -> parseDate(value)?.atTime(dayBoundary) ?: run {
                ctx.error("expires", "\"$raw\" is not a real date.")
                null
            }
            OFFSET_PATTERN.containsMatchIn(value) -> {
                ctx.error(
                    "expires",
                    "Write expires in the phone's local time without a timezone, like 2026-10-26T04:00 (got \"$raw\").",
                )
                null
            }
            else -> {
                ctx.error("expires", "expires must look like 2026-10-26T04:00 or 2026-10-26 (got \"$raw\").")
                null
            }
        }
    }

    private fun readBadDay(obj: JsonObject, ctx: Ctx, directives: List<Directive>?): BadDay {
        val raw = obj["bad_day"]
        if (raw == null || raw == JsonNull) {
            ctx.warn("bad_day", "No bad_day list: in \"I'm not okay today\" mode nothing will fire.")
            return BadDay.NONE
        }
        if (raw !is JsonObject) {
            ctx.error("bad_day", "bad_day must be an object like {\"keep\": [\"fajr\", \"sleep\"]}.")
            return BadDay.NONE
        }
        ctx.warnUnknownKeys(raw, "bad_day", setOf("keep"))
        val keepRaw = raw["keep"]
        if (keepRaw !is JsonArray) {
            ctx.error("bad_day.keep", "bad_day.keep must be a list of directive ids.")
            return BadDay.NONE
        }
        val known = directives?.map { it.id }?.toSet()
        val keep = mutableListOf<String>()
        keepRaw.forEachIndexed { i, el ->
            val path = "bad_day.keep[$i]"
            val id = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
            when {
                id == null -> ctx.error(path, "Each entry in bad_day.keep must be a directive id (text).")
                known != null && id !in known ->
                    ctx.error(path, "bad_day.keep lists \"$id\", but there is no directive with that id.")
                id in keep -> ctx.warn(path, "\"$id\" is listed twice in bad_day.keep.")
                else -> keep += id
            }
        }
        return BadDay(keep)
    }

    // ---------------------------------------------------------------- directives

    private fun readDirectiveList(
        parent: JsonObject,
        parentPath: String,
        key: String,
        ctx: Ctx,
        ids: MutableMap<String, String>,
        required: Boolean,
    ): List<Directive>? {
        val path = join(parentPath, key)
        val raw = parent[key]
        if (raw == null || raw == JsonNull) {
            if (required) ctx.error(path, "\"$key\" is missing. It must be a list of directives.")
            return null
        }
        if (raw !is JsonArray) {
            ctx.error(path, "\"$key\" must be a list of directives.")
            return null
        }
        if (raw.isEmpty()) {
            ctx.error(path, "\"$key\" is empty. Add at least one directive.")
            return null
        }
        val out = mutableListOf<Directive>()
        var ok = true
        raw.forEachIndexed { i, el ->
            val dPath = "$path[$i]"
            if (el !is JsonObject) {
                ctx.error(dPath, "Each directive must be an object.")
                ok = false
                return@forEachIndexed
            }
            val d = readDirective(el, dPath, ctx, ids)
            if (d == null) ok = false else out += d
        }
        return if (ok) out else null
    }

    private fun readDirective(obj: JsonObject, path: String, ctx: Ctx, ids: MutableMap<String, String>): Directive? {
        ctx.warnUnknownKeys(obj, path, DIRECTIVE_KEYS)
        val errorsBefore = ctx.errors.size

        val id = ctx.requireString(obj, path, "id")?.also { id ->
            if (!ID_PATTERN.matches(id)) {
                ctx.error(
                    "$path.id",
                    "\"$id\" is not a valid id. Use 1-48 lowercase letters, digits, '-' or '_', starting with a letter or digit.",
                )
            } else {
                val first = ids[id]
                if (first != null) {
                    ctx.error("$path.id", "The id \"$id\" is used twice (also at $first). Every directive needs its own id.")
                } else {
                    ids[id] = path
                }
            }
        }

        val tier = ctx.requireInt(obj, path, "tier")?.also { tier ->
            if (tier !in 1..3) ctx.error("$path.tier", "tier must be 1, 2 or 3 (got $tier).")
        }

        val title = ctx.optionalString(obj, path, "title")?.also { title ->
            if (title.isBlank()) ctx.error("$path.title", "title must not be empty (or leave it out).")
            if (title.length > 40) ctx.warn("$path.title", "title is ${title.length} characters; keep it under 40 so it fits on screen.")
        }

        val why = ctx.requireString(obj, path, "why")?.also { why ->
            if (why.isBlank()) ctx.error("$path.why", "why must not be empty.")
            if (why.length > 90) ctx.warn("$path.why", "why is ${why.length} characters; keep it under 90 so it fits on one line.")
        }

        val quiet = ctx.requireString(obj, path, "quiet")?.let { raw ->
            QuietPolicy.fromJson(raw) ?: run {
                ctx.error("$path.quiet", "quiet must be \"queue\", \"silent\", \"ignore\" or \"override\" (got \"$raw\").")
                null
            }
        }
        if (quiet == QuietPolicy.OVERRIDE && tier != null && tier != 3) {
            ctx.error("$path.quiet", "quiet \"override\" is only allowed on tier 3 directives (this one is tier $tier).")
        }

        val steps = readSteps(obj, path, ctx)
        if (steps != null && tier == 1 && steps.size != 1) {
            ctx.error("$path.steps", "A tier 1 directive is a single nudge and must have exactly one step (it has ${steps.size}).")
        }
        if (steps != null && tier == 2 && steps.size > 8) {
            ctx.warn("$path.steps", "This tier 2 checklist has ${steps.size} steps; tier 2 is meant to be short.")
        }

        val trigger = readTrigger(obj, path, ctx)

        if (ctx.errors.size > errorsBefore) return null
        if (id == null || tier == null || why == null || quiet == null || steps == null || trigger == null) return null
        return Directive(
            id = id,
            tier = tier,
            title = title ?: id,
            trigger = trigger,
            steps = steps,
            why = why,
            quiet = quiet,
        )
    }

    private fun readSteps(obj: JsonObject, path: String, ctx: Ctx): List<Step>? {
        val sPath = "$path.steps"
        val raw = obj["steps"]
        if (raw == null || raw == JsonNull) {
            ctx.error(sPath, "\"steps\" is missing. Every directive needs at least one step.")
            return null
        }
        if (raw !is JsonArray) {
            ctx.error(sPath, "\"steps\" must be a list of steps.")
            return null
        }
        if (raw.isEmpty()) {
            ctx.error(sPath, "\"steps\" is empty. Every directive needs at least one step.")
            return null
        }
        val out = mutableListOf<Step>()
        var ok = true
        raw.forEachIndexed { i, el ->
            val p = "$sPath[$i]"
            if (el !is JsonObject) {
                ctx.error(p, "Each step must be an object like {\"text\": \"Make wudu\", \"min_seconds\": 60}.")
                ok = false
                return@forEachIndexed
            }
            ctx.warnUnknownKeys(el, p, STEP_KEYS)
            val text = ctx.requireString(el, p, "text")
            if (text != null && text.isBlank()) {
                ctx.error("$p.text", "Step text must not be empty.")
                ok = false
            }
            if (text != null && text.length > 120) {
                ctx.warn("$p.text", "Step text is ${text.length} characters; keep it under 120.")
            }
            val seconds = ctx.requireInt(el, p, "min_seconds")
            if (seconds != null && seconds !in 0..MAX_STEP_SECONDS) {
                ctx.error("$p.min_seconds", "min_seconds must be between 0 and $MAX_STEP_SECONDS (got $seconds).")
                ok = false
            }
            if (text == null || seconds == null) ok = false
            if (ok) out += Step(text!!, seconds!!)
        }
        return if (ok) out else null
    }

    // ---------------------------------------------------------------- triggers

    private fun readTrigger(obj: JsonObject, path: String, ctx: Ctx): Trigger? {
        val tPath = "$path.trigger"
        val raw = obj["trigger"]
        if (raw == null || raw == JsonNull) {
            ctx.error(tPath, "\"trigger\" is missing. Say what fires this directive, e.g. {\"type\": \"time\", \"at\": \"07:00\"}.")
            return null
        }
        if (raw !is JsonObject) {
            ctx.error(tPath, "\"trigger\" must be an object with a \"type\".")
            return null
        }
        val type = ctx.requireString(raw, tPath, "type") ?: return null
        return when (type) {
            Trigger.Time.TYPE -> readTimeTrigger(raw, tPath, ctx)
            Trigger.Adhan.TYPE -> readAdhanTrigger(raw, tPath, ctx)
            Trigger.AppUsage.TYPE -> readAppUsageTrigger(raw, tPath, ctx)
            else -> {
                val reserved = if (type in Trigger.RESERVED_TYPES) " It is reserved for a future version." else ""
                ctx.warn(
                    "$tPath.type",
                    "Trigger type \"$type\" isn't supported by this app.$reserved This directive is kept but will never fire. " +
                        "Supported types: ${Trigger.SUPPORTED_TYPES.joinToString()}.",
                )
                Trigger.Unsupported(type)
            }
        }
    }

    private fun readTimeTrigger(obj: JsonObject, path: String, ctx: Ctx): Trigger.Time? {
        ctx.warnUnknownKeys(obj, path, TIME_KEYS)
        val at = ctx.requireTime(obj, path, "at")
        val daysRaw = obj["days"]
        val days: Set<DayOfWeek>? = when {
            daysRaw == null || daysRaw == JsonNull -> DayOfWeek.entries.toSet()
            daysRaw !is JsonArray -> {
                ctx.error("$path.days", "days must be a list like [\"mon\", \"wed\", \"fri\"] (or leave it out for every day).")
                null
            }
            daysRaw.isEmpty() -> {
                ctx.error("$path.days", "days is empty, so this would never fire. List days or leave it out for every day.")
                null
            }
            else -> {
                val set = linkedSetOf<DayOfWeek>()
                var ok = true
                daysRaw.forEachIndexed { i, el ->
                    val code = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val day = code?.let { DAY_CODES[it] }
                    if (day == null) {
                        ctx.error("$path.days[$i]", "Days are written mon, tue, wed, thu, fri, sat, sun (got ${el}).")
                        ok = false
                    } else if (!set.add(day)) {
                        ctx.warn("$path.days[$i]", "\"$code\" is listed twice.")
                    }
                }
                if (ok) set else null
            }
        }
        if (at == null || days == null) return null
        return Trigger.Time(at, days)
    }

    private fun readAdhanTrigger(obj: JsonObject, path: String, ctx: Ctx): Trigger.Adhan? {
        ctx.warnUnknownKeys(obj, path, ADHAN_KEYS)
        val raw = ctx.requireString(obj, path, "prayer") ?: return null
        val prayer = Prayer.fromJson(raw)
        if (prayer == null) {
            ctx.error("$path.prayer", "prayer must be one of fajr, dhuhr, asr, maghrib, isha (got \"$raw\").")
            return null
        }
        return Trigger.Adhan(prayer)
    }

    private fun readAppUsageTrigger(obj: JsonObject, path: String, ctx: Ctx): Trigger.AppUsage? {
        ctx.warnUnknownKeys(obj, path, APP_USAGE_KEYS)
        val errorsBefore = ctx.errors.size

        val appsRaw = obj["apps"]
        val apps = mutableListOf<String>()
        when {
            appsRaw == null || appsRaw == JsonNull ->
                ctx.error("$path.apps", "\"apps\" is missing. List Android package names, e.g. [\"com.instagram.android\"].")
            appsRaw !is JsonArray -> ctx.error("$path.apps", "\"apps\" must be a list of Android package names.")
            appsRaw.isEmpty() -> ctx.error("$path.apps", "\"apps\" is empty. List at least one Android package name.")
            else -> appsRaw.forEachIndexed { i, el ->
                val pkg = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    pkg == null || !PACKAGE_PATTERN.matches(pkg) -> ctx.error(
                        "$path.apps[$i]",
                        "${el} is not an Android package name. Use the app's package id, like \"com.zhiliaoapp.musically\" for TikTok.",
                    )
                    pkg in apps -> ctx.warn("$path.apps[$i]", "\"$pkg\" is listed twice.")
                    else -> apps += pkg
                }
            }
        }

        val hasOpens = obj.containsKey("opens") || obj.containsKey("window_min")
        val hasContinuous = obj.containsKey("continuous_min")
        val limit: UsageLimit? = when {
            hasOpens && hasContinuous -> {
                ctx.error(path, "Use either \"opens\" + \"window_min\" or \"continuous_min\", not both.")
                null
            }
            hasOpens -> {
                val opens = ctx.requireInt(obj, path, "opens")
                val window = ctx.requireInt(obj, path, "window_min")
                if (opens != null && opens !in 1..1000) ctx.error("$path.opens", "opens must be between 1 and 1000 (got $opens).")
                if (window != null && window !in 1..MAX_WINDOW_MIN) {
                    ctx.error("$path.window_min", "window_min must be between 1 and $MAX_WINDOW_MIN minutes (got $window).")
                }
                if (opens != null && window != null) UsageLimit.Opens(opens, window) else null
            }
            hasContinuous -> {
                val minutes = ctx.requireInt(obj, path, "continuous_min")
                if (minutes != null && minutes !in 1..MAX_WINDOW_MIN) {
                    ctx.error("$path.continuous_min", "continuous_min must be between 1 and $MAX_WINDOW_MIN minutes (got $minutes).")
                }
                minutes?.let { UsageLimit.Continuous(it) }
            }
            else -> {
                ctx.error(
                    path,
                    "An app_usage trigger needs either \"opens\" + \"window_min\" (opened N times in M minutes) " +
                        "or \"continuous_min\" (used M minutes without a break).",
                )
                null
            }
        }

        val cooldown = if (obj.containsKey("cooldown_min")) {
            ctx.requireInt(obj, path, "cooldown_min")?.also {
                if (it !in 0..MAX_WINDOW_MIN) ctx.error("$path.cooldown_min", "cooldown_min must be between 0 and $MAX_WINDOW_MIN (got $it).")
            }
        } else {
            when (limit) {
                is UsageLimit.Opens -> limit.windowMin
                is UsageLimit.Continuous -> limit.minutes
                null -> null
            }
        }

        if (ctx.errors.size > errorsBefore || limit == null || cooldown == null) return null
        return Trigger.AppUsage(apps.toList(), limit, cooldown)
    }

    // ---------------------------------------------------------------- helpers

    private fun parseDate(value: String): LocalDate? = try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun join(parent: String, key: String) = if (parent.isEmpty()) key else "$parent.$key"

    private fun describeSyntaxError(text: String, e: Exception): String {
        val offset = Regex("offset (\\d+)").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val where = if (offset != null && offset <= text.length) {
            val before = text.substring(0, offset)
            val line = before.count { it == '\n' } + 1
            val column = offset - (before.lastIndexOf('\n') + 1) + 1
            " (problem near line $line, column $column)"
        } else {
            ""
        }
        return "This file isn't valid JSON$where. Look for a missing comma, quote or bracket, " +
            "or a comma after the last item in a list."
    }

    private class Ctx {
        val errors = mutableListOf<Issue>()
        val warnings = mutableListOf<Issue>()

        fun error(path: String, message: String) {
            errors += Issue(path, message)
        }

        fun warn(path: String, message: String) {
            warnings += Issue(path, message)
        }

        fun failure() = ParseResult.Failure(errors.toList(), warnings.toList())

        /** Keys starting with "_" are comments (like "_readme") and are always allowed. */
        fun warnUnknownKeys(obj: JsonObject, path: String, known: Set<String>) {
            for (key in obj.keys) {
                if (key.startsWith("_") || key in known) continue
                warn(join(path, key), "Unknown field \"$key\" is ignored by this version of the app.")
            }
        }

        fun requireString(obj: JsonObject, path: String, key: String): String? {
            val p = join(path, key)
            return when (val el = obj[key]) {
                null, JsonNull -> {
                    error(p, "\"$key\" is missing.")
                    null
                }
                is JsonPrimitive -> if (el.isString) el.content else {
                    error(p, "\"$key\" must be text in quotes (got $el).")
                    null
                }
                else -> {
                    error(p, "\"$key\" must be text in quotes.")
                    null
                }
            }
        }

        fun optionalString(obj: JsonObject, path: String, key: String): String? {
            val el = obj[key]
            if (el == null || el == JsonNull) return null
            if (el is JsonPrimitive && el.isString) return el.content
            error(join(path, key), "\"$key\" must be text in quotes.")
            return null
        }

        fun requireInt(obj: JsonObject, path: String, key: String): Int? {
            val p = join(path, key)
            return when (val el = obj[key]) {
                null, JsonNull -> {
                    error(p, "\"$key\" is missing.")
                    null
                }
                is JsonPrimitive -> {
                    val long = if (el.isString) null else el.longOrNull
                    when {
                        el.isString -> {
                            error(p, "\"$key\" must be a number without quotes (got \"${el.content}\").")
                            null
                        }
                        long == null -> {
                            error(p, "\"$key\" must be a whole number (got ${el.content}).")
                            null
                        }
                        long < Int.MIN_VALUE || long > Int.MAX_VALUE -> {
                            error(p, "\"$key\" is far too large (${el.content}).")
                            null
                        }
                        else -> long.toInt()
                    }
                }
                else -> {
                    error(p, "\"$key\" must be a whole number.")
                    null
                }
            }
        }

        fun requireTime(obj: JsonObject, path: String, key: String): LocalTime? {
            val raw = requireString(obj, path, key) ?: return null
            if (!TIME_PATTERN.matches(raw)) {
                error(join(path, key), "\"$key\" must be a 24-hour time like 05:30 or 21:00 (got \"$raw\").")
                return null
            }
            return LocalTime.parse(raw)
        }

        fun requireDate(obj: JsonObject, path: String, key: String): LocalDate? {
            val raw = requireString(obj, path, key) ?: return null
            if (!DATE_PATTERN.matches(raw)) {
                error(join(path, key), "\"$key\" must be a date like 2026-10-19 (got \"$raw\").")
                return null
            }
            return parseDate(raw) ?: run {
                error(join(path, key), "\"$raw\" is not a real date.")
                null
            }
        }
    }
}
