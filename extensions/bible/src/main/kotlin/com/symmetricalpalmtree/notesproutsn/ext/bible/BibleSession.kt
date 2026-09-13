package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore

/**
 * Process-wide state shared by [BibleService] (the host's held bind) and [BibleActivity] (the
 * screen) — they live in the same process (the tag manager's `TagSession` shape, arc 21 / W1).
 *
 * It holds **only what the host lent for this showing**: the store binder from `begin`. `end()`
 * clears it. Nothing here is ever written to disk by the extension itself — the reader's one row
 * of per-device state lives in the host's store.
 */
object BibleSession {

    @Volatile
    var store: IExtensionStore? = null

    @Synchronized
    fun clear() {
        store = null
    }
}
