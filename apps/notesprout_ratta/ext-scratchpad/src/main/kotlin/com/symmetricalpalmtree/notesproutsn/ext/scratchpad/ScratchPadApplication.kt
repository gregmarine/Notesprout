package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.app.Application
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine

/**
 * The extension's own process hosts a paper surface (arc 11 / J4), so it registers g-paper's engine
 * itself. **`RattaEngine.register()` only** — SN has no Onyx. Two paper surfaces never share a
 * process or the EPD pipeline at once: the host releases its pipeline (`releaseForHandoff`)
 * immediately before launching us, and we release ours before every `finish()`.
 */
class ScratchPadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RattaEngine.register()
    }
}
