package dev.vigil.takeover

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.vigil.core.machine.ExerciseView
import dev.vigil.core.machine.WorkoutView
import dev.vigil.core.run.GymSet
import dev.vigil.ui.theme.Palette
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * The gym's workout step: big targets for sweaty hands, white on black to read in the gym. On top the exercise being
 * logged (pre-filled with the last reps and weight the engine has for it), REPS, KG and REST steppers and LOG SET; after
 * each set the rest screen counts down to [WorkoutView.restUntil] and sounds its end ([onRestOver]: the chime, unless
 * QUIET), SKIP REST goes back. Under it every exercise with its last set and today's sets (a tap picks one), + ADD
 * EXERCISE (any name; the known names are offered as they match) and FINISH WORKOUT (tap twice). Every set goes to the
 * engine ([onLog]); the screen decides nothing about the workout itself.
 */
@Composable
internal fun WorkoutPanel(
    workout: WorkoutView,
    nowSec: () -> Long,
    onLog: (exercise: String, reps: Int, weight: Double, restS: Int?) -> Unit,
    onFinish: () -> Unit,
    onRestOver: () -> Unit,
    onTyping: () -> Unit,
) {
    val restOver by rememberUpdatedState(onRestOver)
    // Names typed with + ADD EXERCISE that have no set yet (once one is logged, the engine lists them).
    val added = remember { mutableStateListOf<String>() }
    val exercises = workout.exercises + added.filter { name -> workout.exercises.none { it.name.equals(name, ignoreCase = true) } }
        .map { ExerciseView(it, null, null, workout.exercises.firstOrNull()?.restS ?: DEFAULT_REST_S, emptyList(), null) }
    var selected by remember { mutableStateOf(workout.lastExercise ?: workout.exercises.firstOrNull()?.name) }
    val chosen = exercises.firstOrNull { it.name == selected } ?: exercises.firstOrNull()
    var adding by remember { mutableStateOf(false) }
    var finishArmed by remember { mutableStateOf(false) }
    val restUntil = workout.restUntil
    var restSkipped by remember(restUntil) { mutableStateOf(false) }

    // The rest's end sounds once, also when the screen is behind the lock screen (not after SKIP REST).
    LaunchedEffect(restUntil, restSkipped) {
        if (restSkipped) return@LaunchedEffect
        val end = restUntil ?: return@LaunchedEffect
        val ms = end.toEpochMilli() - System.currentTimeMillis()
        if (ms <= 0) return@LaunchedEffect
        delay(ms)
        restOver()
    }
    LaunchedEffect(finishArmed) {
        if (finishArmed) {
            delay(FINISH_ARM_MS)
            finishArmed = false
        }
    }

    val resting = restUntil != null && !restSkipped && restUntil.epochSecond + (if (restUntil.nano > 0) 1 else 0) > nowSec()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TakeoverText("WORKOUT", TakeoverType.Small)
        TakeoverText(plannedLine(workout, nowSec()), TakeoverType.Small)
        Spacer(Modifier.height(16.dp))
        if (resting && restUntil != null) {
            RestView(restUntil, nowSec(), workout, onSkip = { restSkipped = true })
            return@Column
        }
        if (chosen == null) {
            TakeoverText("ADD AN EXERCISE BELOW", TakeoverType.Step)
        } else {
            key(chosen.name) { SetEditor(chosen, onLog) }
        }
        Spacer(Modifier.height(28.dp))
        TakeoverText("EXERCISES", TakeoverType.Small)
        Spacer(Modifier.height(8.dp))
        for (ex in exercises) {
            key(ex.name) {
                ExerciseRow(ex, ex.name == chosen?.name) { selected = ex.name }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (adding) {
            AddExercise(
                known = exercises.map { it.name },
                onTyping = onTyping,
                onAdd = { name ->
                    val known = exercises.firstOrNull { it.name.equals(name, ignoreCase = true) }?.name
                    if (known == null) added += name
                    selected = known ?: name
                    adding = false
                },
                onCancel = { adding = false },
            )
        } else {
            BigButton("+ ADD EXERCISE", TakeoverType.Dim) { adding = true }
        }
        Spacer(Modifier.height(28.dp))
        BigButton(if (finishArmed) "TAP AGAIN TO FINISH" else "FINISH WORKOUT", Palette.White) {
            if (finishArmed) {
                finishArmed = false
                onFinish()
            } else {
                finishArmed = true
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The exercise being logged: its name, REPS, KG and REST steppers (pre-filled) and LOG SET. */
@Composable
private fun SetEditor(ex: ExerciseView, onLog: (String, Int, Double, Int?) -> Unit) {
    var reps by remember { mutableIntStateOf(ex.reps ?: DEFAULT_REPS) }
    var weight by remember { mutableDoubleStateOf(ex.weight ?: 0.0) }
    var rest by remember { mutableIntStateOf(ex.restS) }
    TakeoverText(ex.name, TakeoverType.Title)
    Spacer(Modifier.height(4.dp))
    TakeoverText(
        listOfNotNull(
            "SET ${ex.sets.size + 1}",
            ex.best?.let { "BEST ${setText(it)}" },
        ).joinToString(" · "),
        TakeoverType.Small,
    )
    Spacer(Modifier.height(16.dp))
    Stepper("REPS", reps.toString(), onMinus = { reps = (reps - 1).coerceAtLeast(1) }, onPlus = { reps = (reps + 1).coerceAtMost(MAX_REPS) })
    Stepper(
        "KG", if (weight == 0.0) "BODY" else kg(weight),
        onMinus = { weight = (weight - WEIGHT_STEP).coerceAtLeast(0.0) },
        onPlus = { weight = (weight + WEIGHT_STEP).coerceAtMost(MAX_WEIGHT) },
    )
    Stepper(
        "REST", "${rest}S",
        onMinus = { rest = (rest - REST_STEP).coerceAtLeast(MIN_REST_S) },
        onPlus = { rest = (rest + REST_STEP).coerceAtMost(MAX_REST_S) },
    )
    Spacer(Modifier.height(16.dp))
    // The rest is sent only when it was changed here: the engine keeps it for this exercise.
    BigButton("LOG SET", Palette.White, tall = true) { onLog(ex.name, reps, weight, rest.takeIf { it != ex.restS }) }
}

/** After a set: REST and its countdown, what comes next, and SKIP REST. */
@Composable
private fun RestView(until: Instant, nowSec: Long, workout: WorkoutView, onSkip: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    TakeoverText("REST", TakeoverType.Title)
    Spacer(Modifier.height(24.dp))
    TakeoverText(countdown(secondsUntil(until, nowSec)), TakeoverType.BigTimer)
    Spacer(Modifier.height(24.dp))
    val next = workout.exercises.firstOrNull { it.name == workout.lastExercise }
    if (next != null) {
        TakeoverText("NEXT: ${next.name} · SET ${next.sets.size + 1}", TakeoverType.Step)
        next.sets.lastOrNull()?.let {
            Spacer(Modifier.height(8.dp))
            TakeoverText("LAST: ${setText(it)}", TakeoverType.Small)
        }
    }
    Spacer(Modifier.height(40.dp))
    BigButton("SKIP REST", TakeoverType.Dim, onTap = onSkip)
}

/** One exercise: its name, the last set and how many sets today; the one being logged is white. */
@Composable
private fun ExerciseRow(ex: ExerciseView, chosen: Boolean, onTap: () -> Unit) {
    val color = if (chosen) Palette.White else TakeoverType.Dim
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        TakeoverText(ex.name, TakeoverType.Choice, Modifier.fillMaxWidth(), color = color)
        val last = when {
            ex.sets.isNotEmpty() -> "${ex.sets.size} SETS TODAY · " + ex.sets.joinToString(", ") { setText(it) }
            else -> ex.reps?.let { "LAST ${setText(GymSet(ex.name, it, ex.weight ?: 0.0))}" } ?: "NEW"
        }
        TakeoverText(last, TakeoverType.Small, Modifier.fillMaxWidth())
    }
}

/** + ADD EXERCISE: a name (any), with the known names that match offered under it. */
@Composable
private fun AddExercise(known: List<String>, onTyping: () -> Unit, onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val typed = name.trim()
    BasicTextField(
        value = name,
        onValueChange = {
            name = it.replace('\n', ' ').take(MAX_NAME_CHARS)
            onTyping()
        },
        singleLine = true,
        textStyle = TakeoverType.Field,
        cursorBrush = SolidColor(Palette.White),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            focus.clearFocus()
            if (typed.isNotEmpty()) onAdd(typed)
        }),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TakeoverType.Faint, RectangleShape)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    )
    val matches = if (typed.isEmpty()) emptyList() else known.filter { it.contains(typed, ignoreCase = true) }.take(MAX_MATCHES)
    for (m in matches) {
        key(m) { BigButton(m, TakeoverType.Dim) { onAdd(m) } }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { BigButton("ADD", if (typed.isNotEmpty()) Palette.White else TakeoverType.Faint) { if (typed.isNotEmpty()) onAdd(typed) } }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { BigButton("CANCEL", TakeoverType.Dim, onTap = onCancel) }
    }
}

/** LABEL  [ - ]  VALUE  [ + ]: big square targets on both sides. */
@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TakeoverText(label, TakeoverType.Small, Modifier.width(LABEL_WIDTH))
        StepperKey("-", onMinus)
        TakeoverText(value, TakeoverType.Step, Modifier.weight(1f))
        StepperKey("+", onPlus)
    }
}

