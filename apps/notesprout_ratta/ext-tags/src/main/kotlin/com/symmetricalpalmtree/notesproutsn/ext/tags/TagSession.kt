package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.TagShowing

/**
 * Process-wide state shared by [TagManagerService] (the host's held bind) and [TagsActivity] (the
 * screen) — they live in the same process (arc 21 / W1, the `ScratchSession` shape).
 *
 * It holds **only what the host lent for this showing**: the store binder from `begin` and the
 * [TagShowing] from `configureShowing`. `end()` clears both. Nothing here is ever written to disk by
 * the extension itself — the tag index lives in the host's store.
 *
 * **There is no write lock here any more** (arc 22 / X3). W1 owned one, because the index was a
 * single store value and two writers — the screen on IO and the service's call-shaped `assign` on a
 * Binder thread — each applying their change to the version they happened to be holding is how one
 * silently erases the other. The index is rows now: every write is one statement, or two in one
 * batch, and **the store's transaction is the lock** — across both writers, and across the two
 * processes a monitor in this one could never have covered.
 */
object TagSession {

    @Volatile
    var store: IExtensionStore? = null

    /** What this showing is about. Parked by `configureShowing`, read once by the screen. */
    @Volatile
    var showing: TagShowing? = null

    @Synchronized
    fun clear() {
        store = null
        showing = null
    }
}
