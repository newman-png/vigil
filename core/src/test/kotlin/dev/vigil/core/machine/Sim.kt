package dev.vigil.core.machine

import dev.vigil.core.journal.Entry
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.Lock
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.Protocol
import dev.vigil.core.protocol.ProtocolFile
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.run.Run
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.MyTimesStore
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

val ROME: ZoneId = ZoneId.of("Europe/Rome")

/** A local wall-clock time in Rome (2026-10-19 is a Monday; clocks go back on Sunday 2026-10-25 at 03:00). */
fun rome(local: String): Instant = LocalDateTime.parse(local).atZone(ROME).toInstant()

/** The protocol kinds that arrived with M6 and M7 (study block, attention reset, meals, gym). */
val M6_M7_KINDS = setOf(ProtocolKind.STUDY, ProtocolKind.ATTENTION, ProtocolKind.MEAL, ProtocolKind.GYM)

/** The protocol kinds that arrived with M8 (walk, friends, call, wellness nudges). */
val M8_KINDS = setOf(ProtocolKind.WALK, ProtocolKind.FRIENDS, ProtocolKind.CALL, ProtocolKind.NUDGE)

/**
 * The bundled file without its M6/M7 and M8 protocols: the tests of the earlier rules (prayers, pool, sleep, Jummah,
 * reset, promise) use it, so lunch, dinner, the attention reset after Dhuhr and the nudges do not run through them.
 */
val M5_FILE: ProtocolFile = BundledProtocols.file.copy(protocols = BundledProtocols.file.protocols.filter { it.kind !in M6_M7_KINDS + M8_KINDS })

/** The bundled file without its M8 protocols (the M6/M7 tests: no nudge comes in between). The M8 tests and the invariant walk use the whole file. */
val M7_FILE: ProtocolFile = BundledProtocols.file.copy(protocols = BundledProtocols.file.protocols.filter { it.kind !in M8_KINDS })

/**
 * Drives [Machine] like the Android layer would: keeps the state, collects every entry and effect. Starts the first
 * day with a Tick at [start] unless [startDay] is false.
 */
class Sim(
    start: String = "2026-10-19T08:00",
    val file: ProtocolFile = M5_FILE,
    times: MyTimes = MyTimes(),
    startDay: Boolean = true,
) {
    var now: Instant = rome(start)
    var state: MachineState = MachineState(times = MyTimesStore(current = times))
    val entries = mutableListOf<Entry>()
    val effects = mutableListOf<Effect>()
    var last: Outcome? = null

    init {
        if (startDay) step()
    }

    val env: MachineEnv get() = MachineEnv(now, ROME, file)

    fun step(input: Input = Input.Tick): Outcome {
        val o = Machine.step(state, input, env)
        state = o.state
        entries += o.entries
        effects += o.effects
        last = o
        return o
    }

    /** Moves the clock to a Rome local time and ticks. */
    fun at(local: String, input: Input = Input.Tick): Outcome {
        now = rome(local)
        return step(input)
    }

    /** Moves the clock forward and ticks. */
    fun after(minutes: Long, input: Input = Input.Tick): Outcome {
        now = now.plus(Duration.ofMinutes(minutes))
        return step(input)
    }

    fun afterSeconds(seconds: Long, input: Input = Input.Tick): Outcome {
        now = now.plusSeconds(seconds)
        return step(input)
    }

    fun types(): List<String> = entries.map { it.type }

    fun of(type: String): List<Entry> = entries.filter { it.type == type }

    fun lastOf(type: String): Entry? = entries.lastOrNull { it.type == type }

    fun run(protocolId: String): Run? = state.runs.firstOrNull { it.protocol == protocolId }

    fun runId(protocolId: String): String = run(protocolId)!!.id

    inline fun <reified T : Effect> effectsOf(): List<T> = effects.filterIsInstance<T>()

    inline fun <reified T : Effect> lastEffects(): List<T> = last!!.effects.filterIsInstance<T>()

    fun decision(isPool: Boolean = true) = Machine.poolDecision(state, env, isPool)

    fun clearLog() {
        entries.clear()
        effects.clear()
    }
}

/** A generic protocol for tests. */
fun testProtocol(
    id: String,
    trigger: String = "manual",
    kind: ProtocolKind = ProtocolKind.RESET,
    lock: Lock = Lock(LockType.SCREEN, 30, null),
    steps: List<Step> = listOf(Step("One."), Step("Two."), Step("Done.", StepType.DONE)),
    short: List<Step>? = null,
    minimal: List<Step>? = null,
    single: List<Step>? = null,
    stages: Boolean = true,
    weekdays: Map<String, dev.vigil.core.protocol.StepVersions> = emptyMap(),
    title: String = id.uppercase(),
) = Protocol(
    id = id, kind = kind, title = title, trigger = trigger, lock = lock, steps = steps, short = short, minimal = minimal,
    single = single, stages = stages, weekdays = weekdays,
)

/** The bundled file (without its M6/M7 protocols, unless [base] says otherwise) plus [extra] protocols. */
fun fileWith(vararg extra: Protocol, base: ProtocolFile = M5_FILE): ProtocolFile =
    base.copy(protocols = base.protocols + extra)

/** The bundled file without its prayers, plus [extra]. */
fun fileWithOnly(vararg extra: Protocol): ProtocolFile = BundledProtocols.file.copy(protocols = extra.toList())

/**
 * The bundled file with only its prayers (no wind-down, night lock, wake alarm or morning): for tests of the M1 rules
 * on their own (wind-down = bedtime_goal - 60 without a sleep protocol).
 */
val PRAYERS_ONLY: ProtocolFile = BundledProtocols.file.copy(protocols = BundledProtocols.file.protocols.filter { it.kind == ProtocolKind.PRAYER })
