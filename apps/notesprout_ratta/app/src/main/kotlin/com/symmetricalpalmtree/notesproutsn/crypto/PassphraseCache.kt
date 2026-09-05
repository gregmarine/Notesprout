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

    /** How long a parked value waits to be taken (arc 26 / U4): long enough for the screen change
     *  it was parked for, short enough that a hand-off nothing took cannot surface later as an
     *  open that "did not ask". */
    const val TTL_MS = 60_000L

    private class Parked(val passphrase: String, val at: Long)

    private val once = ConcurrentHashMap<String, Parked>()

    /** Park [passphrase] for exactly one [takeOnce] of [notebookId] within [TTL_MS]; replaces any
     *  earlier value. */
    fun storeOnce(notebookId: String, passphrase: String, now: Long = System.currentTimeMillis()) {
        once[notebookId] = Parked(passphrase, now)
    }

    /**
     * The parked passphrase for [notebookId], removed as it is read; null when none is waiting or
     * the one waiting is older than [TTL_MS] (dropped on the way out). **Only the notebook screen's
     * own open takes it** (`NotebookActivity.keyFor`) — the door it was parked for. Every other
     * prompt (a link follow, the picker's lock row, the Export screen) asks regardless, so "which
     * door came first" never decides whether a prompt appears.
     */
    fun takeOnce(notebookId: String, now: Long = System.currentTimeMillis()): String? {
        val parked = once.remove(notebookId) ?: return null
        return parked.passphrase.takeIf { now - parked.at in 0..TTL_MS }
    }

    /** Drop everything parked (the Encryption screen's Forget). */
    fun clear() = once.clear()
}
