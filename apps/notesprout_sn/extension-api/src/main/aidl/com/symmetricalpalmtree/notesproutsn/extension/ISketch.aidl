package com.symmetricalpalmtree.notesproutsn.extension;

import com.symmetricalpalmtree.notesproutsn.extension.ISketchHost;

/**
 * The SKETCH point (arc 43 / K2) -- SN's TENTH capability point (the user's explicit 2026-09-15
 * decision) and its SIXTH screen-owning one. The extension owns a full-screen raster sketch
 * Activity (action ACTION_SKETCH_SCREEN) the host launches for a result; the host HOLDS one bind
 * on this service for the screen's whole showing (begin -> launch -> result -> end -> unbind).
 *
 * TWO methods, and no store. Every other screen-owning point is lent an IExtensionStore at
 * begin(), because every other one has something of its own to remember -- the pad's pages, the
 * calendar's bookmark, the reader's position, the editor's caret. A sketch has none: the pixels
 * live in the host's .soil, the bookmark is the notebook's own page, the undo history is this
 * sitting and dies with it, and there is one tool with no settings. A store lent here would be an
 * empty file on disk and a binder to revoke, for nothing.
 *
 * The seam's piece rides begin(): [host] is a HOST-side binder (IDocumentHost's recipe -- minted
 * per showing, uid-gated in every method, revoked with the unbind) through which every byte of
 * raster image (two per page since arc 45 — graphite and ink, each a lossless WebP) crosses, in
 * both directions, chunked (ByteChunks). Nothing rides the screen's Intent but
 * ExtensionContract.EXTRA_CHROME_HIDDEN. Every method here: HostCallerCheck.enforce first.
 * Timeouts are the host's.
 */
interface ISketch {
    /** The host is about to show the sketch screen: hold [host] for the showing (dropped at end(),
     *  and revoked by the host alongside the unbind). */
    void begin(ISketchHost host);

    /** The showing is over (result / cancel / host stop): drop the host binder and any parked save. */
    void end();
}
