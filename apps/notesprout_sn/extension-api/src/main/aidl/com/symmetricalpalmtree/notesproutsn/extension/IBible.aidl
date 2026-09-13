package com.symmetricalpalmtree.notesproutsn.extension;

import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore;

/**
 * The Bible-reader point (arc 37 / B0) — SN's NINTH capability point, the fifth screen-owning one
 * and the second with no paper. The tag manager's showing bracket and nothing more: the host
 * pre-opens the store, holds the bind, calls `begin(store)`, launches the screen for a result,
 * and calls `end()` when the screen has returned. The store carries the reader's one row of
 * per-device state (the last-read position); scripture never crosses this seam.
 *
 * Nothing rides the screen's Intent. Only the three marshalable exceptions leave a stub method.
 */
interface IBible {
    /** Lend the store for the showing. Called once per showing, before the screen launches. */
    void begin(IExtensionStore store);
    /** The showing is over; drop the parked store. Best-effort on the host side. */
    void end();
}
