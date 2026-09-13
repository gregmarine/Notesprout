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

    /**
     * The reference this showing opens on (arc 38 / R1, `beginAt`) — the wire form, never
     * decoded here; null for a plain `begin`, which opens the reader where it was left. The
     * screen reads it once in `onCreate` to pick its mode. Never logged.
     */
    @Volatile
    var reference: String? = null

    @Synchronized
    fun clear() {
        store = null
        reference = null
    }
}
