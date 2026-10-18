package dev.vigil.ui.times

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.vigil.core.common.WallClock
import dev.vigil.core.protocol.TimeExpr
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import dev.vigil.ui.components.ChoiceChips
import dev.vigil.ui.components.DayChips
import dev.vigil.ui.components.NumberField
import dev.vigil.ui.components.PixelText
import dev.vigil.ui.components.TextInput
import dev.vigil.ui.components.TimeField
import dev.vigil.ui.components.normalizeTime
import dev.vigil.ui.components.tap
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type

/**
 * The six MY TIMES lists ([MyTimes.lists]), each with the fields its rows use. [core] is the list's name in the core
 * (its problems start with it, e.g. "Classes row 2: ..."). A new row starts with [newRow].
 */
internal enum class SlotListSpec(
    val core: String,
    val title: String,
    val rowTitle: String,
    val help: String,
    val addLabel: String,
    /** The caption of the start time. */
    val startCaption: String,
    /** The caption of the end time, or null when the rows have none. */
    val endCaption: String? = null,
    /** What the empty end field shows (the default the core uses, if any). */
    val endPlaceholder: String = "HH:MM",
    /** The caption of the free-text label, or null (meals pick their label from chips instead). */
    val labelCaption: String? = null,
    val labelPlaceholder: String = "",
    /** The caption of the minutes field, or null when the rows have none. */
    val minutesCaption: String? = null,
) {
    CLASSES(
        "classes", "CLASSES", "CLASS",
        "Your timetable. A prayer whose adhan comes during a class shows a silent card only; its takeover starts when " +
            "the class ends.",
        "+ ADD CLASS", "FROM", endCaption = "TO", labelCaption = "NAME", labelPlaceholder = "optional",
    ),
    MEALS(
        "meals", "MEALS", "MEAL",
        "Breakfast, lunch and dinner. Dinner opens the evening free window (after dinner, until wind-down).",
        "+ ADD MEAL", "AT",
    ),
    STUDY(
        "study", "STUDY", "STUDY BLOCK",
        "Study blocks and what to study in each (used by the study protocol once it is added).",
        "+ ADD STUDY BLOCK", "FROM", endCaption = "TO", labelCaption = "WHAT", labelPlaceholder = "e.g. Analysis 1",
    ),
    GYM(
        "gym", "GYM", "GYM SESSION",
        "When you train (used by the gym protocol once it is added).",
        "+ ADD GYM SESSION", "FROM", endCaption = "TO",
    ),
    CALLS(
        "calls", "CALLS", "CALL",
        "Calls you commit to: who, when to remind you, and the deadline (16:00 when empty). WRAP-UP = minutes into " +
            "a call before a quiet \"time to wrap up\" (20 when empty). Used by the call protocol once it is added.",
        "+ ADD CALL", "REMIND", endCaption = "BY", endPlaceholder = MyTimes.DEFAULT_CALL_DEADLINE,
        labelCaption = "WHO", labelPlaceholder = "e.g. Mom", minutesCaption = "WRAP-UP",
    ),
    COMMUTE(
        "commute", "COMMUTE", "COMMUTE",
        "When you leave for uni or home. Walking mode starts by itself then (once the walk protocol is added). LENGTH = " +
            "this trip's length when it differs from the usual commute below.",
        "+ ADD COMMUTE", "LEAVE", labelCaption = "WHERE", labelPlaceholder = "e.g. to uni", minutesCaption = "LENGTH",
    ),
    ;

    fun rows(t: MyTimes): List<Slot> = when (this) {
        CLASSES -> t.classes
        MEALS -> t.meals
        STUDY -> t.study
        GYM -> t.gym
        CALLS -> t.calls
        COMMUTE -> t.commute
    }

    fun with(t: MyTimes, rows: List<Slot>): MyTimes = when (this) {
        CLASSES -> t.copy(classes = rows)
        MEALS -> t.copy(meals = rows)
        STUDY -> t.copy(study = rows)
        GYM -> t.copy(gym = rows)
        CALLS -> t.copy(calls = rows)
        COMMUTE -> t.copy(commute = rows)
    }

    /** A new row: weekdays for the timetable-like lists, every day for meals and calls; the times still to type. */
    fun newRow(t: MyTimes): Slot = when (this) {
        MEALS -> Slot(EVERY_DAY, "", label = TimeExpr.MEAL_LABELS.firstOrNull { m -> t.meals.none { it.label == m } } ?: "lunch")
        CALLS -> Slot(EVERY_DAY, "", end = MyTimes.DEFAULT_CALL_DEADLINE)
        else -> Slot(WEEKDAYS, "")
    }

    /** True when one of [problems] is about this list's rows. */
    fun hasProblem(problems: List<String>): Boolean {
        val prefix = core.replaceFirstChar { it.uppercase() } + " row"
        return problems.any { it.startsWith(prefix) }
    }

    companion object {
        val EVERY_DAY: List<String> = WallClock.DAY_CODES.keys.toList()
        val WEEKDAYS: List<String> = listOf("mon", "tue", "wed", "thu", "fri")
    }
}

