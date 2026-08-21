package com.symmetricalpalmtree.notesprout.extension;

import com.symmetricalpalmtree.notesprout.extension.IExtensionStore;
import com.symmetricalpalmtree.notesprout.extension.InkBundle;

/**
 * The SCRATCH_PAD point (arc 6 / S0) — the first screen-owning point. The extension owns an off-paper
 * Activity (action ACTION_SCRATCH_PAD_SCREEN) the host launches for a result; the host HOLDS one bind
 * on this service for the screen's whole showing (begin → launch → result → end → unbind), and every
 * byte of ink crosses through these methods — never through the Intent. Every method: HostCallerCheck
 * first. Timeouts are the host's (≤ 2 s each).
 */
interface IScratchPad {
    /** The host is about to show the screen: hold [store] for the screen's life (revoked at end()). */
    void begin(IExtensionStore store);

    /** Notebook → pad (S2): one chunk of the inbound ink; [placement] (PLACEMENT_*) + [last] on every
     *  chunk. The extension appends chunks until last == true, then places them (a new page after the
     *  current one, or the current page) and marks them "open selected" for the next screen launch.
     *  Throws IllegalStateException(SCRATCH_PAGE_FULL) if the target page would exceed the store cap. */
    void receiveInk(in InkBundle chunk, int placement, boolean last);

    /** Pad → notebook (S2): after RESULT_SCRATCH_SEND the host drains the outbound ink chunk by chunk;
     *  an empty bundle (0 strokes) means done. */
    InkBundle takeOutgoing(int chunkIndex);

    /** The screen is over (result / cancel / host stop): drop the store, clear pending ink. */
    void end();
}
