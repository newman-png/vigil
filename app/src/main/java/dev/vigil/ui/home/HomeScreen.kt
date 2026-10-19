package dev.vigil.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.Graph
import dev.vigil.core.machine.Effect
import dev.vigil.core.machine.HomeStatus
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.run.JummahPart
import dev.vigil.core.run.Run
import dev.vigil.core.run.RunPhase
import dev.vigil.core.timeline.StartSource
import dev.vigil.core.timeline.StartStatus
import dev.vigil.core.timeline.TimelineItem
import dev.vigil.data.ReportExporter
import dev.vigil.engine.Vigil
import dev.vigil.guard.GuardScreens
import dev.vigil.trigger.StepCounter
import dev.vigil.ui.Format
import dev.vigil.ui.components.ChoiceChips
import dev.vigil.ui.components.Label
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.TextInput
import dev.vigil.ui.components.TierPips
import dev.vigil.ui.components.stagePips
import dev.vigil.ui.components.tap
import dev.vigil.ui.rememberNow
import dev.vigil.ui.setup.missingPermissions
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Where the home screen leads. QUIET, the switches, GOING TO SLEEP and EXPORT act right here, so they have no action. */
class HomeActions(
    val openMyTimes: () -> Unit,
    val openSetup: () -> Unit,
    val openTest: () -> Unit,
    /** Opens the takeover of the run that owns the screen (it does not come back by itself over VIGIL's own screens). */
    val openTakeover: () -> Unit,
    val openAudio: () -> Unit,
    /** Asks for PHYSICAL ACTIVITY (the step counter), the first time I'M WALKING is used. */
    val requestActivity: () -> Unit,
)

/**
 * HOME: the day, the pool, the flags, tonight's bedtime and the next wake alarm, a red line while a protocol (or the
 * night lock) owns the screen, the reset offer (START RESET / NOT NOW), LATER and RESET, START STUDY (a block of 50,
 * 100 or 150 minutes), the running promise and today's Jummah, an attention reset waiting to start, a running morning,
 * meal or gym to open, today's to-dos with tick boxes, prayers waiting for CONFIRM and today's timeline (study blocks
 * with what to study, with when each protocol last fired), today's call cards; then QUIET, I'M WALKING and I'M WITH
 * FRIENDS (each with when it ends), GOING TO SLEEP (CONFIRM ISHA first when Isha is still open), AUDIO, MY TIMES,
 * SETUP, TEST and EXPORT.
 * Everything shown comes from the engine ([Vigil.status], [Vigil.timeline]); nothing is decided here.
 *
 * [refreshKey] changes on every resume, so the permission count is re-read after a visit to system settings;
 * [laterRequest] changes when the always-on notification's LATER opened VIGIL, which opens the LATER sheet.
 */
