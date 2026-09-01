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
 * [writes] is the other thing this object owns, and it is not session state: it is the **one lock
 * every read-modify-write of the index takes**. The index is a single store value, and there are two
 * writers in this process — the screen (on IO) and the service's call-shaped `assign` (on a Binder
 * thread, W3) — so without it two edits that read the same index would each write their own version
 * and the second would erase the first.
 */
object TagSession {

    /** The monitor every read-modify-write of the stored index holds. Never held across a UI frame. */
    val writes = Any()

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
