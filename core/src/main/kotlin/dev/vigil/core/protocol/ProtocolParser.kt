package dev.vigil.core.protocol

import dev.vigil.core.common.WallClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A problem found in protocols.json, phrased for a person (or Claude Code) to fix. */
data class ProtocolIssue(
    /** Where it is, e.g. `protocols[2] (prayer-dhuhr).lock.minutes`. Empty for whole-file problems. */
    val path: String,
    val message: String,
) {
    override fun toString(): String = if (path.isEmpty()) message else "$path: $message"
}

/** The result of reading protocols.json: the file and its warnings, or every error found. */
sealed interface ProtocolParseResult {
    val warnings: List<ProtocolIssue>

    data class Success(val file: ProtocolFile, override val warnings: List<ProtocolIssue>) : ProtocolParseResult

    data class Failure(val errors: List<ProtocolIssue>, override val warnings: List<ProtocolIssue>) : ProtocolParseResult
}

/**
 * Reads and validates protocols.json: kotlinx.serialization for the shape, then the semantic rules of design 2.1
 * (known kinds, step types, triggers and anchors; unique ids; references that exist; ladder versions only where the
 * kind allows them; every number in range; package names that look like package names).
 *
 * It never throws. Errors make the file unusable (the bundled-file unit test fails on any error, so CI rejects a bad
 * edit); warnings (unknown fields, odd but workable values) are listed and the file still loads. Keys starting with
 * `_` are comments anywhere.
 */
object ProtocolParser {
    const val MAX_FILE_CHARS = 2_000_000
    const val MAX_STEP_MIN = 240
    const val MAX_LOCK_MIN = 600
    const val MAX_LINES = 10
    const val MAX_TITLE_CHARS = 30
    const val MAX_STEP_TEXT_CHARS = 160

