package dev.vigil.core.machine

import dev.vigil.core.day.DayRules
import dev.vigil.core.protocol.Lock
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.run.RunPhase
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.Random

/**
 * Random days of input against the rules that must always hold, whatever happens: the state survives encode/decode,
 * no run outlives two days, the day is never overdue, a call never pauses for longer than its cap, one screen lock at
 * a time, nothing waits in the queue while the screen is free, the day key only moves forward.
 */
class InvariantTest {
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"

    private val file0 = fileWith(
        testProtocol("alpha"),
        testProtocol("timed", trigger = "time:10:00", steps = listOf(Step("Sun.", StepType.CHECK, min = 2), Step("Breathe.", StepType.TIMER, min = 3))),
        testProtocol("sleep", trigger = "time:bedtime-60", kind = ProtocolKind.SLEEP, lock = Lock(LockType.POOL, 30, null)),
        testProtocol("meal", trigger = "slot:meal", kind = ProtocolKind.MEAL, lock = Lock(LockType.POOL, 20, null)),
        testProtocol("friends", trigger = "toggle:friends", kind = ProtocolKind.FRIENDS, lock = Lock(LockType.POOL)),
        testProtocol("free", trigger = "event:after:prayer-dhuhr", kind = ProtocolKind.ATTENTION, lock = Lock(LockType.SCREEN, 5, null)),
        testProtocol("reset", trigger = "event:misses:2", lock = Lock(LockType.SCREEN, 10, 2)),
    )
    private val file = file0.copy(pool = file0.pool.copy(windows = listOf(dev.vigil.core.protocol.FreeWindowConfig("day", "05:00", "23:30", 60), dev.vigil.core.protocol.FreeWindowConfig("x", "12:00", "next", 20))))
    private val times = MyTimes(classes = listOf(Slot(listOf("tue", "fri"), "13:00", "14:30", label = "Analysis")))

    /** About 0.7 s per seed (eight days each); widen the range locally when hunting a bug. */
    @Test
    fun rulesHoldThroughRandomDays() {
        for (seed in 1000L..1024L) walk(seed)
    }

    private fun walk(seed: Long) {
        val rnd = Random(seed)
        val sim = Sim("2026-10-19T03:00", file = file, times = times)
        var lastKey = sim.state.day!!.key
        val dayStarts = mutableListOf(sim.state.day!!.startedAt)
        val end = rome("2026-10-27T07:00") // across the clock change of 25 October
        var steps = 0
        while (sim.now.isBefore(end)) {
            sim.now = sim.now.plusSeconds(60L * (1 + rnd.nextInt(6)) + rnd.nextInt(60))
            val input = randomInput(rnd, sim)
            try {
                sim.step(input)
            } catch (e: Throwable) {
                throw AssertionError("seed $seed step $steps: $input threw", e)
            }
            if (sim.state.day!!.key != lastKey) dayStarts += sim.state.day!!.startedAt
            check(seed, steps, input, sim, lastKey, dayStarts.getOrElse(dayStarts.size - 2) { dayStarts[0] })
            lastKey = sim.state.day!!.key
            steps++
        }
        assertTrue(steps > 100)
    }

