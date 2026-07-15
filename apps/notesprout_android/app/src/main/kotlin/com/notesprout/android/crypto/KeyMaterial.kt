package com.notesprout.android.crypto

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point for obtaining a file's SQLCipher **raw key** (the derive-once cache).
 *
 * Resolution order: process RAM → [DerivedKeyStore] (Keystore, GLOBAL only) → derive + store. The
 * expensive KDF ([RawKeyDerivation.deriveKey]) runs at most once per file per device; every open
 * after that reopens with the raw key (~35 ms) instead of re-running the KDF (~300–700 ms).
 *
 * Two scopes:
 *  - [rawKeyGlobal] persists the key (Keystore) — global-scope files, fast across cold launches.
 *  - [rawKeyEphemeral] caches in RAM only, never on disk — notebook-passphrase (private) files, so
 *    the key never rests on the device. Cleared on notebook close / process death.
 *
 * All methods are blocking (KDF on a miss); call on Dispatchers.IO/Default. Key material is never logged.
 */
object KeyMaterial {

    /** Stable ids for the non-notebook global files. */
    const val INDEX_FILE_ID = "__notesprout_index__"
    const val TRAINING_FILE_ID = "__hwr_training__"

    private val ram = ConcurrentHashMap<String, ByteArray>()

    /** Raw key for a GLOBAL-scope file: RAM → Keystore → derive+persist. */
    fun rawKeyGlobal(context: Context, fileId: String, file: File, passphrase: String): ByteArray {
        ram[fileId]?.let { return it }
        DerivedKeyStore.get(context, fileId)?.let { ram[fileId] = it; return it }
        val key = RawKeyDerivation.deriveKey(file, passphrase)
        DerivedKeyStore.put(context, fileId, key)
        ram[fileId] = key
        return key
    }

    /** Raw key for a NOTEBOOK-scope (private) file: RAM only, never persisted. */
    fun rawKeyEphemeral(fileId: String, file: File, passphrase: String): ByteArray {
        ram[fileId]?.let { return it }
        val key = RawKeyDerivation.deriveKey(file, passphrase)
        ram[fileId] = key
        return key
    }

    /** Cached raw key for [fileId] if already resolved this session (RAM), else null — no derivation. */
    fun peek(fileId: String): ByteArray? = ram[fileId]

    /** Drop a single file's key from RAM and (if present) the Keystore — on delete or before re-key. */
    fun invalidate(context: Context, fileId: String) {
        ram.remove(fileId)
        DerivedKeyStore.remove(context, fileId)
    }

    /** Drop only the RAM copy (e.g. a private notebook closing) — leaves any persisted global key. */
    fun forgetRam(fileId: String) {
        ram.remove(fileId)
    }

    /** Wipe everything — after global rotation (all salts change) or "Forget on this device". */
    fun clearAll(context: Context) {
        ram.clear()
        DerivedKeyStore.clear(context)
    }
}