@Composable
fun HomeScreen(refreshKey: Int, laterRequest: Int, actions: HomeActions) {
    val context = LocalContext.current
    val state by Vigil.state.collectAsState()
    // Minutes left, windows opening and planned starts going by change with the clock alone.
    val now = rememberNow()
    val status = remember(state, now) { Vigil.status() }
    val timeline = remember(state, now) { Vigil.timeline() }
    val missing = remember(refreshKey) { missingPermissions(context) }
    val zone = Graph.zone
    val nowAt = Instant.ofEpochMilli(now)

    var titleTaps by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var exporting by remember { mutableStateOf(false) }
    var askIsha by remember { mutableStateOf(false) }
    var laterOpen by remember { mutableStateOf(false) }
    var studyOpen by remember { mutableStateOf(false) }
    LaunchedEffect(laterRequest) {
        if (laterRequest > 0) laterOpen = true
    }
    // RESET takes the screen and leaving it early costs like any bail-out: the first tap only asks for a second one.
    var resetArmed by remember { mutableStateOf(false) }
    LaunchedEffect(resetArmed) {
        if (resetArmed) {
            delay(RESET_ARM_MS)
            resetArmed = false
        }
    }

    /** GOING TO SLEEP for real ([ishaPrayed]: the answer to CONFIRM ISHA), and what happened. */
    fun sleepNow(ishaPrayed: Boolean) {
        val before = Vigil.state.value.day?.startedAt
        val why = Vigil.status().sleepTapIgnored
        Vigil.goingToSleep(ishaPrayed)
        val ended = Vigil.state.value.day?.startedAt != before
        val text = if (ended) "The day has ended. Sleep well." else why ?: "The day goes on."
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(32.dp))
        PixelText(
            "VIGIL",
            Type.Logo,
            Modifier.tap {
                // Seven taps in a row (each within 1.5 s of the last) open the kill switch's 5-minute wait.
                val t = System.currentTimeMillis()
                titleTaps = if (t - lastTapMs > TAP_GAP_MS) 1 else titleTaps + 1
                lastTapMs = t
                if (titleTaps >= 7) {
                    titleTaps = 0
                    val until = status.suspendedUntil
                    if (until != null) {
                        Toast.makeText(context, "VIGIL is already suspended until ${Format.clock(until, zone)}.", Toast.LENGTH_SHORT).show()
                    } else {
                        GuardScreens.killSwitch(context)
                    }
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        PixelText(dayLine(status, zone), Type.Label, color = Palette.White)
        PixelText(status.pool, Type.Label, color = Palette.White)

        val flags = buildList {
            if (status.quiet) add("QUIET")
            status.suspendedUntil?.let { add("SUSPENDED UNTIL ${Format.clock(it, zone)}") }
            if (status.callActive) add("IN A CALL · TIMERS PAUSED")
            if (status.walking) add("WALKING")
            if (status.friends) add("WITH FRIENDS")
            if (status.dndOn) add("PHONE SILENT (JUMMAH)")
        }
        if (flags.isNotEmpty()) PixelText(flags.joinToString(" · "), Type.Label, color = Palette.White)
        prayersLine(status)?.let { PixelText(it, Type.Label) }
        status.nextStart?.let { PixelText("NEXT · ${startTime(it, zone)} ${it.start.title.uppercase()}", Type.Label) }
        sleepLine(status, zone)?.let { PixelText(it, Type.Label) }

        val owner = status.activeRun
        if (owner != null) {
            Spacer(Modifier.height(12.dp))
            PixelText(
                if (owner.phase == RunPhase.NIGHT) "NIGHT LOCK · UNTIL THE ALARM · OPEN" else "A PROTOCOL IS RUNNING · OPEN",
                Type.Body,
                Modifier.fillMaxWidth().tap(onClick = actions.openTakeover).padding(vertical = 8.dp),
                color = Palette.Red,
            )
        }
        status.nightLockUntil?.let {
            PixelText("THE NIGHT LOCK ENDS WHEN YOU ANSWER THE ALARM, ${Format.clock(it, zone)} AT THE LATEST", Type.Label)
        }

        // The reset offered after missed or left protocols in a row (or a missed Jummah): START or NOT NOW.
        status.offer?.let { offer ->
            Spacer(Modifier.height(12.dp))
            val title = Vigil.file().find(offer.protocol)?.title?.uppercase() ?: "RESET"
            val why = if (offer.reason == "jummah_missed") "JUMMAH WAS MISSED" else "${offer.reason.removePrefix("misses:")} MISSED OR LEFT IN A ROW"
            PixelText("$title? $why. ABOUT 15 MIN, NO PENALTY.", Type.Body, color = Palette.Red)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                PixelButton("START $title", { Vigil.answerOffer(start = true) }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                PixelButton("NOT NOW", { Vigil.answerOffer(start = false) }, Modifier.weight(1f), color = Palette.Dim)
            }
        }

        // LATER (a 10-minute promise) and RESET, always in reach.
        val promiseOn = status.promiseDelays.isNotEmpty()
        if (promiseOn || status.resetProtocol != null) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                if (promiseOn) {
                    PixelButton(if (laterOpen) "CLOSE" else "LATER", { laterOpen = !laterOpen }, Modifier.weight(1f))
                }
                if (promiseOn && status.resetProtocol != null) Spacer(Modifier.width(8.dp))
                if (status.resetProtocol != null) {
                    PixelButton(
                        if (resetArmed) "TAP AGAIN" else "RESET",
                        {
                            if (!resetArmed) {
                                resetArmed = true
                            } else {
                                resetArmed = false
                                Vigil.startReset()?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            }
                        },
                        Modifier.weight(1f),
                    )
                }
            }
        }
        if (resetArmed) PixelText("RESET: ABOUT 15 MIN WITH THE SCREEN LOCKED. TAP AGAIN TO START.", Type.Label, color = Palette.White)
        if (laterOpen && promiseOn) {
            LaterSheet(
                delays = status.promiseDelays,
                running = status.promise != null,
                onStart = { text, minutes ->
                    val notMade = Vigil.promise(text, minutes)
                    laterOpen = notMade != null
                    Toast.makeText(context, notMade ?: "Promised: in $minutes min.", Toast.LENGTH_SHORT).show()
                },
            )
        }
        // START STUDY: a block now, with what to study (optional) and its length.
        if (status.studyProtocol != null) {
            Spacer(Modifier.height(8.dp))
            PixelButton(if (studyOpen) "CLOSE" else "START STUDY", { studyOpen = !studyOpen }, Modifier.fillMaxWidth())
            if (studyOpen) {
                StudySheet(
                    lengths = status.studyLengths,
                    default = status.studyDefault,
                    onStart = { label, minutes ->
                        val notStarted = Vigil.startStudy(minutes, label)
                        studyOpen = false
                        Toast.makeText(context, notStarted ?: "Study block: $minutes min.", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
        status.promise?.let { PromiseRow(it, nowAt, zone) }
        status.jummah?.let { JummahRow(it, zone) }
        // An attention reset triggered after Dhuhr, waiting for its time, a class to end or the screen to be free.
        for (w in status.deferred) {
            val title = (Vigil.file().find(w.protocol)?.title ?: w.protocol).uppercase()
            PixelText("WAITING: $title FROM ${Format.clock(w.due, zone)} · ONCE NO CLASS OR TAKEOVER IS ON", Type.Label)
        }
        // Today's call cards (a stage-3 one is under OPEN): who, by when, the card again, and DONE once unlocked.
        for (r in status.calls.filter { it.phase == RunPhase.ACTIVE }) CallRow(r, zone)
        // A run without a screen lock (the morning): its takeover opens from here, like from its notification. The
        // promise, the call cards and the switches have their own rows.
        val others = status.runs.filter {
            it.id != owner?.id && it.promise == null && it.call == null && it.kind != ProtocolKind.WALK && it.kind != ProtocolKind.FRIENDS &&
                (it.phase == RunPhase.ACTIVE || it.phase == RunPhase.QUEUED)
        }
        for (r in others) {
            val openable = r.phase == RunPhase.ACTIVE && r.lock != LockType.SCREEN
            val step = if (openable) Vigil.stepView(r.id)?.let { " · STEP ${it.index + 1}/${it.total}" } ?: "" else ""
            PixelText(
                (if (r.phase == RunPhase.QUEUED) "WAITING: " else "RUNNING: ") + title(r) + step + if (openable) " · OPEN" else "",
                if (openable) Type.Body else Type.Label,
                Modifier.fillMaxWidth().tap(enabled = openable) { Vigil.openTakeover(context, r.id) }.padding(vertical = 4.dp),
            )
        }

        // Today's to-dos, written in last night's wind-down: a tap ticks one off (or back on).
        if (status.todos.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Label("TO-DOS")
            status.todos.forEachIndexed { i, t ->
                Row(
                    Modifier.fillMaxWidth().tap { Vigil.todoDone(i, !t.done) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixelText(if (t.done) "[X]" else "[ ]", Type.Body, color = if (t.done) Palette.Dim else Palette.White)
                    Spacer(Modifier.width(12.dp))
                    PixelText(t.text, Type.Body, Modifier.weight(1f), color = if (t.done) Palette.Dim else Palette.White)
                }
            }
        }

        // Prayers waiting for CONFIRM (lock over, a card during class, a reminder), and stage-3 reminders to mark done.
        val open = status.toConfirm + status.runs.filter { it.prayer == null && it.phase == RunPhase.REMINDER }
        if (open.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Label("OPEN")
            for (r in open) OpenRow(r, status, zone)
        }

        Spacer(Modifier.height(12.dp))
        Label("TODAY")
        if (timeline.isEmpty()) {
            PixelText("Nothing planned yet. A prayer shows here once its adhan has come at least once.", Type.Small)
        }
        for (item in timeline) TimelineRow(item, status.stages[item.start.protocolId] ?: 1, nowAt, zone)

        Spacer(Modifier.height(32.dp))
        Item(
            "QUIET",
            hint = if (status.quiet) "ON · TAKEOVERS SILENT" else "OFF",
            hintColor = if (status.quiet) Palette.White else Palette.Dim,
            onClick = { Vigil.setQuiet(!status.quiet) },
        )
        // The switches: on, the pool is blocked (no wait while with friends); each says when it ends by itself.
        SwitchItem(
            "I'M WALKING",
            on = status.walking,
            hint = when {
                !status.walking -> "OFF · BLOCKS THE POOL WHILE YOU WALK"
                status.stepsAvailable -> "ON SINCE ${status.walkingSince?.let { Format.clock(it, zone) } ?: "-"} · ENDS " +
                    "${status.walkEndsAt?.let { Format.clock(it, zone) } ?: "-"} IF YOU STOP MOVING · TAP TO END"
                else -> "ON · ENDS BY ${status.walkEndsAt?.let { Format.clock(it, zone) } ?: "-"} (NO STEP COUNTER) · TAP TO END"
            },
            onClick = {
                // The first time: ask for the step counter, so the walk can end when you stop moving.
                if (!status.walking && !Graph.settings.activityAsked && StepCounter.present(context) && !StepCounter.permitted(context)) {
                    Graph.settings.activityAsked = true
                    actions.requestActivity()
                }
                Vigil.setToggle(ToggleName.WALKING, !status.walking)
            },
        )
        SwitchItem(
            "I'M WITH FRIENDS",
            on = status.friends,
            hint = if (status.friends) {
                val until = status.friendsEndsAt
                "ON SINCE ${status.friendsSince?.let { Format.clock(it, zone) } ?: "-"} · UNTIL ${until?.let { Format.clock(it, zone) } ?: "-"}" +
                    (until?.let { " (${left(nowAt, it)} LEFT)" } ?: "") + " · TAP TO END"
            } else {
                "OFF · BLOCKS THE POOL, NO WAIT"
            },
            onClick = { Vigil.setToggle(ToggleName.FRIENDS, !status.friends) },
        )
        HoldItem(
            "GOING TO SLEEP",
            hint = if (status.sleepTapIgnored != null) "NOT BEFORE TODAY'S WAKE TIME" else "HOLD 2 S · ENDS THE DAY",
            holdMillis = 2_000,
            onComplete = {
                // Isha still open: ask first, so a prayed Isha is not logged as missed. Before the day's own wake time
                // the engine keeps the day going (it journals the tap as skipped) and says why.
                if (Vigil.status().ishaToConfirm != null) askIsha = true else sleepNow(ishaPrayed = false)
            },
        )
        if (askIsha) {
            PixelText("ISHA IS NOT CONFIRMED. DID YOU PRAY IT?", Type.Body, color = Palette.White)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                PixelButton(
                    "I PRAYED ISHA",
                    {
                        askIsha = false
                        sleepNow(ishaPrayed = true)
                    },
                    Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PixelButton("NOT YET", { askIsha = false }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            PixelButton(
                "SLEEP WITHOUT IT (ISHA MISSED)",
                {
                    askIsha = false
                    sleepNow(ishaPrayed = false)
                },
                Modifier.fillMaxWidth(),
                color = Palette.Dim,
            )
            Spacer(Modifier.height(12.dp))
        }
        Item(
            "AUDIO",
            hint = status.audio?.let { a ->
                when {
                    a.sound == Effect.Audio.WHITE_NOISE -> "WHITE NOISE"
                    a.sound == Effect.Audio.METRONOME -> "METRONOME ${a.bpm ?: Effect.Audio.DEFAULT_BPM}"
                    else -> (a.name ?: "A FILE").uppercase()
                } + " · PLAYING"
            } ?: "WHITE NOISE · METRONOME · FILES",
            hintColor = if (status.audio != null) Palette.White else Palette.Dim,
            onClick = actions.openAudio,
        )
        Item(
            "MY TIMES",
            hint = if (state.times.pending != null) "CHANGED · FROM TOMORROW" else if (!state.times.edited) "NOT SET" else null,
            hintColor = if (!state.times.edited) Palette.Red else Palette.Dim,
            onClick = actions.openMyTimes,
        )
        Item(
            "SETUP",
            hint = if (missing > 0) "$missing MISSING" else null,
            hintColor = Palette.Red,
            onClick = actions.openSetup,
        )
        Item("TEST", hint = null, onClick = actions.openTest)
        Item(
            "EXPORT",
            hint = if (exporting) "PREPARING..." else "REPORT + JOURNAL",
            onClick = {
                if (!exporting) {
                    exporting = true
                    ReportExporter.share(context) { opened ->
                        exporting = false
                        if (!opened) Toast.makeText(context, "Could not export the report.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        Line()
        Spacer(Modifier.height(24.dp))
    }
}

/** SUN 18 OCT · SINCE 06:12 · ENDS BY 06:00 */
private fun dayLine(status: HomeStatus, zone: ZoneId): String =
    "${Format.dayKey(status.day)} · SINCE ${Format.clock(status.dayStartedAt, zone)} · ENDS BY ${Format.clock(status.dayEndsBy, zone)}"

/**
 * BEDTIME 00:10 · ALARM TUE 07:00 · 2 MISSED NIGHTS: tonight's target (sleep shifting), the next wake alarm (RINGING
 * while it rings) and missed nights in a row; null without a sleep protocol or alarm.
 */
private fun sleepLine(status: HomeStatus, zone: ZoneId): String? {
    val parts = buildList {
        status.bedtime?.let { add("BEDTIME ${Format.clock(it, zone)}") }
        if (status.wakeAlarmRinging) add("ALARM RINGING") else status.wakeAlarmAt?.let { add("ALARM ${Format.dayTime(it, zone)}") }
        if (status.missedNights > 0) add(if (status.missedNights == 1) "1 MISSED NIGHT" else "${status.missedNights} MISSED NIGHTS")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** PRAYED FAJR, DHUHR · MISSED ASR · JUMMAH PRAYED, or null before anything happened today. */
private fun prayersLine(status: HomeStatus): String? {
    val parts = buildList {
        if (status.confirmedToday.isNotEmpty()) add("PRAYED " + status.confirmedToday.joinToString(", ") { it.title })
        if (status.missedToday.isNotEmpty()) add("MISSED " + status.missedToday.joinToString(", ") { it.title })
        status.jummahToday?.let { add("JUMMAH ${it.uppercase()}") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * START STUDY's sheet: what to study (optional; the takeover shows it big and the report logs it) and the block's
 * length from [lengths] ([default] picked first).
 */
@Composable
private fun StudySheet(lengths: List<Int>, default: Int?, onStart: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(default?.takeIf { it in lengths } ?: lengths.firstOrNull() ?: 100) }
    Spacer(Modifier.height(12.dp))
    Label("STUDY BLOCK")
    TextInput(text, { text = it.replace('\n', ' ') }, placeholder = "What to study? (optional)")
    Spacer(Modifier.height(8.dp))
    ChoiceChips(
        options = lengths.map { "$it MIN" },
        selected = "$minutes MIN",
        onSelect = { choice -> choice.removeSuffix(" MIN").toIntOrNull()?.let { minutes = it } },
    )
    Spacer(Modifier.height(8.dp))
    PixelText("Work rounds with short breaks fill the block. The screen stays locked: APPS has PoliTO, WhatsApp and calls.", Type.Small)
    Spacer(Modifier.height(8.dp))
    PixelButton("START", { onStart(text.trim(), minutes) }, Modifier.fillMaxWidth())
}

/** What a planned start is for: a study block's subject and length, a gym session's minutes (meals say it in the title). */
private fun startLabel(item: TimelineItem): String? {
    val slot = item.start.slot ?: return null
    val label = slot.label.takeIf { it.isNotBlank() && !it.equals(item.start.title, ignoreCase = true) }?.uppercase()
    val minutes = slot.minutes ?: slot.end?.let { Duration.between(slot.start, it).toMinutes().toInt() }
    return listOfNotNull(label, minutes?.let { "$it MIN" }).joinToString(" · ").ifEmpty { null }
}

/** A planned start's time; a prayer whose adhan has not come yet today is an estimate (~). */
private fun startTime(item: TimelineItem, zone: ZoneId): String =
    (if (item.start.estimated) "~" else "") + Format.clock(item.start.at, zone)

private fun title(r: Run): String = (Vigil.file().find(r.protocol)?.title ?: r.prayer?.title ?: r.protocol).uppercase()

/** A run waiting for CONFIRM (prayers) or DONE (a stage-3 reminder), with where it stands. */
@Composable
private fun OpenRow(r: Run, status: HomeStatus, zone: ZoneId) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            PixelText(title(r), Type.Body)
            val where = when (r.phase) {
                RunPhase.CARD -> "IN CLASS · THE TAKEOVER COMES WHEN IT ENDS"
                RunPhase.RETURNED -> "WINDOW CLOSING · THE TAKEOVER IS BACK"
                RunPhase.REMINDER -> "REMINDER"
                else -> "LOCK OVER · NOT CONFIRMED"
            }
            val window = when {
                r.prayer == null -> null
                r.prayer == Prayer.ISHA -> "UNTIL THE DAY ENDS (${Format.clock(status.dayEndsBy, zone)})"
                r.windowEstimated -> r.windowEndsAt?.let { "UNTIL ~${Format.clock(it, zone)}" }
                else -> null
            }
            PixelText(listOfNotNull(where, window).joinToString(" · "), Type.Label)
        }
        Spacer(Modifier.width(12.dp))
        PixelButton(if (r.prayer != null) "CONFIRM" else "DONE", { Vigil.confirm(r.id) })
    }
}

/**
 * LATER: one line of what you will do, and in how many minutes ([delays], from the promise protocol). At the time a
 * full takeover holds you to it. One promise at a time ([running]).
 */
@Composable
private fun LaterSheet(delays: List<Int>, running: Boolean, onStart: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(delays.firstOrNull { it == 10 } ?: delays.first()) }
    Spacer(Modifier.height(12.dp))
    Label("I'LL DO IT IN...")
    if (running) {
        PixelText("A PROMISE IS ALREADY RUNNING. ONE AT A TIME.", Type.Label, color = Palette.White)
        return
    }
    TextInput(text, { text = it.replace('\n', ' ') }, placeholder = "What will you do?")
    Spacer(Modifier.height(8.dp))
    ChoiceChips(
        options = delays.map { "$it MIN" },
        selected = "$minutes MIN",
        onSelect = { choice -> choice.removeSuffix(" MIN").toIntOrNull()?.let { minutes = it } },
    )
    Spacer(Modifier.height(8.dp))
    PixelButton("START", { onStart(text.trim(), minutes) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
}

/** The running promise: its line, when it is due (or that the pool is locked until DONE), and DONE. */
@Composable
private fun PromiseRow(r: Run, now: Instant, zone: ZoneId) {
    val promise = r.promise ?: return
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            PixelText((if (r.test) "PROMISE (TEST): " else "PROMISE: ") + promise.text, Type.Body)
            val left = (Duration.between(now, promise.dueAt).seconds + 59).coerceAtLeast(0) / 60
            val line = if (r.phase == RunPhase.WAITING) {
                "AT ${Format.clock(promise.dueAt, zone)} · IN $left MIN" + if (promise.snoozed) " · SNOOZED" else ""
            } else {
                "NOW · THE POOL IS LOCKED UNTIL DONE"
            }
            PixelText(line, Type.Label, color = if (r.phase == RunPhase.WAITING) Palette.Dim else Palette.Red)
        }
        Spacer(Modifier.width(12.dp))
        PixelButton("DONE", { Vigil.confirm(r.id) })
    }
}

/** Today's Jummah: its times and where it is, with I'M AT UNI on the way and CONFIRM after the lock. */
@Composable
private fun JummahRow(r: Run, zone: ZoneId) {
    val j = r.jummah ?: return
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            PixelText("JUMMAH ${Format.clock(j.jummahAt, zone)}", Type.Body)
            val where = when (j.part) {
                JummahPart.LEAVE -> "LEAVE BY ${Format.clock(j.leaveAt, zone)}"
                JummahPart.TRAVEL -> if (j.arrived) "AT UNI" else "ON THE WAY · AT UNI BY ${Format.clock(j.arriveAt, zone)}"
                JummahPart.LOCK -> "THE JUMMAH LOCK"
                JummahPart.CONFIRM -> "NOT CONFIRMED · UNTIL ${Format.clock(j.missedAt, zone)}"
            }
            PixelText("$where · SILENT FROM ${Format.clock(j.lockAt, zone)}", Type.Label)
        }
        val button = when {
            j.part == JummahPart.CONFIRM -> "CONFIRM"
            !j.arrived && (j.part == JummahPart.LEAVE || j.part == JummahPart.TRAVEL) -> "I'M AT UNI"
            else -> null
        }
        if (button != null) {
            Spacer(Modifier.width(12.dp))
            PixelButton(button, { Vigil.confirm(r.id) })
        }
    }
}

/** A call card of today: who, by when, and DONE once a call has unlocked it; a tap opens the card again. */
@Composable
private fun CallRow(r: Run, zone: ZoneId) {
    val context = LocalContext.current
    val c = r.call ?: return
    val view = Vigil.stepView(r.id)?.call
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).tap { Vigil.openTakeover(context, r.id) }) {
            PixelText("${title(r)} ${(r.label ?: "").uppercase()}".trim() + " · OPEN", Type.Body)
            val state = when {
                view != null && view.done && view.message -> "A MESSAGE INSTEAD"
                view?.done == true -> "CALLED · TAP DONE"
                else -> "BY ${Format.clock(c.dueAt, zone)} · DONE AFTER A CALL OF ${view?.minCallMin ?: 3} MIN"
            }
            PixelText(state, Type.Label, color = if (view?.done == true) Palette.White else Palette.Dim)
        }
        if (view?.done == true) {
            Spacer(Modifier.width(12.dp))
            PixelButton("DONE", { Vigil.confirm(r.id) })
        }
    }
}

/** A switch of HOME: its name big, and under it whether it is on and when it ends. */
@Composable
private fun SwitchItem(label: String, on: Boolean, hint: String, onClick: () -> Unit) {
    Line()
    Column(Modifier.fillMaxWidth().tap(onClick = onClick).padding(vertical = 20.dp)) {
        PixelText(label, Type.Item, color = if (on) Palette.White else Palette.Dim)
        Spacer(Modifier.height(4.dp))
        PixelText(hint, Type.Label, color = if (on) Palette.White else Palette.Dim)
    }
}

/** 2 H 10 MIN, 45 MIN: the time left until [until]. */
private fun left(now: Instant, until: Instant): String {
    val m = ((Duration.between(now, until).seconds + 59) / 60).coerceAtLeast(0)
    return if (m >= 60) "${m / 60} H ${m % 60} MIN" else "$m MIN"
}

/** One planned start: time, title, stage pips, what happened, and when that protocol last fired. */
@Composable
private fun TimelineRow(item: TimelineItem, stage: Int, now: Instant, zone: ZoneId) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText(startTime(item, zone), Type.Body, Modifier.width(64.dp))
            PixelText(
                item.start.title.uppercase() + (startLabel(item)?.let { " · $it" } ?: ""),
                Type.Body, Modifier.weight(1f), maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            TierPips(stagePips(stage))
            Spacer(Modifier.width(8.dp))
            val (text, color) = when (item.status) {
                StartStatus.PLANNED -> (if (item.start.source == StartSource.ADHAN) "AT ADHAN" else "PLANNED") to Palette.Dim
                StartStatus.FIRED -> "FIRED" to Palette.White
                StartStatus.SKIPPED -> "SKIPPED" to Palette.Dim
                StartStatus.NOT_FIRED -> "NOT FIRED" to Palette.Red
            }
            PixelText(text, Type.Label, color = color)
        }
        val last = item.lastFired?.let { "LAST FIRED ${Format.ago(it, now, zone)}" } ?: "NEVER FIRED"
        PixelText(last, Type.Label, Modifier.padding(start = 64.dp), color = Palette.Faint)
    }
}

/** A large uppercase word with its state on the right. */
@Composable
private fun Item(label: String, hint: String?, hintColor: Color = Palette.Dim, onClick: () -> Unit) {
    Line()
    Row(
        Modifier
            .fillMaxWidth()
            .tap(onClick = onClick)
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(label, Type.Item, Modifier.weight(1f))
        if (hint != null) {
            Spacer(Modifier.width(12.dp))
            PixelText(hint, Type.Label, color = hintColor)
        }
    }
}

/**
 * Like [Item], but it fires only after being held for [holdMillis]: while held, the hint counts down the seconds
 * left; letting go early does nothing.
 */
@Composable
private fun HoldItem(label: String, hint: String, holdMillis: Long, onComplete: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    val complete by rememberUpdatedState(onComplete)

    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (holding) {
            val left = holdMillis - (System.currentTimeMillis() - start)
            if (left <= 0) {
                holding = false
                complete()
                break
            }
            secondsLeft = ((left + 999) / 1000).toInt()
            delay(50)
        }
    }

    Line()
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(holdMillis) {
                awaitEachGesture {
                    awaitFirstDown()
                    secondsLeft = ((holdMillis + 999) / 1000).toInt()
                    holding = true
                    waitForUpOrCancellation()
                    holding = false
                }
            }
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText(label, Type.Item, Modifier.weight(1f), color = if (holding) Palette.Red else Palette.White)
        Spacer(Modifier.width(12.dp))
        PixelText(if (holding) "KEEP HOLDING · $secondsLeft" else hint, Type.Label, color = if (holding) Palette.White else Palette.Dim)
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Faint))
}

private const val TAP_GAP_MS = 1_500L

/** How long RESET waits for its second tap. */
private const val RESET_ARM_MS = 4_000L
