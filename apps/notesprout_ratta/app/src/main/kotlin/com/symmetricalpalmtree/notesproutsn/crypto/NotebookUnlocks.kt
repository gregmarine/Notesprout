package com.symmetricalpalmtree.notesproutsn.crypto

import java.util.concurrent.ConcurrentHashMap

/**
 * The `NOTEBOOK`-scope notebooks the person has unlocked **this process** (arc 26 / U4, D3) — ids
 * only, RAM only, gone with the process or with the Encryption screen's Forget.
 *
 * The notebook screen prompts on every open regardless (decision 12); this set exists so the
 * *silent* reads that follow a deliberate unlock — recents, the export source, the picker's page
 * grid — can go ahead without a second prompt, and so nothing reads a locked notebook the person
 * has not opened. [KeyResolver] consults it; [NotebookPassphrasePrompt] fills it.
 */
object NotebookUnlocks {

    private val ids: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun mark(notebookId: String) { ids += notebookId }

    fun has(notebookId: String): Boolean = notebookId in ids

    /** A scope change or a passphrase change re-keys the file: what was unlocked no longer is. */
    fun forget(notebookId: String) { ids -= notebookId }

    fun clear() = ids.clear()
}
