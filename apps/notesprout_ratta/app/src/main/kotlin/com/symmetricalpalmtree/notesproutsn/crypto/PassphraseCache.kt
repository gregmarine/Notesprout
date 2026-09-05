package com.symmetricalpalmtree.notesproutsn.crypto

import java.util.concurrent.ConcurrentHashMap

/**
 * A **single-use**, RAM-only hand-off of a notebook passphrase between the screen that set it and
 * the first open that follows (arc 26 / U2, decision 12): create-with-passphrase and the import
 * chooser [storeOnce]; the notebook's next open [takeOnce]s it, so the person is not asked for the
 * passphrase they typed a moment ago — and is asked on every open after that.
 *
 * Nothing here is persisted, logged, or survives the process; a value not taken is dropped by
 * [clear] (Forget) or the process ending. Keyed by notebook id.
 */
object PassphraseCache {

    private val once = ConcurrentHashMap<String, String>()

    /** Park [passphrase] for exactly one [takeOnce] of [notebookId]; replaces any earlier value. */
    fun storeOnce(notebookId: String, passphrase: String) {
        once[notebookId] = passphrase
    }

    /** The parked passphrase for [notebookId], removed as it is read. Null when none is waiting. */
    fun takeOnce(notebookId: String): String? = once.remove(notebookId)

    /** Drop everything parked (the Encryption screen's Forget). */
    fun clear() = once.clear()
}
