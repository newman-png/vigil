package dev.vigil.core.machine

import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateTest {
    @Test
    fun stateSurvivesEncodeDecode() {
        val sim = Sim(times = MyTimes(classes = listOf(Slot(listOf("mon"), "13:00", "14:30"))))
        sim.step(Input.Toggle(ToggleName.QUIET, true))
        sim.step(Input.EditMyTimes(MyTimes(bedtimeGoal = "22:45")))
        sim.at("2026-10-19T08:30", Input.Foreground("com.instagram.android", true))
        sim.step(Input.PoolWaitStarted("com.instagram.android"))
        sim.after(1, Input.PoolWaitDone("com.instagram.android"))
        sim.at("2026-10-19T13:10", Input.Adhan(Prayer.DHUHR))
        sim.step(Input.CallActive(true))
        val text = sim.state.encode()
        assertEquals(sim.state, MachineState.decode(text))
        assertTrue(text.contains("\"phase\":\"card\""))
        assertTrue(text.contains("\"last_adhan\":{\"dhuhr\":\"2026-10-19T11:10:00Z\"}"))
        // A decoded state keeps working.
        val o = Machine.step(MachineState.decode(text), Input.CallActive(false), sim.env)
        assertEquals(null, o.state.callSince)
    }

    @Test
    fun aSessionOutOfThePoolSurvivesEncodeDecode() {
        val ig = "com.instagram.android"
        val sim = Sim()
        sim.at("2026-10-19T08:30", Input.Foreground(ig, true))
        sim.step(Input.PoolWaitStarted(ig))
        sim.after(1, Input.PoolWaitDone(ig))
        sim.after(3, Input.Foreground("com.nothing.launcher", false))
        assertNull(sim.state.pool.session!!.inSince)
        val text = sim.state.encode()
        assertTrue(text.contains("\"inSince\":null"))
        val decoded = MachineState.decode(text)
        assertEquals(sim.state, decoded)
        // After an app restart the gap still ends the session with its 3 minutes (not counted again from its start).
        val o = Machine.step(decoded, Input.Tick, MachineEnv(sim.now.plusSeconds(60), ROME, sim.file))
        assertNull(o.state.pool.session)
        assertEquals(180L, o.state.pool.usedSeconds)
        assertTrue(o.entries.none { it.type == dev.vigil.core.journal.EntryType.HEAVY_USE })
        // A state saved by the first v2 build (nulls left out) reads the same way.
        val old = MachineState.JSON.encodeToString(MachineState.serializer(), sim.state)
        assertTrue(!old.contains("\"inSince\""))
        assertEquals(sim.state, MachineState.decode(old))
    }

    @Test
    fun oneUnreadablePartKeepsTheRest() {
        val sim = Sim(file = fileWith(testProtocol("alpha")))
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        sim.step(Input.BailOut(sim.runId("prayer-dhuhr")))
        sim.after(1, Input.Manual("alpha"))
        assertEquals(2, sim.state.runs.size)
        val text = sim.state.encode()
        // A run of a kind this version does not know (an older app reading a newer state): only that run is lost.
        val unknownKind = text.replace("\"kind\":\"reset\"", "\"kind\":\"nap\"")
        assertTrue(unknownKind != text)
        val a = MachineState.decode(unknownKind)
        assertEquals(listOf("prayer-dhuhr"), a.runs.map { it.protocol })
        assertEquals(sim.state.pool, a.pool)
        assertEquals(sim.state.stages, a.stages)
        assertEquals(sim.state.day, a.day)
        assertEquals(sim.state.prayers, a.prayers)
        assertEquals(sim.state.nextRun, a.nextRun)
        // An unknown prayer inside the day: the day keeps everything but that map.
        val unknownPrayer = text.replace("\"adhans\":{\"dhuhr\"", "\"adhans\":{\"jumuah\"")
        assertTrue(unknownPrayer != text)
        val b = MachineState.decode(unknownPrayer)
        assertEquals(sim.state.day!!.copy(adhans = emptyMap()), b.day)
        assertEquals(sim.state.runs, b.runs)
        assertEquals(sim.state.pool.penaltyUntil, b.pool.penaltyUntil)
    }

    @Test
    fun damagedStateStartsFresh() {
        assertEquals(MachineState(), MachineState.decode("{oops"))
        assertEquals(MachineState(), MachineState.decode(null))
        assertEquals(MachineState(screenOn = false), MachineState.decode("""{"screen_on":false,"future_field":1}"""))
    }

    @Test
    fun stepIsPure() {
        val sim = Sim()
        val before = sim.state
        val a = Machine.step(before, Input.Adhan(Prayer.DHUHR), sim.env)
        val b = Machine.step(before, Input.Adhan(Prayer.DHUHR), sim.env)
        assertEquals(a, b)
        assertEquals(before, sim.state)
    }

    @Test
    fun isPoolApp() {
        val file = dev.vigil.core.protocol.BundledProtocols.file
        assertTrue(Machine.isPoolApp("com.pinterest", file, emptySet()))
        assertTrue(Machine.isPoolApp("org.mozilla.firefox", file, setOf("org.mozilla.firefox")))
        assertTrue(!Machine.isPoolApp("com.whatsapp", file, setOf("org.mozilla.firefox")))
        val noBrowsers = file.copy(pool = file.pool.copy(allBrowsers = false))
        assertTrue(!Machine.isPoolApp("org.mozilla.firefox", noBrowsers, setOf("org.mozilla.firefox")))
    }
}
