package dev.vigil.core.machine

import dev.vigil.core.journal.EntryType
import dev.vigil.core.pool.BlockReason
import dev.vigil.core.pool.PoolDecision
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.core.protocol.Intensity
import dev.vigil.core.protocol.LockType
import dev.vigil.core.protocol.ProtocolFile
import dev.vigil.core.protocol.StepType
import dev.vigil.core.protocol.Version
import dev.vigil.core.run.RunPhase
import dev.vigil.core.run.StageMemory
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol 6: meals (design M7), with the bundled `lunch` and `dinner` protocols and the default MY TIMES meals. */
class MealTest {
    private val full = BundledProtocols.file

    private fun Sim.blocks(): List<BlockReason> = (decision() as? PoolDecision.Blocked)?.blocks?.map { it.reason } ?: emptyList()

    /** The bundled file with lunch's `fallback` set. */
    private fun withFallback(vararg meals: String): ProtocolFile {
        val p = full.find("lunch")!!
        val params = JsonObject(p.params + ("fallback" to JsonArray(meals.map { JsonPrimitive(it) })))
        return full.copy(protocols = full.protocols.map { if (it.id == "lunch") p.copy(params = params) else it })
    }

    @Test
    fun lunchLocksThePoolUntilTheKitchenIsClean() {
        val sim = Sim(file = full)
        sim.at("2026-10-19T13:00")
        val r = sim.run("lunch")!!
        assertEquals(RunPhase.ACTIVE, r.phase)
        assertEquals("slot:meal", r.reason)
        assertEquals("lunch", r.label)
        assertEquals(LockType.POOL, r.lock)
        assertEquals(Intensity.SUBTLE, Machine.intensity(r, sim.file))
        // No takeover of its own: a notification shows the step and opens it.
        assertNull(Machine.activeRun(sim.state, sim.env))
        val notice = sim.lastEffects<Effect.Notify>().single { it.runId == r.id }
        assertEquals(NotifyKind.RUN, notice.kind)
        assertEquals("LUNCH · POOL LOCKED", notice.title)
        assertEquals("Go to the kitchen.", notice.text)
        assertTrue(BlockReason.RUN_LOCK in sim.blocks())
        for (i in 0..3) sim.after(5, Input.StepDone(r.id, i))
        assertTrue(BlockReason.RUN_LOCK in sim.blocks())
        // The cleanup ends the lock; the walk follows without one.
        sim.after(5, Input.StepDone(r.id, 4))
        val lunch = sim.run("lunch")!!
        assertEquals(LockType.NONE, lunch.lock)
        assertEquals(RunPhase.ACTIVE, lunch.phase)
        assertFalse(BlockReason.RUN_LOCK in sim.blocks())
        assertEquals("unlock", sim.lastOf(EntryType.LOCK_ENDED)!!.reason)
        val walk = sim.lastEffects<Effect.Notify>().single { it.runId == r.id }
        assertEquals("LUNCH", walk.title)
        assertEquals("Walk.", walk.text)
        assertEquals(StepType.TIMER, Machine.stepView(sim.state, r.id, sim.env)!!.step.type)
        sim.after(10)
        assertNull(sim.run("lunch"))
        assertEquals("clean", sim.lastOf(EntryType.COMPLETED)!!.reason)
        assertTrue(sim.lastEffects<Effect.CancelNotify>().any { it.id == Txn.runNotice(r) })
    }

    @Test
    fun noBreakfastRunTheMorningCoversIt() {
        val sim = Sim("2026-10-19T07:00", file = full)
        sim.at("2026-10-19T07:30")
        assertTrue(sim.state.runs.none { it.kind == dev.vigil.core.protocol.ProtocolKind.MEAL })
        val meals = Machine.timeline(sim.state, sim.env).filter { it.start.protocolId in setOf("lunch", "dinner") }
        assertEquals(listOf("lunch" to "lunch", "dinner" to "dinner"), meals.map { it.start.protocolId to it.start.slot!!.label })
        assertEquals(listOf(rome("2026-10-19T13:00"), rome("2026-10-19T19:30")), meals.map { it.start.at })
    }

    @Test
    fun dinnerDoneOpensTheEveningWindow() {
        val sim = Sim(file = full)
        assertEquals(rome("2026-10-19T20:15"), Machine.windows(sim.state, sim.env).single { it.id == "evening" }.opensAt)
        sim.at("2026-10-19T19:30")
        val r = sim.run("dinner")!!
        for (i in 0..4) sim.after(3, Input.StepDone(r.id, i))
        sim.after(10)
        assertNull(sim.run("dinner"))
        assertEquals(rome("2026-10-19T19:55"), Machine.windows(sim.state, sim.env).single { it.id == "evening" }.opensAt)
        // Inside the window now: the pool waits instead of being closed.
        assertTrue(sim.decision() is PoolDecision.Wait)
    }

