package dev.vigil.core.log

import dev.vigil.core.engine.ActivePlanResolver
import dev.vigil.core.engine.ActiveSet
import dev.vigil.core.engine.AdhanConfig
import dev.vigil.core.engine.BuiltinPlan
import dev.vigil.core.engine.Engine
import dev.vigil.core.engine.Env
import dev.vigil.core.engine.Outcome
import dev.vigil.core.engine.PlanSource
import dev.vigil.core.engine.RuntimeState
import dev.vigil.core.engine.TimeSchedule
import dev.vigil.core.plan.CheckInAnswer
import dev.vigil.core.plan.ParseResult
import dev.vigil.core.plan.Plan
import dev.vigil.core.plan.PlanParser
import dev.vigil.core.plan.Rating
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The week of 2026-10-19 (spec/sample-plan.json), played through the real engine so spec/sample-bundle.json shows
 * exactly what the app writes. The bundle is exported on the Monday after, once the plan has expired.
 */
internal object SampleWeek {
    val zone: ZoneId = ZoneId.of("Europe/London")

    /** spec/sample-plan.json exactly as the user imported it. */
    val planText: String = File(System.getProperty("vigil.repoRoot"), "spec/sample-plan.json").readText()

    private val parsed = PlanParser.parse(planText) as ParseResult.Success

    /** The same week as spec/sample-plan.json, so the two samples tell one story. */
    val plan: Plan = parsed.plan

    /** When the plan was imported (Sunday night); the app passes it to the engine as the plan key. */
    private const val IMPORTED_AT = "2026-10-18T21:14:09"

    /** Monday morning after the week: PLAN COMPLETE is on screen and the user taps EXPORT. */
    private const val EXPORTED_AT = "2026-10-26T07:12:40"

    /** The phone at the moment of the export, after the whole week. */
    val phone: Phone by lazy { play() }

    fun export(): String {
        val p = phone
        // The phone stores events as JSON Lines; the export reads them back.
        val stored = p.log.joinToString("\n") { LogJson.encodeLine(it) } + "\n"
        val exportedAt = p.timestamp(EXPORTED_AT)
        val status = when (p.activeAt(EXPORTED_AT).source) {
            PlanSource.PLAN -> BundleExport.STATUS_RUNNING
            else -> BundleExport.STATUS_COMPLETE
        }
        val meta = BundleMeta(
            exportedAt = exportedAt,
            appVersion = "1.1",
            timezone = zone.id,
            since = p.log.first().ts,
            until = exportedAt,
            planText = planText,
            planStatus = status,
            steppedDown = p.state.steppedDown,
            settings = linkedMapOf(
                LogExport.SETTING_ALLOW_QUIET_OVERRIDE to "true",
                "sound" to "true",
                "vibration" to "true",
                "glyph" to "true",
                "adhan_apps" to "any app",
                "adhan_phrase" to AdhanConfig.DEFAULT.requiredPhrase,
                "app_usage_detection" to "accessibility (instant)",
            ),
        )
        return BundleExport.build(LogJson.decodeLines(stored), meta)
    }

