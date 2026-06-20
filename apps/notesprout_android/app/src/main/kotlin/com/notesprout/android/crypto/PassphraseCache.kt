package com.notesprout.android.crypto

import java.util.concurrent.ConcurrentHashMap

/**
 * Single-use in-memory passphrase cache for the create → immediate-open path.
 *
 * When a Notebook-encrypted notebook is just created the caller already holds the passphrase.
 * Storing it here lets the first open skip the prompt — the entry is consumed (removed) on the
 * first hit, so every subsequent open prompts as normal.
 *
 * Never persisted. Never put in an Intent. Dies with the process.
 */
internal object PassphraseCache {

    private val store = ConcurrentHashMap<String, String>()

    fun storeOnce(notebookId: String, passphrase: String) {
        store[notebookId] = passphrase
    }

    /** Returns and removes the cached passphrase, or null if none. */
    fun consumeOnce(notebookId: String): String? = store.remove(notebookId)
}
