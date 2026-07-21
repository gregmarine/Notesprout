package com.notesprout.android.core

import java.util.Collections

/**
 * Process-wide registry of notebooks currently open in a NotebookActivity.
 *
 * Exists so destructive whole-notebook operations (import "Replace") can refuse to swap the
 * `.soil` file out from under a live Room connection — the connection would keep writing to the
 * unlinked inode and every edit after the swap would silently vanish. Multiple activities can
 * hold the same notebook (double-launch), so this counts rather than flags.
 */
object OpenNotebooks {

    private val counts = Collections.synchronizedMap(mutableMapOf<String, Int>())

    fun opened(notebookId: String) {
        if (notebookId.isEmpty()) return
        synchronized(counts) { counts[notebookId] = (counts[notebookId] ?: 0) + 1 }
    }

    fun closed(notebookId: String) {
        if (notebookId.isEmpty()) return
        synchronized(counts) {
            val next = (counts[notebookId] ?: 1) - 1
            if (next <= 0) counts.remove(notebookId) else counts[notebookId] = next
        }
    }

    fun isOpen(notebookId: String): Boolean = counts.containsKey(notebookId)
}
