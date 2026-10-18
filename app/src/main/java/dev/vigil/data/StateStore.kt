package dev.vigil.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dev.vigil.core.machine.Input
import dev.vigil.core.machine.Machine
import dev.vigil.core.machine.MachineEnv
import dev.vigil.core.machine.MachineState
import dev.vigil.core.machine.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Holds the engine's [MachineState] (design 2.3). Every change goes through [step], which runs one pure
 * [Machine.step], saves the new state and appends the step's journal entries, all under one lock.
 */
class StateStore(context: Context, private val journal: Journal, private val myTimes: MyTimesFile) {
    private val file = AtomicFile(File(context.filesDir, "state/machine.json"))
    private val lock = Any()
    private val _state = MutableStateFlow(load())

    /** The persisted state, live. */
    val state: StateFlow<MachineState> = _state.asStateFlow()

    val current: MachineState get() = _state.value

    /**
     * Applies [input] at the moment [env] gives (read under the lock, so steps never go back in time). Returns the
     * state before the step and the step's outcome.
     */
    fun step(input: Input, env: () -> MachineEnv): Pair<MachineState, Outcome> = synchronized(lock) {
        val before = _state.value
        val outcome = Machine.step(before, input, env())
        val after = outcome.state
        if (after != before) {
            save(after)
            _state.value = after
            if (after.times != before.times) myTimes.save(after.times)
        }
        journal.append(outcome.entries)
        before to outcome
    }

    private fun load(): MachineState {
        val text = try {
            file.readFully().decodeToString()
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the engine state: ${e.message}")
            null
        }
        val state = MachineState.decode(text)
        // A state that never started a day is fresh (first start, or the file was lost or unreadable): keep the
        // user's MY TIMES from their own copy.
        if (state.day != null) return state
        val times = myTimes.load() ?: return state
        if (text != null) Log.w(TAG, "The engine state could not be read; starting fresh with the saved MY TIMES")
        return state.copy(times = times)
    }

    /**
     * Writes [state] atomically (a crash mid-write keeps the previous file). A failed write is logged: the state
     * stays in memory and the next step tries again.
     */
    private fun save(state: MachineState) {
        var out: FileOutputStream? = null
        try {
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            out = stream
            stream.write(state.encode().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            Log.w(TAG, "Could not save the engine state: ${e.message}")
            out?.let { file.failWrite(it) }
        }
    }

    private companion object {
        const val TAG = "VigilState"
    }
}
