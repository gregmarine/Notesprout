package com.symmetricalpalmtree.notesproutsn.ext.drive

import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore

/**
 * Process-wide state shared by [DriveService] (the host's held bind for the connect showing) and
 * [ConnectActivity] (the sign-in screen) — they live in the same process (arc 25 / V2, the
 * `TagSession` shape).
 *
 * It holds **only what the host lent for this showing**: the store binder from `beginConnect`.
 * `endConnect` clears it, and the host revokes the binder right after — so a screen that outlives
 * the bracket (it cannot, but a stale reference could) finds a binder that refuses every call, never
 * a live one. The token the screen wins is written through this store and nowhere else: the
 * extension writes nothing to disk itself, ever.
 *
 * There is no lock beyond the monitor: one showing at a time is the host's rule (its entry latches
 * at the tap), and the store's transaction is the lock for the two writes the screen makes.
 */
object ConnectSession {

    @Volatile
    var store: IExtensionStore? = null

    @Synchronized
    fun clear() {
        store = null
    }
}
