package dev.vigil.core.machine

import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ToggleName
import dev.vigil.core.times.MyTimes
import dev.vigil.core.times.Slot
import org.junit.Assert.assertEquals
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
