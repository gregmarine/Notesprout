package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.app.Application
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine

/**
 * The extension's own process hosts a paper surface (arc 43 / K5), so it registers g-paper's engine
 * itself — the host's registration means nothing across a process boundary. **`RattaEngine.register()`
 * only**: SN has no Onyx.
 *
 * Two paper surfaces never share a process or the EPD pipeline at once: the notebook releases its
 * pipeline (`releaseForHandoff`) immediately before launching us, and we release ours before every
 * `finish()`.
 */
class SketchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RattaEngine.register()
    }
}
