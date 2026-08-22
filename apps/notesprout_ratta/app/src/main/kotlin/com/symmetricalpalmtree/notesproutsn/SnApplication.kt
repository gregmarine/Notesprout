package com.symmetricalpalmtree.notesproutsn

import android.app.Application
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine

/**
 * R0 scaffold: loads the SQLCipher native library once at process start (every SQLCipher open
 * elsewhere routes through crypto/SoilCrypto — see root CLAUDE.md) and registers the Ratta drawing
 * engine with g-paper.
 */
class SnApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        RattaEngine.register()
        // P1 removed the tool panels and with them ToolPrefs; drop the file it left behind rather
        // than leave a dead prefs file on every device that ran R3–R6. Off the main thread (it
        // touches disk) and harmless once it is gone — the call just reports false.
        Thread { deleteSharedPreferences(LEGACY_TOOL_PREFS) }.start()
    }

    private companion object {
        /** The `ToolPrefs` file (R3–R6): armed width/style/ink/radius + the recogniser latches. */
        const val LEGACY_TOOL_PREFS = "sn_tool"
    }
}
