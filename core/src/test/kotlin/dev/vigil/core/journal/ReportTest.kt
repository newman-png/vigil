package dev.vigil.core.journal

import dev.vigil.core.machine.Input
import dev.vigil.core.machine.ROME
import dev.vigil.core.machine.Sim
import dev.vigil.core.machine.fileWith
import dev.vigil.core.machine.rome
import dev.vigil.core.machine.testProtocol
import dev.vigil.core.protocol.Prayer
import dev.vigil.core.protocol.ProtocolKind
import dev.vigil.core.protocol.Step
import dev.vigil.core.protocol.StepType
import dev.vigil.core.times.MyTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    /** Two days of real machine output: prayers, the pool, a bail-out, a step-down, notes, app events. */
    private fun twoDays(): List<Entry> {
        val close = testProtocol(
            "close", kind = ProtocolKind.RESET,
            steps = listOf(Step("One line on the day.", StepType.TEXT)),
            short = listOf(Step("Short.")),
        )
        val sim = Sim(file = fileWith(close))
        sim.step(Input.AppExit("CRASH", null, "NullPointerException"))
        // Pool: one session of 3 minutes.
        sim.at("2026-10-19T08:30", Input.Foreground("com.instagram.android", true))
        sim.step(Input.PoolWaitStarted("com.instagram.android"))
        sim.after(1, Input.PoolWaitDone("com.instagram.android"))
        sim.after(3, Input.Foreground("com.nothing.launcher", false))
        sim.after(2)
        sim.step(Input.Foreground("com.instagram.android", true)) // cooldown: blocked
        sim.step(Input.Foreground("com.nothing.launcher", false))
        // Prayers: Dhuhr confirmed, Asr missed.
        sim.at("2026-10-19T12:30", Input.Adhan(Prayer.DHUHR))
        val d = sim.runId("prayer-dhuhr")
        sim.at("2026-10-19T12:38", Input.Confirm(d))
        sim.at("2026-10-19T12:50")
        sim.at("2026-10-19T15:40", Input.Adhan(Prayer.ASR))
        sim.at("2026-10-19T17:50", Input.Adhan(Prayer.MAGHRIB))
        // A bail-out and a note.
        // Maghrib's lock is over (unconfirmed) by 18:30, so the screen is free.
        sim.at("2026-10-19T18:30", Input.Manual("close"))
        sim.step(Input.BailOut(sim.runId("close")))
        sim.step(Input.Manual("close"))
        sim.step(Input.StepDone(sim.runId("close"), 0, "Good day, tired"))
        sim.step(Input.Manual("close", test = true))
        sim.step(Input.BailOut(sim.runId("close")))
        sim.at("2026-10-19T19:02", Input.Alarm(rome("2026-10-19T19:00")))
        sim.step(Input.SpeedBump("app_info"))
        sim.step(Input.Suspend(60))
        sim.at("2026-10-19T23:30", Input.GoingToSleep())
        // Day two: a step-down.
        sim.at("2026-10-20T09:00", Input.Manual("close"))
        sim.step(Input.NotOkay(sim.runId("close")))
        sim.step(Input.StepDone(sim.runId("close"), 0))
        return sim.entries.toList()
    }

    @Test
    fun daysAreSplitAtDayStart() {
        val days = Report.days(twoDays())
        assertEquals(listOf("2026-10-19", "2026-10-20"), days.map { it.key })
        val d1 = days[0]
        assertEquals("first_start", d1.startReason)
        assertEquals("sleep_tap", d1.endReason)
        assertEquals("2026-10-19T23:30:00+02:00", d1.endedAt)
        assertEquals(1, d1.poolOpens)
        assertEquals(180L, d1.poolSeconds)
        assertEquals(mapOf("cooldown" to 1), d1.poolBlocks)
        assertEquals(listOf("dhuhr"), d1.prayersConfirmed)
        assertEquals(listOf("asr"), d1.prayersMissed)
        assertEquals(1, d1.bailOuts.size)
        assertEquals(120, d1.penaltyMinutes)
        assertEquals(1, d1.appExits.size)
        assertEquals(1, d1.lateAlarms.size)
        assertEquals(1, d1.speedBumps.size)
        assertEquals(listOf("suspended", "resumed"), d1.killSwitch.map { it.reason })
        assertEquals(listOf("Good day, tired"), d1.notes.map { it.text })
        val close = d1.protocols["close"]!!
        assertEquals(2, close.fired)
        assertEquals(1, close.bailed)
        assertEquals(1, close.completed)
        assertEquals(1, close.clean)
        val dhuhr = d1.protocols["prayer-dhuhr"]!!
        assertEquals(1, dhuhr.completed)
        assertEquals(1, d1.protocols["prayer-asr"]!!.missed)
        // Day two is still running.
        val d2 = days[1]
        assertEquals("sleep_tap", d2.startReason)
        assertNull(d2.endedAt)
        assertEquals(1, d2.protocols["close"]!!.steppedDown)
        assertEquals(0, d2.protocols["close"]!!.clean)
    }

    @Test
    fun testEntriesNeverCount() {
        val days = Report.days(twoDays())
        assertTrue(days[0].entries.any { it.test == true })
        assertEquals(1, days[0].bailOuts.size)
    }

    @Test
    fun markdownReport() {
        val md = Report.markdown(twoDays(), ReportMeta(rome("2026-10-20T10:00"), ROME, "2.0"))
        assertTrue(md.startsWith("# VIGIL progress report\n"))
        assertTrue(md.contains("Generated 2026-10-20 10:00 (Europe/Rome), app 2.0. 2 days, 2026-10-19 to 2026-10-20."))
        assertTrue(md.contains("- Prayers: 1 confirmed, 2 missed, 0 without an adhan notification"))
        assertTrue(md.contains("- Pool: 1 opens, 3 min; blocked 1 times; 0 heavy use; penalty 120 min"))
        assertTrue(md.contains("## 2026-10-19 (Mon)"))
        assertTrue(md.contains("Started 2026-10-19 08:00 (first start) · ended 2026-10-19 23:30 (GOING TO SLEEP)"))
        assertTrue(md.contains("- **Prayers** confirmed: dhuhr · missed: asr"))
        assertTrue(md.contains("- **Pool** 1 opens, 3 min · blocked 1 (cooldown 1) · waits abandoned 0 · heavy use 0 · penalty 120 min"))
        assertTrue(md.contains("| Protocol | Fired | Completed | Clean | Stepped down | Bailed | Missed | Not fired | Skipped |"))
        assertTrue(md.contains("| close | 2 | 1 | 1 | 0 | 1 | 0 | 0 | 0 |"))
        assertTrue(md.contains("- **Bail-outs**: 18:30 close"))
        assertTrue(md.contains("- **Kill switch**: 19:02 suspended 60 min; 20:02 resumed (the suspension ended)"))
        assertTrue(md.contains("- Kill switch: 1 · speed bumps: 1 · app exits: 1 · late alarms: 1"))
        assertTrue(md.contains("- **Speed bumps**: 19:02 app_info"))
        assertTrue(md.contains("- **App exits**: 08:00 CRASH (NullPointerException)"))
        assertTrue(md.contains("- **Late alarms**: 19:02 (planned 19:00, 120 s late)"))
        assertTrue(md.contains("- **Notes**: 18:30 close: \"Good day, tired\""))
        assertTrue(md.contains("## 2026-10-20 (Tue)"))
        assertTrue(md.contains("still running"))
        assertTrue(md.contains("## How to read the journal"))
        assertTrue(md.contains("- `pool_session`:"))
    }

    @Test
    fun emptyJournal() {
        val md = Report.markdown(emptyList(), ReportMeta(rome("2026-10-20T10:00"), ROME))
        assertTrue(md.contains("No days recorded yet."))
    }

    @Test
    fun csvQuotesWhatNeedsQuoting() {
        val e = Entry("2026-10-19T12:40:00+02:00", EntryType.STEP_DONE, protocol = "x", step = 0, text = "a, \"b\"\nc", detail = " padded")
        val csv = Report.csv(listOf(e))
        val lines = csv.split("\r\n")
        assertEquals(Report.CSV_COLUMNS.joinToString(","), lines[0])
        assertEquals("2026-10-19T12:40:00+02:00,step_done,x,,0,,,,,,,\" padded\",\"a, \"\"b\"\"\nc\",,,,,", csv.removePrefix(lines[0] + "\r\n").removeSuffix("\r\n"))
        assertEquals(18, Report.CSV_COLUMNS.size)
    }

    @Test
    fun csvOfMachineOutput() {
        val entries = twoDays()
        val csv = Report.csv(entries)
        assertEquals(entries.size + 2, csv.split("\r\n").size)
        assertTrue(csv.contains(",pool_session,"))
    }

    /** A Friday: Jummah confirmed, a reset offered (NOT NOW) and then done, a promise kept after a snooze, one left. */
    private fun friday(): List<Entry> {
        val sim = Sim("2026-10-23T08:00", file = fileWith(testProtocol("alpha")), times = MyTimes())
        sim.at("2026-10-23T13:20")
        sim.at("2026-10-23T14:20")
        val j = sim.state.runs.first { it.jummah != null }.id
        sim.at("2026-10-23T14:22", Input.Confirm(j))
        sim.at("2026-10-23T14:35")
        sim.at("2026-10-23T14:50", Input.StepDone(j, 5))
        repeat(2) {
            sim.at("2026-10-23T16:00", Input.Manual("alpha"))
            sim.step(Input.BailOut(sim.runId("alpha")))
        }
        sim.step(Input.AnswerOffer(start = false))
        sim.at("2026-10-23T16:10", Input.Manual("reset"))
        val r = sim.runId("reset")
        sim.after(1, Input.StepDone(r, 1, "Phone in bed"))
        sim.after(3, Input.StepDone(r, 2))
        sim.after(5, Input.StepDone(r, 3))
        sim.after(5, Input.StepDone(r, 4))
        sim.step(Input.StepDone(r, 5))
        sim.at("2026-10-23T17:00", Input.Promise("Email the professor", 10))
        sim.at("2026-10-23T17:10", Input.Snooze(sim.state.runs.first { it.promise != null }.id))
        sim.at("2026-10-23T17:15")
        sim.at("2026-10-23T17:16", Input.Confirm(sim.state.runs.first { it.promise != null }.id))
        sim.at("2026-10-23T18:00", Input.Promise("Tidy the desk", 5))
        sim.at("2026-10-23T18:05", Input.BailOut(sim.state.runs.first { it.promise != null }.id))
        sim.at("2026-10-23T23:00", Input.GoingToSleep())
        return sim.entries.toList()
    }

    @Test
    fun jummahResetsAndPromises() {
        val d = Report.days(friday()).first()
        assertEquals("confirmed", d.jummah)
        assertEquals(2, d.arrived!!.minutes)
        assertEquals(listOf("jummah"), d.prayersConfirmed)
        assertEquals(1, d.resets.size)
        assertEquals("Phone in bed", d.resets.single().text)
        assertEquals(1, d.offers)
        assertEquals(1, d.offersDeclined)
        assertEquals(listOf("promised", "due", "snoozed", "due", "kept", "promised", "due", "bailed"), d.promises.map { it.reason })
        // The named reason is in the resets line, not repeated as a note.
        assertTrue(d.notes.none { it.text == "Phone in bed" && it.type == EntryType.REENTRY })
        val md = Report.markdown(friday(), ReportMeta(rome("2026-10-24T10:00"), ROME))
        assertTrue(md.contains("- Jummah: 1 confirmed, 0 missed"))
        assertTrue(md.contains("- Resets: 1 done; offered 1 time, 1 not now"))
        assertTrue(md.contains("- Promises: 2 made, 1 kept, 1 snoozed, 1 bailed, 0 expired at the day end"))
        assertTrue(md.contains("- **Prayers** confirmed: jummah"))
        assertTrue(md.contains("- **Jummah** confirmed · at uni 14:22 (2 min after the arrival time)"))
        assertTrue(md.contains("- **Resets**: 16:24 \"Phone in bed\": NEXT: WIND-DOWN AT 23:30."))
        assertTrue(md.contains("- **Promises**: 17:10 \"Email the professor\" snoozed; 17:16 \"Email the professor\" kept; 18:05 \"Tidy the desk\" bailed"))
        assertTrue(md.contains("- `promise`:"))
        val csv = Report.csv(friday())
        assertTrue(csv.contains(",promise,promise,"))
        assertTrue(csv.contains(",reentry,reset,"))
    }
}
