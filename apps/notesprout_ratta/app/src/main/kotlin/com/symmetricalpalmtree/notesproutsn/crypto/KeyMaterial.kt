package com.symmetricalpalmtree.notesproutsn.crypto

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point for a file's SQLCipher **raw key** (the derive-once cache).
 *
 * Resolution: process RAM → [DerivedKeyStore] (Keystore) → derive + persist. Every key is
 * persisted, a `NOTEBOOK`-scope notebook's included (arc 26 / U4, decision 12: the raw key is
 * cached for speed; the passphrase never is — the prompt still asks on every open, and a silent
 * read is gated by [NotebookUnlocks], not by this cache). Blocking on a miss (KDF) — call on
 * Dispatchers.IO.
 */
object KeyMaterial {

    /** Stable id for the index in the raw-key cache. */
    const val INDEX_FILE_ID = "__notesprout_index__"

    private val ram = ConcurrentHashMap<String, ByteArray>()

    /** Raw key for [fileId]: RAM → Keystore → derive against [file]'s salt + persist. */
    fun rawKey(context: Context, fileId: String, file: File, passphrase: String): ByteArray {
        ram[fileId]?.let { return it }
        DerivedKeyStore.get(context, fileId)?.let { ram[fileId] = it; return it }
        val key = RawKeyDerivation.deriveKey(file, passphrase)
        DerivedKeyStore.put(context, fileId, key)
        ram[fileId] = key
        return key
    }

    /** RAM or Keystore hit, **never derives**. Null when not yet derived on this device. */
    fun peekOrLoad(context: Context, fileId: String): ByteArray? {
        ram[fileId]?.let { return it }
        return DerivedKeyStore.get(context, fileId)?.also { ram[fileId] = it }
    }

    /** Drop one file's key everywhere — on delete, or when a cached key no longer fits the file.
     *  Clears **both** the RAM map and the Keystore entry (the Paper Phase-6 lesson: clearing only
     *  the Keystore leaks the RAM entry for the process lifetime). */
    fun invalidate(context: Context, fileId: String) {
        ram.remove(fileId)
        DerivedKeyStore.remove(context, fileId)
    }

    /** Wipe every cached key (the Encryption screen's Forget, arc 26 / U1; rotation from U3). */
    fun clearAll(context: Context) {
        ram.clear()
        DerivedKeyStore.clear(context)
    }
}
