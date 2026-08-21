package com.symmetricalpalmtree.notesprout.ext.scratchpad

import android.app.Application
import com.symmetricalpalmtree.gpaper.onyx.OnyxEngine
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine

/**
 * The extension's own process hosts a paper surface (arc 6), so it registers g-paper's engines
 * itself — Onyx must run in `Application.onCreate` (g-paper's rule; rule 27: two paper surfaces never
 * share a process or the EPD pipeline at once — the host releases its pipeline before launching us).
 */
class ScratchPadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OnyxEngine.register(this)
        RattaEngine.register()
    }
}