    private val strict = Json { isLenient = false }
    private val decoder = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }
    private val INTEGER = Regex("^-?(0|[1-9][0-9]*)$")
    private val NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")
    private val PACKAGE_FIRST_SEGMENT = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    private val PACKAGE_SEGMENT = Regex("^[A-Za-z0-9_]+$")
    private val ROLES = listOf("dialer", "adhan", "clock")

    fun parse(input: String): ProtocolParseResult {
        val ctx = Ctx()
        if (input.length > MAX_FILE_CHARS) {
            ctx.error("", "The file is too large (over 2 MB).")
            return ctx.result(null)
        }
        val text = input.removePrefix("﻿")
        if (text.isBlank()) {
            ctx.error("", "The file is empty.")
            return ctx.result(null)
        }
        val root = try {
            strict.parseToJsonElement(text)
        } catch (e: Exception) {
            ctx.error("", "This is not valid JSON: ${e.message?.lineSequence()?.firstOrNull() ?: "syntax error"}")
            return ctx.result(null)
        }
        if (root !is JsonObject) {
            ctx.error("", "The file must be one JSON object { ... }.")
            return ctx.result(null)
        }
        checkShape(root, ROOT, "", ctx)
        if (ctx.errors.isNotEmpty()) return ctx.result(null)
        val file = try {
            decoder.decodeFromJsonElement(ProtocolFile.serializer(), root)
        } catch (e: Exception) {
            ctx.error("", "The file does not have the expected shape: ${e.message?.lineSequence()?.firstOrNull()}")
            return ctx.result(null)
        }
        checkSemantics(file, root, ctx)
        return ctx.result(file)
    }

    // ------------------------------------------------------------------ shape

    private sealed interface Shape {
        data object Text : Shape
        data object Whole : Shape
        data object Number : Shape
        data object Bool : Shape
        data object AnyObject : Shape
        data class OneOf(val values: List<String>) : Shape
        data class Obj(val fields: Map<String, Shape>, val required: Set<String> = emptySet()) : Shape
        data class ListOf(val item: Shape) : Shape
        data class MapOf(val keys: Set<String>, val value: Shape) : Shape
    }

    private val STEP = Shape.Obj(
        mapOf(
            "text" to Shape.Text,
            "type" to Shape.OneOf(StepType.entries.map { it.json }),
            "min" to Shape.Whole,
            "lines" to Shape.Whole,
        ),
        required = setOf("text"),
    )
    private val STEPS = Shape.ListOf(STEP)
    private val VERSIONS = Shape.Obj(mapOf("steps" to STEPS, "short" to STEPS, "minimal" to STEPS, "single" to STEPS))
    private val LOCK = Shape.Obj(
        mapOf("type" to Shape.OneOf(LockType.entries.map { it.json }), "minutes" to Shape.Whole, "done_after_min" to Shape.Whole),
    )
    private val PROTOCOL = Shape.Obj(
        mapOf(
            "id" to Shape.Text,
            "kind" to Shape.OneOf(ProtocolKind.entries.map { it.json }),
            "title" to Shape.Text,
            "trigger" to Shape.Text,
            "intensity" to Shape.OneOf(Intensity.entries.map { it.json }),
            "lock" to LOCK,
            "steps" to STEPS,
            "short" to STEPS,
            "minimal" to STEPS,
            "single" to STEPS,
            "weekdays" to Shape.MapOf(WallClock.DAY_CODES.keys, VERSIONS),
            "params" to Shape.AnyObject,
            "stages" to Shape.Bool,
        ),
        required = setOf("id", "kind", "title", "trigger", "steps"),
    )
    private val WINDOW = Shape.Obj(
        mapOf("id" to Shape.Text, "opens" to Shape.Text, "closes" to Shape.Text, "cap_min" to Shape.Whole),
        required = setOf("id", "opens", "closes", "cap_min"),
    )
    private val POOL = Shape.Obj(
        mapOf(
            "apps" to Shape.ListOf(Shape.Text),
            "all_browsers" to Shape.Bool,
            "wait_step_min" to Shape.Whole,
            "wait_cap_min" to Shape.Whole,
            "cooldown_factor" to Shape.Number,
            "cooldown_cap_min" to Shape.Whole,
            "session_gap_s" to Shape.Whole,
            "windows" to Shape.ListOf(WINDOW),
            "heavy_use" to Shape.Obj(mapOf("continuous_min" to Shape.Whole, "opens" to Shape.Whole, "within_min" to Shape.Whole)),
            "bail_out" to Shape.Obj(mapOf("extra_lock_min" to Shape.Whole, "daily_cap_min" to Shape.Whole)),
        ),
        required = setOf("apps", "windows"),
    )
    private val ROOT = Shape.Obj(
        mapOf(
            "version" to Shape.Whole,
            "pool" to POOL,
            "apps_button" to Shape.ListOf(
                Shape.Obj(mapOf("role" to Shape.OneOf(ROLES), "package" to Shape.Text, "label_contains" to Shape.Text)),
            ),
            "day" to Shape.Obj(mapOf("idle_end_min" to Shape.Whole, "fallback_end" to Shape.Text, "night_edge" to Shape.Text)),
            "takeover" to Shape.Obj(mapOf("glyph_off_after_s" to Shape.Whole, "full_seconds" to Shape.Whole)),
            "stages" to Shape.Obj(
                mapOf("promote_after_clean" to Shape.Whole, "demote_after_misses" to Shape.Whole, "window" to Shape.Whole),
            ),
            "protocols" to Shape.ListOf(PROTOCOL),
        ),
        required = setOf("version", "pool", "protocols"),
    )

    private fun checkShape(e: JsonElement, shape: Shape, path: String, ctx: Ctx) {
        when (shape) {
            Shape.Text -> if (!(e is JsonPrimitive && e.isString)) ctx.error(path, "must be text in quotes.")
            Shape.Whole -> if (!(e is JsonPrimitive && !e.isString && INTEGER.matches(e.content))) ctx.error(path, "must be a whole number.")
            Shape.Number -> if (!(e is JsonPrimitive && !e.isString && NUMBER.matches(e.content))) ctx.error(path, "must be a number.")
            Shape.Bool -> if (!(e is JsonPrimitive && !e.isString && (e.content == "true" || e.content == "false"))) {
                ctx.error(path, "must be true or false.")
            }
            Shape.AnyObject -> if (e !is JsonObject) ctx.error(path, "must be an object { ... }.")
            is Shape.OneOf -> if (!(e is JsonPrimitive && e.isString && e.content in shape.values)) {
                ctx.error(path, "must be one of ${shape.values.joinToString(", ")} (found ${e}).")
            }
            is Shape.ListOf -> {
                if (e !is JsonArray) {
                    ctx.error(path, "must be a list [ ... ].")
                    return
                }
                e.forEachIndexed { i, item -> checkShape(item, shape.item, "$path[$i]${label(item)}", ctx) }
            }
            is Shape.MapOf -> {
                if (e !is JsonObject) {
                    ctx.error(path, "must be an object { ... }.")
                    return
                }
                for ((k, v) in e) {
                    if (k.startsWith("_")) continue
                    if (k !in shape.keys) {
                        ctx.error(join(path, k), "\"$k\" is not allowed here; use ${shape.keys.joinToString(", ")}.")
                        continue
                    }
                    checkShape(v, shape.value, join(path, k), ctx)
                }
            }
            is Shape.Obj -> {
                if (e !is JsonObject) {
                    ctx.error(path, "must be an object { ... }.")
                    return
                }
                for (r in shape.required) {
                    val v = e[r]
                    if (v == null || v is JsonNull) ctx.error(join(path, r), "is missing.")
                }
                for ((k, v) in e) {
                    if (k.startsWith("_")) continue
                    val fieldShape = shape.fields[k]
                    if (fieldShape == null) {
                        ctx.warn(join(path, k), "unknown field; the app ignores it.")
                        continue
                    }
                    if (v is JsonNull) continue
                    checkShape(v, fieldShape, join(path, k), ctx)
                }
            }
        }
    }

    private fun label(item: JsonElement): String {
        val id = ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ""
        return " ($id)"
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    // ------------------------------------------------------------------ semantics

    private fun checkSemantics(file: ProtocolFile, root: JsonObject, ctx: Ctx) {
        if (file.version != ProtocolFile.SUPPORTED_VERSION) {
            ctx.error("version", "must be ${ProtocolFile.SUPPORTED_VERSION} (this app reads version ${ProtocolFile.SUPPORTED_VERSION} files).")
        }
        val ids = file.protocols.map { it.id }.toSet()
        checkPool(file.pool, ids, ctx)
        checkAppsButton(file.appsButton, ctx)
        checkDay(file.day, ctx)
        range(ctx, "takeover.glyph_off_after_s", file.takeover.glyphOffAfterS, 0, 600)
        range(ctx, "takeover.full_seconds", file.takeover.fullSeconds, 0, 600)
        range(ctx, "stages.promote_after_clean", file.stages.promoteAfterClean, 1, 100)
        range(ctx, "stages.window", file.stages.window, 1, 100)
        range(ctx, "stages.demote_after_misses", file.stages.demoteAfterMisses, 1, maxOf(1, file.stages.window))

        val seen = mutableSetOf<String>()
        val prayers = mutableMapOf<Prayer, String>()
        val rawProtocols = root["protocols"] as? JsonArray
        file.protocols.forEachIndexed { i, p ->
            val path = "protocols[$i] (${p.id})"
            if (!ID_PATTERN.matches(p.id)) {
                ctx.error("$path.id", "\"${p.id}\" is not a valid id: use lowercase letters, digits, - and _ (at most 48).")
            }
            if (!seen.add(p.id)) ctx.error("$path.id", "\"${p.id}\" is used by more than one protocol.")
            val raw = rawProtocols?.getOrNull(i) as? JsonObject
            checkProtocol(p, path, ids, raw, ctx)
            val prayer = (p.parsedTrigger as? Trigger.Adhan)?.prayer
            if (prayer != null) {
                val other = prayers.put(prayer, p.id)
                if (other != null) ctx.error("$path.trigger", "${prayer.json} already starts \"$other\"; one protocol per prayer.")
            }
        }
        if (file.protocols.isEmpty()) ctx.warn("protocols", "there are no protocols.")
    }

    private fun checkPool(pool: PoolConfig, ids: Set<String>, ctx: Ctx) {
        val seenApps = mutableSetOf<String>()
        pool.apps.forEachIndexed { i, app ->
            if (!looksLikePackage(app)) ctx.error("pool.apps[$i]", "\"$app\" does not look like a package name (e.g. com.instagram.android).")
            if (!seenApps.add(app)) ctx.warn("pool.apps[$i]", "\"$app\" is listed twice.")
        }
        if (pool.apps.isEmpty() && !pool.allBrowsers) ctx.warn("pool.apps", "the pool is empty: nothing is ever blocked.")
        range(ctx, "pool.wait_step_min", pool.waitStepMin, 1, 60)
        range(ctx, "pool.wait_cap_min", pool.waitCapMin, 1, 120)
        if (pool.waitCapMin < pool.waitStepMin) ctx.error("pool.wait_cap_min", "must be at least wait_step_min (${pool.waitStepMin}).")
        if (pool.cooldownFactor < 0.0 || pool.cooldownFactor > 10.0) ctx.error("pool.cooldown_factor", "must be from 0 to 10.")
        range(ctx, "pool.cooldown_cap_min", pool.cooldownCapMin, 0, 480)
        range(ctx, "pool.session_gap_s", pool.sessionGapS, 0, 600)
        val windowIds = mutableSetOf<String>()
        pool.windows.forEachIndexed { i, w ->
            val path = "pool.windows[$i] (${w.id})"
            if (!ID_PATTERN.matches(w.id)) ctx.error("$path.id", "\"${w.id}\" is not a valid id: use lowercase letters, digits, - and _.")
            if (!windowIds.add(w.id)) ctx.error("$path.id", "\"${w.id}\" is used by more than one window.")
            checkAnchor(w.opens, allowNext = false, "$path.opens", ids, ctx)
            checkAnchor(w.closes, allowNext = true, "$path.closes", ids, ctx)
            range(ctx, "$path.cap_min", w.capMin, 1, 600)
        }
        if (pool.windows.isEmpty()) ctx.warn("pool.windows", "there are no free windows: the pool can never be opened.")
        range(ctx, "pool.heavy_use.continuous_min", pool.heavyUse.continuousMin, 1, 240)
        range(ctx, "pool.heavy_use.opens", pool.heavyUse.opens, 2, 20)
        range(ctx, "pool.heavy_use.within_min", pool.heavyUse.withinMin, 1, 240)
        range(ctx, "pool.bail_out.extra_lock_min", pool.bailOut.extraLockMin, 0, 720)
        range(ctx, "pool.bail_out.daily_cap_min", pool.bailOut.dailyCapMin, 0, 1440)
    }

    private fun checkAnchor(text: String, allowNext: Boolean, path: String, ids: Set<String>, ctx: Ctx) {
        when (val a = Anchor.parse(text, allowNext)) {
            is Parsed.Error -> ctx.error(path, a.message)
            is Parsed.Ok -> {
                val ref = when (val v = a.value) {
                    is Anchor.Done -> v.protocolId
                    is Anchor.Start -> v.protocolId
                    else -> null
                }
                if (ref != null && ref !in ids) {
                    ctx.warn(path, "no protocol \"$ref\" in the file (yet), so the time after | is used.")
                }
            }
        }
    }

    private fun checkAppsButton(entries: List<AppsButtonEntry>, ctx: Ctx) {
        entries.forEachIndexed { i, e ->
            val path = "apps_button[$i]"
            val set = listOfNotNull(e.role, e.packageName, e.labelContains)
            if (set.size != 1) ctx.error(path, "give exactly one of role, package or label_contains.")
            e.packageName?.let { if (!looksLikePackage(it)) ctx.error("$path.package", "\"$it\" does not look like a package name.") }
            e.labelContains?.let { if (it.isBlank()) ctx.error("$path.label_contains", "must not be blank.") }
        }
        if (entries.isEmpty()) ctx.warn("apps_button", "the APPS button would be empty (the dialer still works for emergencies).")
    }

    private fun checkDay(day: DayConfig, ctx: Ctx) {
        range(ctx, "day.idle_end_min", day.idleEndMin, 10, 720)
        val fallback = WallClock.parseTime(day.fallbackEnd)
        val edge = WallClock.parseTime(day.nightEdge)
        if (fallback == null) ctx.error("day.fallback_end", "\"${day.fallbackEnd}\" must be a time HH:MM.")
        if (edge == null) ctx.error("day.night_edge", "\"${day.nightEdge}\" must be a time HH:MM.")
        if (fallback != null && fallback.hour >= 12) ctx.warn("day.fallback_end", "a fallback end after noon makes very short days.")
        if (edge != null && edge.hour >= 12) ctx.warn("day.night_edge", "a night edge after noon puts afternoon times into the night.")
    }

    private fun checkProtocol(p: Protocol, path: String, ids: Set<String>, raw: JsonObject?, ctx: Ctx) {
        if (p.title.isBlank()) ctx.error("$path.title", "must not be blank.")
        if (p.title.length > MAX_TITLE_CHARS) ctx.warn("$path.title", "longer than $MAX_TITLE_CHARS characters; it may not fit the takeover.")

        // Trigger.
        when (val t = Trigger.parse(p.trigger)) {
            is Parsed.Error -> ctx.error("$path.trigger", t.message)
            is Parsed.Ok -> {
                val trigger = t.value
                if (p.kind == ProtocolKind.PRAYER && trigger !is Trigger.Adhan) {
                    ctx.error("$path.trigger", "a prayer protocol starts at its adhan: use event:adhan:<prayer>.")
                }
                if (p.kind != ProtocolKind.PRAYER && trigger is Trigger.Adhan) {
                    ctx.error("$path.trigger", "event:adhan starts prayer protocols only (kind \"prayer\").")
                }
                if (trigger is Trigger.After) {
                    if (trigger.protocolId !in ids) ctx.error("$path.trigger", "there is no protocol \"${trigger.protocolId}\".")
                    if (trigger.protocolId == p.id) ctx.error("$path.trigger", "a protocol cannot start after itself.")
                }
            }
        }

        // Lock.
        val lock = p.lock
        lock.minutes?.let { range(ctx, "$path.lock.minutes", it, 1, MAX_LOCK_MIN) }
        lock.doneAfterMin?.let {
            range(ctx, "$path.lock.done_after_min", it, 0, MAX_LOCK_MIN)
            if (lock.minutes != null && it > lock.minutes) ctx.error("$path.lock.done_after_min", "must not be more than lock.minutes (${lock.minutes}).")
        }
        if (lock.type == LockType.NONE && lock.minutes != null) ctx.warn("$path.lock.minutes", "a lock of type none has nothing to time.")
        if (p.kind == ProtocolKind.PRAYER) {
            if (lock.minutes == null) ctx.error("$path.lock.minutes", "a prayer lock needs minutes (e.g. 20).")
            if (lock.doneAfterMin == null) ctx.warn("$path.lock.done_after_min", "without it \"I'M NOT OKAY\" cannot shorten the prayer lock.")
            if (lock.type == LockType.NONE) ctx.warn("$path.lock.type", "a prayer without a lock only shows its steps.")
        }

        // Versions.
        checkSteps(p.steps, "$path.steps", p, required = true, ctx = ctx)
        for (v in listOf(Version.SHORT, Version.MINIMAL, Version.SINGLE)) {
            val list = p.stepsFor(v, null) ?: continue
            checkVersionAllowed(p, v, "$path.${v.json}", ctx)
            checkSteps(list, "$path.${v.json}", p, required = true, ctx = ctx)
            if (v == Version.SINGLE && list.size != 1) ctx.error("$path.single", "must hold exactly one step.")
        }
        for ((day, versions) in p.weekdays) {
            val dayPath = "$path.weekdays.$day"
            versions.steps?.let { checkSteps(it, "$dayPath.steps", p, required = true, ctx = ctx) }
            for ((v, list) in listOf(Version.SHORT to versions.short, Version.MINIMAL to versions.minimal, Version.SINGLE to versions.single)) {
                if (list == null) continue
                checkVersionAllowed(p, v, "$dayPath.${v.json}", ctx)
                checkSteps(list, "$dayPath.${v.json}", p, required = true, ctx = ctx)
                if (v == Version.SINGLE && list.size != 1) ctx.error("$dayPath.single", "must hold exactly one step.")
            }
            if (versions.steps == null && versions.short == null && versions.minimal == null && versions.single == null) {
                ctx.warn(dayPath, "changes nothing.")
            }
        }

        // Params.
        checkParams(p, raw?.get("params") as? JsonObject, "$path.params", ctx)
    }

    private fun checkVersionAllowed(p: Protocol, v: Version, path: String, ctx: Ctx) {
        if (v !in p.kind.ladder) {
            val allowed = p.kind.ladder.joinToString(", ") { it.json }
            ctx.error(
                path,
                if (allowed.isEmpty()) "${p.kind.json} protocols have no step-down ladder; remove ${v.json}."
                else "${p.kind.json} protocols may only define $allowed.",
            )
        }
    }

    private fun checkSteps(steps: List<Step>, path: String, p: Protocol, required: Boolean, ctx: Ctx) {
        if (required && steps.isEmpty()) {
            ctx.error(path, "must hold at least one step.")
            return
        }
        var doneCount = 0
        steps.forEachIndexed { i, s ->
            val sp = "$path[$i]"
            if (s.text.isBlank()) ctx.error("$sp.text", "must not be blank.")
            if (s.text.length > MAX_STEP_TEXT_CHARS) ctx.warn("$sp.text", "longer than $MAX_STEP_TEXT_CHARS characters; keep steps short.")
            s.min?.let {
                if (s.type == StepType.CHECK || s.type == StepType.TIMER) {
                    range(ctx, "$sp.min", it, 1, MAX_STEP_MIN)
                } else {
                    ctx.warn("$sp.min", "only check and timer steps use min; it is ignored here.")
                }
            }
            s.lines?.let {
                if (s.type == StepType.TEXT) range(ctx, "$sp.lines", it, 1, MAX_LINES)
                else ctx.warn("$sp.lines", "only text steps use lines; it is ignored here.")
            }
            if (s.type == StepType.TIMER && s.min == null && p.lock.minutes == null) {
                ctx.error("$sp.min", "a timer without min lasts until the lock ends, but this lock has no minutes.")
            }
            if (s.type == StepType.DONE) doneCount++
        }
        if (doneCount > 1) ctx.error(path, "has more than one done step.")
        // Tapping done is what confirms a prayer: without it the steps would finish the run unconfirmed.
        if (doneCount == 0 && p.kind == ProtocolKind.PRAYER) ctx.error(path, "a prayer needs a done step (tapping it confirms the prayer).")
    }

    private fun checkParams(p: Protocol, raw: JsonObject?, path: String, ctx: Ctx) {
        val params = raw ?: return
        for ((name, value) in params) {
            if (name.startsWith("_")) continue
            val spec = p.kind.params[name]
            val vp = "$path.$name"
            if (spec == null) {
                val known = p.kind.params.keys.joinToString(", ").ifEmpty { "none" }
                ctx.warn(vp, "${p.kind.json} protocols do not use \"$name\" (known: $known); it is ignored.")
                continue
            }
            when (spec) {
                is ParamSpec.IntRange -> {
                    val n = wholeOrNull(value)
                    if (n == null) ctx.error(vp, "must be a whole number from ${spec.min} to ${spec.max}.")
                    else range(ctx, vp, n, spec.min, spec.max)
                }
                is ParamSpec.Bool -> if (!(value is JsonPrimitive && !value.isString && value.content in setOf("true", "false"))) {
                    ctx.error(vp, "must be true or false.")
                }
                ParamSpec.TextList -> if (!(value is JsonArray && value.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() })) {
                    ctx.error(vp, "must be a list of texts.")
                }
                ParamSpec.TextMap -> if (!(value is JsonObject && value.values.all { it is JsonPrimitive && it.isString })) {
                    ctx.error(vp, "must be an object of label: text.")
                }
                is ParamSpec.IntList -> {
                    if (value !is JsonArray || value.isEmpty()) {
                        ctx.error(vp, "must be a list of whole numbers.")
                    } else {
                        value.forEachIndexed { i, item ->
                            val n = wholeOrNull(item)
                            if (n == null) ctx.error("$vp[$i]", "must be a whole number.") else range(ctx, "$vp[$i]", n, spec.min, spec.max)
                        }
                    }
                }
            }
        }
    }

    private fun wholeOrNull(e: JsonElement): Int? =
        (e as? JsonPrimitive)?.takeIf { !it.isString && INTEGER.matches(it.content) }?.content?.toIntOrNull()

    private fun range(ctx: Ctx, path: String, value: Int, min: Int, max: Int) {
        if (value < min || value > max) ctx.error(path, "must be from $min to $max (found $value).")
    }

    /** Checked one segment at a time (a single regex with a repeated group overflows on absurd input). */
    fun looksLikePackage(name: String): Boolean {
        if (name.length > 255) return false
        val parts = name.split('.')
        if (parts.size < 2) return false
        return PACKAGE_FIRST_SEGMENT.matches(parts[0]) && parts.drop(1).all { PACKAGE_SEGMENT.matches(it) }
    }

    private class Ctx {
        val errors = mutableListOf<ProtocolIssue>()
        val warnings = mutableListOf<ProtocolIssue>()

        fun error(path: String, message: String) {
            errors += ProtocolIssue(path, message)
        }

        fun warn(path: String, message: String) {
            warnings += ProtocolIssue(path, message)
        }

        fun result(file: ProtocolFile?): ProtocolParseResult =
            if (file == null || errors.isNotEmpty()) ProtocolParseResult.Failure(errors.toList(), warnings.toList())
            else ProtocolParseResult.Success(file, warnings.toList())
    }
}

/** The bundled protocols.json, loaded from the core's resources. */
object BundledProtocols {
    const val RESOURCE = "/dev/vigil/core/protocols.json"

    /** The file's text. */
    val text: String by lazy {
        BundledProtocols::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
            ?: error("Missing bundled resource $RESOURCE")
    }

    /** The parse result, with any warnings. */
    val result: ProtocolParseResult by lazy { ProtocolParser.parse(text) }

    /** The parsed file. Throws if it is invalid, which the bundled-file unit test prevents from ever shipping. */
    val file: ProtocolFile by lazy {
        when (val r = result) {
            is ProtocolParseResult.Success -> r.file
            is ProtocolParseResult.Failure -> error("Bundled protocols.json is invalid: ${r.errors.joinToString("; ")}")
        }
    }
}
