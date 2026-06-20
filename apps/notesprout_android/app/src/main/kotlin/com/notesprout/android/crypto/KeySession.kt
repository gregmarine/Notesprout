package com.notesprout.android.crypto

/**
 * Process-scoped in-memory key for the single foreground notebook.
 *
 * NotebookActivity sets this on a successful encrypted open and clears it in sealNotebook.
 * Child activities (PageIndexActivity, LinkTargetPickerActivity when targeting the current
 * notebook) read via [getFor] so they never re-prompt the user for the same passphrase.
 *
 * The passphrase is NEVER written to an Intent extra, SharedPreferences, or disk.
 * [PassphraseStore] separately manages the GLOBAL-scope cached passphrase — this object is only
 * for the single foreground session to avoid redundant prompts within one open/close cycle.
 */
object KeySession {
    data class Entry(val notebookId: String, val passphrase: String)

    @Volatile var entry: Entry? = null

    fun set(notebookId: String, passphrase: String) {
        entry = Entry(notebookId, passphrase)
    }

    fun clear() {
        entry = null
    }

    /** Returns the cached passphrase for [notebookId], or null if not set or for a different notebook. */
    fun getFor(notebookId: String): String? = entry?.takeIf { it.notebookId == notebookId }?.passphrase
}
