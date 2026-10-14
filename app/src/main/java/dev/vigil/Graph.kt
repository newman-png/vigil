package dev.vigil

import android.content.Context
import dev.vigil.core.engine.ActivePlanResolver
import dev.vigil.core.engine.ActiveSet
import dev.vigil.core.engine.BuiltinPlan
import dev.vigil.core.engine.Env
import dev.vigil.data.EventLog
import dev.vigil.data.PlanRepository
import dev.vigil.data.Settings
import dev.vigil.data.StateStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Process-wide singletons. Initialised by [VigilApp]. */
object Graph {
    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    val settings by lazy { Settings(app) }
    val eventLog by lazy { EventLog(app) }
    val plans by lazy { PlanRepository(app) }
    val state by lazy { StateStore(app, eventLog) }

    val zone: ZoneId get() = ZoneId.systemDefault()

    fun activeSet(now: Instant = Instant.now()): ActiveSet =
        ActivePlanResolver.resolve(plans.plan, BuiltinPlan.plan, LocalDateTime.ofInstant(now, zone))

    /** Everything an engine step needs to know about the world right now. */
    fun env(now: Instant = Instant.now()): Env = Env(
        now = now,
        zone = zone,
        active = activeSet(now),
        allowQuietOverride = settings.allowQuietOverride,
        newId = { UUID.randomUUID().toString().substring(0, 8) },
    )
}
