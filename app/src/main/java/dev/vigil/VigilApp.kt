package dev.vigil

import android.app.Application
import dev.vigil.engine.Vigil

class VigilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Vigil.onAppStart(this)
    }
}
