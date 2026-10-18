package dev.vigil

import android.content.Context
import dev.vigil.core.machine.MachineEnv
import dev.vigil.core.protocol.BundledProtocols
import dev.vigil.data.Journal
import dev.vigil.data.MyTimesFile
import dev.vigil.data.Settings
import dev.vigil.data.StateStore
import java.time.Instant
import java.time.ZoneId

/** Process-wide singletons. Initialised by [VigilApp]. Screens use [dev.vigil.engine.Vigil] instead. */
object Graph {
    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    val settings by lazy { Settings(app) }
    val journal by lazy { Journal(app) }
    val myTimes by lazy { MyTimesFile(app) }
    val state by lazy { StateStore(app, journal, myTimes) }

    /** The phone's time zone, read each time (it changes when travelling). */
    val zone: ZoneId get() = ZoneId.systemDefault()

    /** Everything an engine step needs to know about the world right now. */
    fun env(now: Instant = Instant.now()): MachineEnv = MachineEnv(now, zone, BundledProtocols.file)
}