    @Test
    fun stepDownsShowTheFallbackMeals() {
        val sim = Sim(file = withFallback("Eggs and toast", "Yogurt and fruit"))
        sim.at("2026-10-19T13:00")
        val id = sim.runId("lunch")
        assertEquals(emptyList<String>(), Machine.stepView(sim.state, id, sim.env)!!.fallback)
        sim.step(Input.NotOkay(id))
        assertEquals(Version.SHORT, sim.run("lunch")!!.version)
        assertEquals(listOf("Eggs and toast", "Yogurt and fruit"), Machine.stepView(sim.state, id, sim.env)!!.fallback)
        // Short: a simple meal and the cleanup, no walk; the cleanup still ends the lock, and the run with it.
        assertEquals(2, Machine.stepsFor(sim.run("lunch")!!, sim.env).size)
        sim.step(Input.NotOkay(id))
        assertEquals(Version.MINIMAL, sim.run("lunch")!!.version)
        assertEquals("Eat something simple.", Machine.stepView(sim.state, id, sim.env)!!.step.text)
        assertEquals(listOf("Eggs and toast", "Yogurt and fruit"), Machine.stepView(sim.state, id, sim.env)!!.fallback)
        // No single version: a third tap changes nothing.
        sim.step(Input.NotOkay(id))
        assertEquals(Version.MINIMAL, sim.run("lunch")!!.version)
        sim.step(Input.StepDone(id, 0))
        sim.step(Input.StepDone(id, 1))
        assertNull(sim.run("lunch"))
        assertEquals("done", sim.lastOf(EntryType.COMPLETED)!!.reason)
        // The bundled lunch has no fallback meals: nothing shows.
        val plain = Sim(file = full)
        plain.at("2026-10-19T13:00")
        plain.step(Input.NotOkay(plain.runId("lunch")))
        assertEquals(emptyList<String>(), Machine.stepView(plain.state, plain.runId("lunch"), plain.env)!!.fallback)
    }

    @Test
    fun leavingBeforeTheCleanupIsABailOut() {
        val sim = Sim(file = full)
        sim.at("2026-10-19T13:00")
        sim.step(Input.BailOut(sim.runId("lunch")))
        assertNull(sim.run("lunch"))
        assertEquals("lunch", sim.lastOf(EntryType.BAILED)!!.protocol)
        assertEquals(BlockReason.PENALTY, (sim.decision() as PoolDecision.Blocked).blocks.first { it.reason != BlockReason.OUTSIDE_WINDOW }.reason)
    }

    @Test
    fun stageThreeIsAReminderAndAMyTimesChangeMovesIt() {
        val sim = Sim(file = full)
        sim.state = sim.state.copy(stages = sim.state.stages + ("dinner" to StageMemory(3)))
        sim.at("2026-10-19T19:30")
        val r = sim.run("dinner")!!
        assertEquals(RunPhase.REMINDER, r.phase)
        assertTrue(sim.blocks().none { it == BlockReason.RUN_LOCK })
        sim.step(Input.Confirm(r.id))
        assertNull(sim.run("dinner"))
        // A later lunch from MY TIMES (applies from tomorrow).
        val late = Sim(file = full, times = MyTimes(meals = listOf(Slot(listOf("mon"), "14:15", label = "lunch"))))
        late.at("2026-10-19T13:00")
        assertNull(late.run("lunch"))
        late.at("2026-10-19T14:15")
        assertEquals("lunch", late.run("lunch")!!.label)
        assertNull(late.run("dinner"))
    }

    @Test
    fun onTheDayTheClocksGoBackAndWhileSuspended() {
        val sim = Sim("2026-10-25T08:00", file = full)
        sim.at("2026-10-25T13:00")
        val r = sim.run("lunch")!!
        assertEquals(rome("2026-10-25T13:00"), r.planned)
        assertEquals("2026-10-25T13:00:00+01:00", sim.lastOf(EntryType.FIRED)!!.scheduled)
        // VIGIL suspended at dinner: nothing starts, and the evening window opens at its time from MY TIMES.
        val off = Sim(file = full)
        off.at("2026-10-19T19:00", Input.Suspend(60))
        off.at("2026-10-19T19:30")
        assertNull(off.run("dinner"))
        assertEquals("suspended", off.lastOf(EntryType.SKIPPED)!!.reason)
        assertEquals(rome("2026-10-19T20:15"), Machine.windows(off.state, off.env).single { it.id == "evening" }.opensAt)
        // A meal running when the kill switch comes is dropped, its notification with it.
        val running = Sim(file = full)
        running.at("2026-10-19T13:00")
        val lunch = running.run("lunch")!!
        running.step(Input.Suspend(60))
        assertNull(running.run("lunch"))
        assertTrue(running.lastEffects<Effect.CancelNotify>().any { it.id == Txn.runNotice(lunch) })
    }

    @Test
    fun aCallPausesTheWalk() {
        val sim = Sim(file = full)
        sim.at("2026-10-19T13:00")
        val id = sim.runId("lunch")
        for (i in 0..4) sim.step(Input.StepDone(id, i))
        sim.after(4, Input.CallActive(true))
        sim.after(20, Input.CallActive(false))
        sim.after(5)
        assertEquals(RunPhase.ACTIVE, sim.run("lunch")!!.phase)
        sim.after(1)
        assertNull(sim.run("lunch"))
    }
}
