package com.symmetricalpalmtree.notesprout

import android.app.Application
import android.util.Log
import com.symmetricalpalmtree.gpaper.onyx.OnyxEngine
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine

class PaperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // SQLCipher's native library must be loaded before any zetetic class is touched.
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("PaperApplication", "SQLCipher native lib failed to load", e)
        }
        // g-paper engines — nothing else at startup. The index is opened by BootstrapActivity
        // (it may need an unlock prompt, so it cannot happen here).
        OnyxEngine.register(this)
        RattaEngine.register()
    }
}