/** True when [text] (as typed) is not a usable time; empty counts as bad only when [required]. */
internal fun badTime(text: String?, required: Boolean = true): Boolean =
    if (text.isNullOrEmpty()) required else WallClock.parseTime(normalizeTime(text)) == null

/** One list's rows, each editable and removable, and the "+ ADD" row under them. */
@Composable
internal fun SlotList(
    spec: SlotListSpec,
    times: MyTimes,
    showErrors: Boolean,
    commuteMinutes: String,
    onChange: (MyTimes) -> Unit,
) {
    val rows = spec.rows(times)
    PixelText(spec.help, Type.Small)
    rows.forEachIndexed { i, slot ->
        Spacer(Modifier.height(12.dp))
        SlotRow(
            spec = spec,
            heading = "${spec.rowTitle} ${i + 1}",
            slot = slot,
            showErrors = showErrors,
            commuteMinutes = commuteMinutes,
            onChange = { changed -> onChange(spec.with(times, rows.toMutableList().also { it[i] = changed })) },
            onRemove = { onChange(spec.with(times, rows.filterIndexed { j, _ -> j != i })) },
        )
    }
    Spacer(Modifier.height(8.dp))
    PixelText(
        spec.addLabel,
        Type.Body,
        Modifier
            .fillMaxWidth()
            .tap { onChange(spec.with(times, rows + spec.newRow(times))) }
            .padding(vertical = 12.dp),
    )
}

/** One row: its days, its times and (for some lists) a label or minutes. */
@Composable
private fun SlotRow(
    spec: SlotListSpec,
    heading: String,
    slot: Slot,
    showErrors: Boolean,
    commuteMinutes: String,
    onChange: (Slot) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PixelText(heading, Type.Label, Modifier.weight(1f), color = Palette.White)
            PixelText("REMOVE", Type.Label, Modifier.tap(onClick = onRemove).padding(vertical = 6.dp, horizontal = 4.dp))
        }
        Spacer(Modifier.height(4.dp))
        DayChips(slot.days, { onChange(slot.copy(days = it)) })
        if (showErrors && slot.days.isEmpty()) PixelText("PICK AT LEAST ONE DAY", Type.Label, color = Palette.Red)

        if (spec == SlotListSpec.MEALS) {
            Spacer(Modifier.height(6.dp))
            ChoiceChips(TimeExpr.MEAL_LABELS, slot.label, { onChange(slot.copy(label = it)) })
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption(spec.startCaption)
            TimeField(slot.start, { onChange(slot.copy(start = it)) }, Modifier.width(FIELD_WIDTH), isError = showErrors && badTime(slot.start))
            val endCaption = spec.endCaption
            if (endCaption != null) {
                Spacer(Modifier.width(12.dp))
                Caption(endCaption, END_CAPTION_WIDTH)
                // A class needs an end (or minutes); elsewhere an empty end means the default, or none.
                val endRequired = spec == SlotListSpec.CLASSES && slot.minutes == null
                TimeField(
                    slot.end.orEmpty(),
                    { onChange(slot.copy(end = it.ifEmpty { null })) },
                    Modifier.width(FIELD_WIDTH),
                    placeholder = spec.endPlaceholder,
                    isError = showErrors && badTime(slot.end, endRequired),
                )
            }
        }

        val labelCaption = spec.labelCaption
        if (labelCaption != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption(labelCaption)
                TextInput(slot.label, { onChange(slot.copy(label = it)) }, Modifier.weight(1f), placeholder = spec.labelPlaceholder)
            }
        }

        val minutesCaption = spec.minutesCaption
        if (minutesCaption != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption(minutesCaption)
                val placeholder = when (spec) {
                    SlotListSpec.CALLS -> MyTimes.DEFAULT_CALL_WRAP_UP_MIN.toString()
                    else -> commuteMinutes
                }
                NumberField(
                    slot.minutes?.toString().orEmpty(),
                    { onChange(slot.copy(minutes = it.toIntOrNull())) },
                    Modifier.width(FIELD_WIDTH),
                    placeholder = placeholder,
                    isError = showErrors && slot.minutes?.let { it !in 1..600 } == true,
                )
                Spacer(Modifier.width(8.dp))
                PixelText("MIN", Type.Label)
            }
        }
    }
}

/** The small caption in front of a field, [width] wide so the fields line up. */
@Composable
internal fun Caption(text: String, width: Dp = CAPTION_WIDTH) {
    PixelText(text, Type.Label, Modifier.width(width))
}

internal val FIELD_WIDTH = 84.dp
internal val CAPTION_WIDTH = 76.dp
private val END_CAPTION_WIDTH = 28.dp