@Composable
private fun StepperKey(text: String, onTap: () -> Unit) {
    Box(
        Modifier
            .width(KEY_SIZE)
            .height(KEY_SIZE)
            .border(1.dp, TakeoverType.Faint, RectangleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        TakeoverText(text, TakeoverType.Title)
    }
}

/** A full-width framed word that acts on a tap; [tall] for LOG SET. */
@Composable
private fun BigButton(text: String, color: Color, tall: Boolean = false, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(min = if (tall) TALL_HEIGHT else ROW_HEIGHT)
            .border(1.dp, if (color == Palette.White) Palette.White else TakeoverType.Faint, RectangleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        TakeoverText(text, TakeoverType.Choice, color = color)
    }
}

/** PLANNED 60 MIN · 23 MIN LEFT, or PLANNED 60 MIN · TIME IS UP once the planned minutes are over. */
private fun plannedLine(w: WorkoutView, nowSec: Long): String {
    val end = w.plannedEnd ?: return "PLANNED ${w.plannedMin} MIN"
    return if (end.epochSecond > nowSec) "PLANNED ${w.plannedMin} MIN · ${minutesUntil(end, nowSec)} MIN LEFT" else "PLANNED ${w.plannedMin} MIN · TIME IS UP"
}

/** 5 × 60 KG, or 12 REPS for body weight. */
private fun setText(set: GymSet): String = if (set.weight == 0.0) "${set.reps} REPS" else "${set.reps} × ${kg(set.weight)} KG"

/** 60 for 60.0, 62.5 for 62.5. */
private fun kg(weight: Double): String = if (weight % 1.0 == 0.0) weight.toLong().toString() else weight.toString()

private const val DEFAULT_REPS = 8
private const val DEFAULT_REST_S = 90
private const val MAX_REPS = 1000
private const val MAX_WEIGHT = 1000.0
private const val WEIGHT_STEP = 2.5
private const val REST_STEP = 15
private const val MIN_REST_S = 10
private const val MAX_REST_S = 600
private const val MAX_NAME_CHARS = 40
private const val MAX_MATCHES = 4
private const val FINISH_ARM_MS = 4_000L
private val ROW_HEIGHT = 56.dp
private val TALL_HEIGHT = 72.dp
private val KEY_SIZE = 64.dp
private val LABEL_WIDTH = 64.dp
