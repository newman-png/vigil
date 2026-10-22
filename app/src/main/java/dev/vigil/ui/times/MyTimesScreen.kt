package dev.vigil.ui.times

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vigil.core.common.WallClock
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import dev.vigil.engine.Vigil
import dev.vigil.ui.components.Label
import dev.vigil.ui.components.NumberField
import dev.vigil.ui.components.PixelButton
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.Rule
import dev.vigil.ui.components.ScreenTitle
import dev.vigil.ui.components.Section
import dev.vigil.ui.components.TimeField
import dev.vigil.ui.components.normalizeTime
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type

/** Keeps the edit across rotation and process death, as MY TIMES JSON. */
private val MyTimesSaver = Saver<MyTimes, String>(save = { it.encode() }, restore = { MyTimes.decode(it) })

/**
 * MY TIMES (design 2.2): wake per weekday, the bedtime goal and start, the lists (classes, meals, study, gym, calls,
 * commute) with "+ ADD" rows and weekday chips, the commute minutes and the Jummah time: exactly the core's [MyTimes].
 * It edits what tomorrow will use ([dev.vigil.core.times.MyTimesStore.upcoming]); SAVE hands it to
 * [Vigil.saveMyTimes], which applies it from tomorrow (only the very first save applies at once). Times are typed on
 * the number pad and checked as HH:MM before saving; the core's own check ([MyTimes.problems]) has the last word.
 */
