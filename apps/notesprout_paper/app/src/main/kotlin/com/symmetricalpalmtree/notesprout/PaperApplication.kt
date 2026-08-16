package com.symmetricalpalmtree.notesprout

import android.app.Application
import com.symmetricalpalmtree.gpaper.onyx.OnyxEngine
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine

class PaperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OnyxEngine.register(this)
        RattaEngine.register()
    }
}