    private fun check(seed: Long, step: Int, input: Input, sim: Sim, lastKey: String, previousDayStart: java.time.Instant) {
        fun broken(what: String): Nothing {
            val ids = sim.state.runs.map { it.id }.toSet()
            val story = sim.entries.filter { it.run in ids || it.type == "day_start" || it.type == "day_end" || it.type == "skipped" }
                .takeLast(60).joinToString("\n") { "${it.ts} ${it.type} ${it.protocol ?: ""} ${it.run ?: ""} ${it.reason ?: ""} ${it.detail ?: ""} ${it.day ?: ""}" }
            throw AssertionError("seed $seed step $step after $input at ${sim.now}: $what\n${sim.state.runs}\n$story")
        }
        val s = sim.state
        val now = sim.now
        if (MachineState.decode(s.encode()) != s) broken("the state does not survive encode/decode")
        val day = s.day ?: broken("no day")
        if (day.key < lastKey) broken("the day key went back from $lastKey to ${day.key}")
        if (!DayRules.fallbackEnd(day.date, file.day, ROME).isAfter(now)) broken("the day ${day.key} is overdue")
        if (day.startedAt.isAfter(now)) broken("the day starts in the future")
        // No run outlives its day; a sleep run lasts into the next day at most; Isha closes with its day; the other
        // prayers' windows end within 20 hours of the adhan.
        for (r in s.runs.filter { !it.test }) {
            val ok = when {
                r.prayer == Prayer.ISHA -> !r.firedAt.isBefore(day.startedAt)
                r.prayer != null -> Duration.between(r.firedAt, now) <= Duration.ofHours(21)
                r.kind.survivesDayEnd -> !r.firedAt.isBefore(previousDayStart)
                else -> !r.firedAt.isBefore(day.startedAt)
            }
            if (!ok) broken("${r.id} (${r.protocol}, fired ${r.firedAt}) outlived its day")
        }
        s.callSince?.let { if (Duration.between(it, now) >= Machine.MAX_CALL_PAUSE) broken("the call pause outlived its cap") }
        val env = sim.env
        val view = View(s, env)
        val screenLocks = s.runs.count { it.lock == LockType.SCREEN && view.lockActive(it) }
        if (screenLocks > 1) broken("$screenLocks screen locks at once")
        if (!view.suspended && view.screenOwner() == null && s.runs.any { it.phase == RunPhase.QUEUED }) {
            broken("a run waits in the queue while the screen is free")
        }
        if (s.pool.penaltyMinutesToday > file.pool.bailOut.dailyCapMin) broken("penalty over the daily cap")
        s.pool.cooldownUntil?.let {
            val longest = Duration.ofMinutes(maxOf(file.pool.cooldownCapMin, file.pool.heavyUse.withinMin).toLong())
            if (Duration.between(now, it) > longest) broken("a cooldown longer than any rule allows")
        }
        val prayers = s.runs.filter { it.prayer != null && !it.test }.groupBy { it.prayer }
        if (prayers.values.any { it.size > 1 }) broken("two open runs for one prayer")
        Machine.nextWake(s, env)?.let { if (!it.isAfter(now)) broken("next wake $it is not after now") }
        // The queries the app calls never throw.
        Machine.status(s, env)
        Machine.timeline(s, env)
        Machine.poolStatus(s, env)
        s.runs.forEach { Machine.stepView(s, it.id, env) }
        assertEquals(s, sim.state)
    }

    private fun randomInput(rnd: Random, sim: Sim): Input {
        val runs = sim.state.runs
        val run = runs.takeIf { it.isNotEmpty() }?.get(rnd.nextInt(runs.size))
        val prayer = Prayer.entries[rnd.nextInt(Prayer.entries.size)]
        return when (rnd.nextInt(30)) {
            0, 1, 2, 3, 4, 5 -> Input.Tick
            6 -> Input.Adhan(prayer)
            7 -> Input.Manual(listOf("alpha", "timed", "sleep", "meal", "prayer-${prayer.json}")[rnd.nextInt(5)], test = rnd.nextInt(6) == 0)
            8, 9, 10 -> if (run != null) Input.StepDone(run.id, run.step, "one\ntwo\nthree") else Input.Tick
            11 -> if (run != null) Input.Confirm(run.id) else Input.Tick
            12 -> if (run != null) Input.NotOkay(run.id) else Input.Tick
            13 -> if (run != null && rnd.nextBoolean()) Input.BailOut(run.id) else Input.Tick
            14 -> Input.CallActive(rnd.nextInt(3) != 0)
            15, 16 -> rnd.nextBoolean().let { on -> Input.Screen(on, unlocked = on && rnd.nextInt(3) != 0) }
            17, 18 -> {
                val app = listOf(ig, yt, "com.nothing.launcher", null)[rnd.nextInt(4)]
                Input.Foreground(app, app == ig || app == yt)
            }
            19 -> Input.PoolWaitStarted(ig)
            20, 21 -> Input.PoolWaitDone(ig)
            22 -> Input.PoolWaitAbandoned(ig)
            23 -> if (rnd.nextInt(4) == 0) Input.GoingToSleep else Input.Tick
            24 -> Input.Toggle(ToggleName.entries[rnd.nextInt(3)], rnd.nextBoolean())
            25 -> if (rnd.nextInt(3) == 0) Input.WakeAlarm else Input.Tick
            26 -> if (rnd.nextInt(5) == 0) Input.Suspend(5 + rnd.nextInt(55)) else Input.Resume
            27 -> Input.Alarm(sim.now.minusSeconds(rnd.nextInt(300).toLong()))
            28 -> if (rnd.nextInt(10) == 0) Input.EditMyTimes(MyTimes(bedtimeGoal = "23:30")) else Input.Tick
            else -> Input.Tick
        }
    }
}