@Composable
fun MyTimesScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    val state by Vigil.state.collectAsState()
    val store = state.times
    // What the edit started from (unsaved changes are measured against it); the draft survives rotation.
    var base by remember { mutableStateOf(Vigil.myTimes().upcoming) }
    var draft by rememberSaveable(stateSaver = MyTimesSaver) { mutableStateOf(base) }
    var commuteText by rememberSaveable { mutableStateOf(base.commuteMin.toString()) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var backWarned by remember { mutableStateOf(false) }

    val dirty = draft != base || commuteText != base.commuteMin.toString()
    // Leaving with unsaved changes takes a second back press.
    BackHandler(enabled = dirty && !backWarned) {
        backWarned = true
        Toast.makeText(context, "Not saved. Press back again to leave without saving.", Toast.LENGTH_LONG).show()
    }

    val problems = remember(draft, commuteText) { problems(prepare(draft, commuteText), commuteText) }

    Column(Modifier.fillMaxWidth()) {
        ScreenTitle(
            "MY TIMES",
            "When you wake, eat, study and sleep. Protocols are placed on your day from these.",
        )
        AppliesNote(firstSave = !store.edited)
        if (store.pending != null) {
            Spacer(Modifier.height(12.dp))
            PixelText("A CHANGE IS ALREADY WAITING FOR TOMORROW. YOU ARE EDITING IT.", Type.Label, color = Palette.White)
            Spacer(Modifier.height(6.dp))
            PixelButton(
                "KEEP TODAY'S TIMES TOMORROW",
                {
                    // Saving the current times again drops the waiting change.
                    Vigil.saveMyTimes(store.current)
                    base = store.current
                    draft = store.current
                    commuteText = store.current.commuteMin.toString()
                    showErrors = false
                    Toast.makeText(context, "Tomorrow uses today's times again.", Toast.LENGTH_SHORT).show()
                },
                Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))

        Section("WAKE", hint = wakeHint(draft), hintColor = errorColor(showErrors && problems.any { it.startsWith("Wake") }), initiallyOpen = true) {
            PixelText(
                "Your wake time on each day (04:00 to 11:00). VIGIL's wake alarm rings then and the morning routine " +
                    "starts when you hold I'M UP. The pool stays blocked until the routine is done.",
                Type.Small,
            )
            Spacer(Modifier.height(6.dp))
            for (code in WallClock.DAY_CODES.keys) {
                val value = draft.wake[code].orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption(code.uppercase())
                    TimeField(
                        value,
                        { draft = draft.copy(wake = draft.wake + (code to it)) },
                        Modifier.width(FIELD_WIDTH),
                        isError = showErrors && badTime(value),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val monday = draft.wake["mon"].orEmpty()
            PixelButton(
                "SAME AS MONDAY EVERY DAY",
                { draft = draft.copy(wake = WallClock.DAY_CODES.keys.associateWith { monday }) },
                Modifier.fillMaxWidth(),
                enabled = monday.isNotEmpty(),
            )
        }

        Section(
            "BEDTIME",
            hint = "START ${draft.bedtimeStart.ifEmpty { "--:--" }} · GOAL ${draft.bedtimeGoal.ifEmpty { "--:--" }}",
            hintColor = errorColor(showErrors && problems.any { it.startsWith("Bedtime") }),
        ) {
            PixelText(
                "BEDTIME START: where the 20-minute shift begins (your real bedtime now). BEDTIME GOAL: where it ends " +
                    "up. Sleep shifting: tonight's target bedtime starts at START and moves 20 minutes earlier each " +
                    "night until it reaches GOAL; after two missed nights in a row it holds for a night. Wind-down " +
                    "starts an hour before tonight's target. Times before 05:00 count as that night.",
                Type.Small,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("START")
                TimeField(
                    draft.bedtimeStart,
                    { draft = draft.copy(bedtimeStart = it) },
                    Modifier.width(FIELD_WIDTH),
                    isError = showErrors && badTime(draft.bedtimeStart),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("GOAL")
                TimeField(
                    draft.bedtimeGoal,
                    { draft = draft.copy(bedtimeGoal = it) },
                    Modifier.width(FIELD_WIDTH),
                    isError = showErrors && badTime(draft.bedtimeGoal),
                )
            }
        }

        for (spec in SlotListSpec.entries) key(spec) {
            val rows = spec.rows(draft)
            val bad = showErrors && (spec.hasProblem(problems) || (spec == SlotListSpec.COMMUTE && commuteProblem(commuteText) != null))
            val count = if (rows.isEmpty()) "NONE" else if (rows.size == 1) "1 ROW" else "${rows.size} ROWS"
            Section(spec.title, hint = count, hintColor = errorColor(bad)) {
                SlotList(spec, draft, showErrors, commuteText, onChange = { draft = it })
                if (spec == SlotListSpec.COMMUTE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Caption("USUAL")
                        NumberField(
                            commuteText,
                            { commuteText = it },
                            Modifier.width(FIELD_WIDTH),
                            placeholder = "60",
                            isError = showErrors && commuteProblem(commuteText) != null,
                        )
                        Spacer(Modifier.width(8.dp))
                        PixelText("MIN EACH WAY", Type.Label)
                    }
                }
            }
        }

        Section(
            "JUMMAH",
            hint = draft.jummahTime.ifEmpty { "--:--" },
            hintColor = errorColor(showErrors && problems.any { it.startsWith("Jummah") }),
        ) {
            PixelText(
                "The Friday prayer at uni. On Fridays it replaces Dhuhr; the leave-by alert and the 45-minute lock are timed from it.",
                Type.Small,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("FRIDAY")
                TimeField(
                    draft.jummahTime,
                    { draft = draft.copy(jummahTime = it) },
                    Modifier.width(FIELD_WIDTH),
                    isError = showErrors && badTime(draft.jummahTime),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Rule()
        if (showErrors && problems.isNotEmpty()) {
            Label("FIX THESE FIRST", color = Palette.Red)
            for (p in problems) PixelText(p, Type.Small, Modifier.padding(vertical = 2.dp), color = Palette.Red)
            Spacer(Modifier.height(12.dp))
        }
        AppliesNote(firstSave = !store.edited)
        Spacer(Modifier.height(12.dp))
        PixelButton(
            "SAVE",
            {
                val ready = prepare(draft, commuteText)
                val issues = problems(ready, commuteText)
                // Show the times as they will be saved (7:30 -> 07:30), also when something still needs fixing.
                draft = ready
                if (issues.isNotEmpty()) {
                    showErrors = true
                    Toast.makeText(context, "Not saved: ${issues.size} thing(s) to fix, listed above SAVE.", Toast.LENGTH_LONG).show()
                } else {
                    val first = !Vigil.myTimes().edited
                    Vigil.saveMyTimes(ready)
                    Toast.makeText(
                        context,
                        if (first) "Saved. Your times apply now." else "Saved. Your new times apply from tomorrow.",
                        Toast.LENGTH_LONG,
                    ).show()
                    onSaved()
                }
            },
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** The big notice that a change waits for tomorrow (or, before the first save, that it applies at once). */
@Composable
private fun AppliesNote(firstSave: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.White, RectangleShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (firstSave) {
            PixelText("FIRST SAVE APPLIES NOW", Type.Huge)
            Spacer(Modifier.height(4.dp))
            PixelText("After this, every change applies from tomorrow.", Type.Small)
        } else {
            PixelText("APPLIES FROM TOMORROW", Type.Huge)
            Spacer(Modifier.height(4.dp))
            PixelText("Today keeps the times it started with. The new times take over when the next day starts.", Type.Small)
        }
    }
}

private fun errorColor(bad: Boolean) = if (bad) Palette.Red else Palette.Dim

/** WAKE's hint: one time when every day is the same, else the weekday range. */
private fun wakeHint(t: MyTimes): String {
    val values = WallClock.DAY_CODES.keys.map { t.wake[it].orEmpty() }.distinct()
    return if (values.size == 1) "DAILY ${values.first().ifEmpty { "--:--" }}" else "PER DAY"
}

/** The commute minutes as typed: null when usable (0 to 300), else what is wrong. */
private fun commuteProblem(text: String): String? {
    val n = text.toIntOrNull()
    return if (n == null || n !in 0..300) "Commute: type the usual minutes each way (0 to 300)." else null
}

/**
 * The draft as it would be saved: every time as HH:MM where it can be read as one, empty optional ends and labels
 * removed or trimmed, and the commute minutes from their field (unchanged while that field is not a number).
 */
private fun prepare(t: MyTimes, commuteText: String): MyTimes {
    fun slots(list: List<Slot>) = list.map { s ->
        s.copy(start = normalizeTime(s.start), end = s.end?.let(::normalizeTime)?.ifEmpty { null }, label = s.label.trim())
    }
    return t.copy(
        wake = t.wake.mapValues { (_, v) -> normalizeTime(v) },
        bedtimeGoal = normalizeTime(t.bedtimeGoal),
        bedtimeStart = normalizeTime(t.bedtimeStart),
        classes = slots(t.classes),
        meals = slots(t.meals),
        study = slots(t.study),
        gym = slots(t.gym),
        calls = slots(t.calls),
        commute = slots(t.commute),
        commuteMin = commuteText.toIntOrNull()?.takeIf { it in 0..300 } ?: t.commuteMin,
        jummahTime = normalizeTime(t.jummahTime),
    )
}

/** Everything to fix before saving: the commute field, then the core's own check of the prepared times. */
private fun problems(prepared: MyTimes, commuteText: String): List<String> =
    listOfNotNull(commuteProblem(commuteText)) + prepared.problems()
