package dev.vigil.data

import android.content.Context
import dev.vigil.core.engine.Outcome
import dev.vigil.core.engine.RuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the engine's [RuntimeState]. Every change goes through [update], which runs one pure engine step,
 * persists the new state and appends the step's events to the log, all under one lock.
 */
class StateStore(context: Context, private val log: EventLog) {
    private val prefs = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)
    private val lock = Any()
    private val _state = MutableStateFlow(RuntimeState.decode(prefs.getString(KEY, null)))
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    val current: RuntimeState get() = _state.value

    fun update(step: (RuntimeState) -> Outcome): Outcome = synchronized(lock) {
        val outcome = step(_state.value)
        if (outcome.state != _state.value) {
            // commit() rather than apply(): the process may be killed right after a takeover changes state.
            prefs.edit().putString(KEY, outcome.state.encode()).commit()
            _state.value = outcome.state
        }
        log.append(outcome.events)
        outcome
    }

    private companion object {
        const val KEY = "state"
    }
}
