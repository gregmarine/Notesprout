package com.symmetricalpalmtree.notesproutsn.extension;

// A .aidl that takes a parcelable needs an explicit import for it.
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore;
import com.symmetricalpalmtree.notesproutsn.extension.InkBundle;

/**
 * The SCRATCH_PAD point (arc 11 / J3) — SN's first screen-owning point. The extension owns an
 * off-paper Activity (action ACTION_SCRATCH_PAD_SCREEN) the host launches for a result; the host
 * HOLDS one bind on this service for the screen's whole showing (begin -> launch -> result -> end ->
 * unbind), and every byte of ink crosses through these methods -- never through the Intent. Every
 * method: HostCallerCheck.enforce first. Timeouts are the host's.
 */
interface IScratchPad {
    /** The host is about to show the screen: hold [store] for the screen's life (revoked at end()). */
    void begin(IExtensionStore store);

    /** Notebook -> pad (J5): one chunk of the inbound ink; [placement] (PLACEMENT_*) + [last] on
     *  every chunk. The extension appends chunks until last == true, then places them (a new page
     *  after the current one, or the current page) and marks them "open selected" for the next screen
     *  launch. A scratch page has no size ceiling (arc 22 / X2 — it is rows in the store's `stroke`
     *  table); the only failure left is IllegalStateException("store unavailable"). */
    void receiveInk(in InkBundle chunk, int placement, boolean last);

    /** Pad -> notebook (J5): after RESULT_SCRATCH_SEND the host drains the outbound ink chunk by
     *  chunk; an empty bundle (0 strokes) means done. */
    InkBundle takeOutgoing(int chunkIndex);

    /** The screen is over (result / cancel / host stop): drop the store, clear pending ink. */
    void end();
}
