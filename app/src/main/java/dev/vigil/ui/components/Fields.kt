package dev.vigil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.vigil.core.common.WallClock
import dev.vigil.ui.theme.Palette
import dev.vigil.ui.theme.Type

/**
 * A single-line text field whose text is [value] (the caller keeps it). [placeholder] shows faintly while it is empty;
 * [isError] draws the border red.
 */
@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String = "",
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = Type.Body,
        cursorBrush = SolidColor(Palette.White),
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
        ),
        modifier = modifier.padding(vertical = 4.dp),
        decorationBox = { inner -> FieldBox(value.isEmpty(), placeholder, isError) { inner() } },
    )
}

/**
 * A time field (`HH:MM`) on the number pad: only digits are typed and the colon goes in by itself ("730" shows 7:30,
 * "0730" 07:30). [normalizeTime] turns what was typed into `HH:MM` when saving. The cursor always stays at the end.
 */
@Composable
fun TimeField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "HH:MM",
    isError: Boolean = false,
) {
    BasicTextField(
        value = TextFieldValue(value, TextRange(value.length)),
        onValueChange = { typed ->
            val text = timeDigits(typed.text.filter { it.isDigit() }.take(4))
            if (text != value) onChange(text)
        },
        singleLine = true,
        textStyle = Type.Body.copy(textAlign = TextAlign.Center),
        cursorBrush = SolidColor(Palette.White),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = modifier.padding(vertical = 4.dp),
        decorationBox = { inner -> FieldBox(value.isEmpty(), placeholder, isError, center = true) { inner() } },
    )
}

/** A field for a whole number of up to [maxDigits] digits; empty is allowed (the caller decides what it means). */
@Composable
fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
    maxDigits: Int = 3,
) {
    BasicTextField(
        value = value,
        onValueChange = { typed ->
            val digits = typed.filter { it.isDigit() }.take(maxDigits)
            if (digits != value) onChange(digits)
        },
        singleLine = true,
        textStyle = Type.Body.copy(textAlign = TextAlign.Center),
        cursorBrush = SolidColor(Palette.White),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = modifier.padding(vertical = 4.dp),
        decorationBox = { inner -> FieldBox(value.isEmpty(), placeholder, isError, center = true) { inner() } },
    )
}

/** The frame every field shares: a thin border (red for an error), padding, and the placeholder while empty. */
@Composable
private fun FieldBox(empty: Boolean, placeholder: String, isError: Boolean, center: Boolean = false, inner: @Composable () -> Unit) {
    Box(
        Modifier
            .border(1.dp, if (isError) Palette.Red else Palette.Faint, RectangleShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) {
        if (empty && placeholder.isNotEmpty()) {
            PixelText(placeholder, Type.Body, color = Palette.Faint, textAlign = if (center) TextAlign.Center else null)
        }
        inner()
    }
}

/** A small square-cornered choice: filled white when [selected], outlined otherwise. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .then(if (selected) Modifier.background(Palette.White) else Modifier.border(1.dp, Palette.Faint, RectangleShape))
            .tap(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        PixelText(text.uppercase(), Type.Label, color = if (selected) Palette.Black else Palette.Dim, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** The seven weekdays as chips; tapping one adds or removes it. [days] holds day codes ("mon".."sun"). */
@Composable
fun DayChips(days: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (code in WallClock.DAY_CODES.keys) {
            val on = code in days
            Chip(
                code,
                on,
                onClick = {
                    val set = if (on) days.toSet() - code else days.toSet() + code
                    // Kept in week order, so the saved file reads naturally.
                    onChange(WallClock.DAY_CODES.keys.filter { it in set })
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One choice out of [options] as chips (e.g. breakfast, lunch, dinner). */
@Composable
fun ChoiceChips(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (option in options) {
            Chip(option, option == selected, onClick = { onSelect(option) }, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Up to four typed digits with the colon in place: "7" and "07" stay as they are, "073" is 07:3 on its way to 07:30
 * (and so is deleting back from 07:30), "730" is 7:30, "0730" is 07:30.
 */
private fun timeDigits(digits: String): String = when {
    digits.length <= 2 -> digits
    digits.length == 3 && digits.take(2).toInt() <= 23 && digits[2] <= '5' -> digits.take(2) + ":" + digits.drop(2)
    else -> digits.dropLast(2) + ":" + digits.takeLast(2)
}

/**
 * What was typed in a [TimeField] as `HH:MM`: "7:30" and "730" become 07:30, "7" and "19" whole hours. Text that is no
 * time of day is returned trimmed but otherwise unchanged, so the validation can name it.
 */
fun normalizeTime(text: String): String {
    val t = text.trim()
    val (hours, minutes) = if (':' in t) {
        t.substringBefore(':').filter { it.isDigit() } to t.substringAfter(':').filter { it.isDigit() }
    } else {
        val digits = t.filter { it.isDigit() }
        if (digits.length <= 2) digits to "00" else digits.dropLast(2) to digits.takeLast(2)
    }
    if (hours.isEmpty() || hours.length > 2 || minutes.length != 2) return t
    val out = hours.padStart(2, '0') + ":" + minutes
    return if (WallClock.parseTime(out) != null) out else t
}