    private fun play(): Phone {
        val phone = Phone()
        phone.write(
            IMPORTED_AT, EventType.PLAN_IMPORTED,
            detail = "week_of ${plan.weekOf}, expires ${plan.expires}, generated_by ${plan.generatedBy}, " +
                "${plan.directives.size} directives, ${parsed.warnings.size} warnings",
        )

        // Monday: Fajr, all four steps, rated OK. The morning routine in full, rated OK.
        phone.trigger("2026-10-19T05:47:52", "fajr", "adhan")
        phone.steps("2026-10-19T05:47:53", 11, 31, 95, 412)
        phone.rate("2026-10-19T05:57:06", Rating.OK)
        phone.trigger("2026-10-19T07:30:02", "morning", "time", scheduledFor = "2026-10-19T07:30")
        phone.steps("2026-10-19T07:30:03", 9, 84, 22, 131)
        phone.rate("2026-10-19T07:34:15", Rating.OK)
        // Monday night: TikTok/Instagram opened six times in half an hour; one step ticked, then abandoned.
        phone.trigger("2026-10-19T21:37:26", "scroll-check", "app_usage")
        phone.steps("2026-10-19T21:37:27", 12)
        phone.run("2026-10-19T21:38:19") { s, e -> Engine.dismiss(s, e, phone.takeoverId) }

        // Tuesday: the morning routine drags, rated HARD. Quiet mode in a lecture holds Dhuhr until it ends.
        phone.trigger("2026-10-20T07:30:01", "morning", "time", scheduledFor = "2026-10-20T07:30")
        phone.steps("2026-10-20T07:30:02", 14, 162, 31, 240)
        phone.rate("2026-10-20T07:37:33", Rating.HARD)
        phone.run("2026-10-20T12:40:30") { s, e -> Engine.setQuiet(s, e, true) }
        phone.trigger("2026-10-20T12:52:02", "dhuhr", "adhan")
        phone.run("2026-10-20T13:55:44") { s, e -> Engine.setQuiet(s, e, false) }
        phone.steps("2026-10-20T13:55:45", 7, 75, 301) // dhuhr is never rated

        // Wednesday: HARD again, the second in a row, so the morning steps down to its two-step variants.easier.
        phone.trigger("2026-10-21T07:30:04", "morning", "time", scheduledFor = "2026-10-21T07:30")
        phone.steps("2026-10-21T07:30:05", 21, 203, 40, 377)
        phone.rate("2026-10-21T07:40:52", Rating.HARD)
        // The battery died at 10:05; back on at 11:24, too late for water-am.
        phone.catchUp(from = "2026-10-21T10:05:00", now = "2026-10-21T11:24:51")
        // Six opens again; this time the pause is taken. Its first completion this plan, so it is rated (sample).
        phone.trigger("2026-10-21T22:14:37", "scroll-check", "app_usage")
        phone.steps("2026-10-21T22:14:38", 13, 24, 11)
        phone.rate("2026-10-21T22:15:30", Rating.EASY)

        // Thursday: a hard day. The easier morning is on screen when "I'm not okay today" is held; only the prayers,
        // sleep and water-am are kept.
        phone.trigger("2026-10-22T07:30:03", "morning", "time", scheduledFor = "2026-10-22T07:30")
        phone.shown("2026-10-22T07:30:04")
        phone.run("2026-10-22T07:33:41") { s, e -> Engine.setBadDay(s, e, true) }
        phone.trigger("2026-10-22T09:00:01", "study-block", "time", scheduledFor = "2026-10-22T09:00")
        phone.trigger("2026-10-22T10:30:02", "water-am", "time", scheduledFor = "2026-10-22T10:30")
        phone.steps("2026-10-22T10:30:03", 18)
        phone.rate("2026-10-22T10:30:25", null) // SKIP
        phone.trigger("2026-10-22T23:00:02", "sleep", "time", scheduledFor = "2026-10-22T23:00")
        phone.steps("2026-10-22T23:00:03", 14, 131, 66, 41, 9)
        phone.rate("2026-10-22T23:04:28", Rating.HARD)

        // Friday: at the day boundary the service's housekeeping makes the check-in due, and its "same" answer (another
        // bad day) applies until it is answered. Fajr first; then, at the unlocked phone, the check-in: WORSE, so a
        // minimum day.
        phone.tick("2026-10-23T04:00:03")
        phone.trigger("2026-10-23T05:53:10", "fajr", "adhan")
        phone.steps("2026-10-23T05:53:11", 8, 22, 70, 352)
        phone.rate("2026-10-23T06:00:49", Rating.HARD)
        phone.present("2026-10-23T06:01:15")
        phone.answer("2026-10-23T06:01:31", CheckInAnswer.WORSE)
        phone.trigger("2026-10-23T07:30:02", "morning", "time", scheduledFor = "2026-10-23T07:30")
        phone.trigger("2026-10-23T09:00:01", "study-block", "time", scheduledFor = "2026-10-23T09:00")
        phone.trigger("2026-10-23T10:30:02", "water-am", "time", scheduledFor = "2026-10-23T10:30")
        phone.trigger("2026-10-23T23:00:01", "sleep", "time", scheduledFor = "2026-10-23T23:00")
        phone.steps("2026-10-23T23:00:02", 12, 125, 61, 33, 6)
        // No rating: the phone is on charge across the room, and the question lapses after 30 minutes.
        phone.tick("2026-10-23T23:34:02")

        // Saturday: after a minimum day the check-in is due again. BETTER: the whole plan runs, the morning in its
        // easier version.
        phone.tick("2026-10-24T04:00:04")
        phone.trigger("2026-10-24T05:58:30", "fajr", "adhan")
        phone.steps("2026-10-24T05:58:31", 6, 18, 64, 301)
        phone.rate("2026-10-24T06:05:55", Rating.OK)
        phone.present("2026-10-24T06:06:20")
        phone.answer("2026-10-24T06:06:34", CheckInAnswer.BETTER)
        phone.trigger("2026-10-24T07:30:02", "morning", "time", scheduledFor = "2026-10-24T07:30")
        phone.steps("2026-10-24T07:30:03", 7, 19)
        phone.rate("2026-10-24T07:30:33", Rating.OK)

        // Sunday: the clocks went back overnight (BST to GMT). The easier morning again, rated EASY.
        phone.trigger("2026-10-25T07:30:01", "morning", "time", scheduledFor = "2026-10-25T07:30")
        phone.steps("2026-10-25T07:30:02", 6, 17)
        phone.rate("2026-10-25T07:30:30", Rating.EASY)

        // Monday: the plan expires at 04:00 and the fallback plan takes over (the app posts PLAN COMPLETE); its Fajr
        // is the first takeover. Then the user taps EXPORT ([EXPORTED_AT]).
        phone.tick("2026-10-26T04:00:02")
        phone.trigger("2026-10-26T05:41:12", "fb-fajr", "adhan")
        phone.steps("2026-10-26T05:41:13", 7, 66, 205)
        phone.rate("2026-10-26T05:46:02", Rating.OK)
        return phone
    }

