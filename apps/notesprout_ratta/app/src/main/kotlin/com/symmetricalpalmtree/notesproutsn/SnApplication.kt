package com.symmetricalpalmtree.notesproutsn

import android.app.Application
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine

/**
 * R0 scaffold: loads the SQLCipher native library once at process start (every SQLCipher open
 * elsewhere routes through crypto/SoilCrypto — see root CLAUDE.md) and registers the Ratta drawing
 * engine with g-paper. Nothing else lives here yet; R1 adds the index bootstrap.
 */
class SnApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        RattaEngine.register()
    }
}