    class Phone {
        var state = RuntimeState()
        val log = mutableListOf<LogEvent>()
        private val ids = java.util.Random(20261019)
        private val planKey = LocalDateTime.parse(IMPORTED_AT).atZone(zone).toInstant().toEpochMilli().toString()

        val takeoverId: String get() = state.active!!.takeoverId

        fun timestamp(local: String): String =
            LocalDateTime.parse(local).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        fun activeAt(local: String): ActiveSet = ActivePlanResolver.resolve(plan, BuiltinPlan.plan, LocalDateTime.parse(local))

        fun run(local: String, action: (RuntimeState, Env) -> Outcome) {
            val now = LocalDateTime.parse(local).atZone(zone).toInstant()
            val env = Env(now, zone, activeAt(local), allowQuietOverride = true, planKey = planKey) { "%08x".format(ids.nextInt()) }
            val outcome = action(state, env)
            state = outcome.state
            log += outcome.events
        }

        fun trigger(local: String, id: String, trigger: String, scheduledFor: String? = null) =
            run(local) { s, e -> Engine.onTrigger(s, e, listOf(id), trigger, scheduledFor?.let(::timestamp)) }

        fun shown(local: String) = run(local) { s, e -> Engine.stepShown(s, e, takeoverId) }

        /** The service's housekeeping, which runs every few seconds; here only the ticks that log something. */
        fun tick(local: String) {
            val before = log.size
            run(local) { s, e -> Engine.refresh(s, e) }
            check(log.size > before) { "the tick at $local logged nothing" }
        }

        /** The phone was unlocked: the morning check-in fires if it is due. */
        fun present(local: String) {
            val before = state.checkIn
            run(local) { s, e -> Engine.onUserPresent(s, e) }
            check(before != null && state.checkIn?.fired == true) { "no check-in fired at $local" }
        }

        /** BETTER / SAME / WORSE on the check-in that is on screen. */
        fun answer(local: String, answer: CheckInAnswer) {
            check(state.active?.directiveId == Engine.CHECK_IN_ID) { "no check-in on screen at $local" }
            run(local) { s, e -> Engine.answerCheckIn(s, e, takeoverId, answer) }
        }

        /** EASY / OK / HARD (null = SKIP) after the last completion. The engine must have asked. */
        fun rate(local: String, rating: Rating?) {
            val open = checkNotNull(state.pendingRating) { "no rating was asked for before $local" }
            run(local) { s, e -> Engine.rate(s, e, open.takeoverId, rating) }
        }

        /** From the moment the next step appears, ticks it after each number of seconds (the next step appears at once). */
        fun steps(firstShown: String, vararg seconds: Long) {
            var at = LocalDateTime.parse(firstShown)
            for (s in seconds) {
                shown(at.toString())
                at = at.plusSeconds(s)
                val before = log.size
                run(at.toString()) { st, e -> Engine.stepDone(st, e, takeoverId, st.active!!.stepsDone) }
                check(log.size > before) { "step at $at was not accepted" }
            }
        }

        /** What the Android layer does after the phone was off: time triggers too far in the past are logged as missed. */
        fun catchUp(from: String, now: String) {
            val pass = TimeSchedule.pass(
                plan.directives,
                LocalDateTime.parse(from).atZone(zone).toInstant(),
                LocalDateTime.parse(now).atZone(zone).toInstant(),
                zone,
            )
            check(pass.due.isEmpty())
            for (fire in pass.missed) {
                for (id in fire.directiveIds) {
                    val d = plan.directive(id)!!
                    log += LogEvent(
                        timestamp(now), EventType.MISSED, directiveId = id, tier = d.tier, trigger = d.trigger.type,
                        scheduledFor = fire.at.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    )
                }
            }
        }

        fun write(local: String, type: String, detail: String) {
            log += LogEvent(timestamp(local), type, detail = detail)
        }
    }
}
