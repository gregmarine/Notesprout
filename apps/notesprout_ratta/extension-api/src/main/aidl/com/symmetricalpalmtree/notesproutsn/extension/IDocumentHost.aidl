package com.symmetricalpalmtree.notesproutsn.extension;

import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState;

/**
 * The host-side callback binder of the DOCUMENT_EDITOR point (arc 19 / M3) -- the first
 * host-side stub on any SN extension seam. The host mints one per showing, bound to the
 * extension's uid, passes it at IDocumentEditor.begin(), and revokes it with the unbind:
 * every method refuses any other uid (and everything after the revoke) with a
 * SecurityException, the extension-side HostCallerCheck's mirror.
 *
 * Text crosses ONLY here, chunked by the shared TextChunks rule, and is never logged on
 * either side. The read direction is a pull: every state-answering call parks its text in the
 * host's read window atomically with the DocumentPageState it returns, and readChunk() serves
 * that window. The write direction is a push: saveChunk() accumulates, and the last chunk
 * commits -- to the target [pageKey] names, which must be the CURRENT target's key, so
 * notebook-document text can never land on a page row or vice versa (the mode-routing guard,
 * structural). Only SecurityException / IllegalArgumentException / IllegalStateException may
 * cross; a method whose phase has not landed yet answers UnsupportedOperationException (it
 * marshals intact -- the J3 precedent).
 *
 * M8 owns the semantics of the calls its phase lands (renameNotebook/closeNotebook) and may
 * reshape them before the arc freezes; begin/current/readChunk/saveChunk are M3's,
 * requestPage/requestSeed M6's and requestScope/requestMerge/cancelRequest M7's — stable.
 */
interface IDocumentHost {
    /** The current target's state; parks its document text in the read window. */
    DocumentPageState current();

    /** One chunk of the read window's text, 0-based; outside 0..textChunks-1 is refused. */
    String readChunk(int chunkIndex);

    /**
     * One chunk of a save for the target [pageKey] names. Chunks arrive in order from 0; the
     * host re-checks the running total against MAX_DOCUMENT_CHARS on receipt; [last] commits.
     * [drafted] marks the one save that anchors a draft (a stored seed/merge): the host stamps
     * the watermark it parked when it served that request -- an ordinary edit never moves it.
     * A refused chunk resets the whole accumulation; the editor restarts from chunk 0.
     */
    void saveChunk(String pageKey, int chunkIndex, String chunk, boolean last, boolean drafted);

    /**
     * M6: flip to the page PAGE_PREV/PAGE_NEXT of the current one. The editor pushes its text
     * FIRST (the flip gap is a no-save zone, guarded on both sides); this call then moves the
     * host's target and swaps the read window atomically, so a save landing in the gap is
     * refused by key rather than written onto the wrong page. An undocumented page is seeded
     * exactly like opening one — the answer carries `seeded = true` and the window holds the
     * recognized text (empty when there is no recognizer ready: the flip still lands, the page
     * stays seedable). null = no page there / the load failed; the target did NOT move and the
     * editor stays where it was.
     */
    DocumentPageState requestPage(int direction);

    /**
     * M7: switch the target between SCOPE_PAGE and SCOPE_NOTEBOOK — a page flip in every way that
     * matters: the editor pushes its text FIRST, this call then moves the host's target and swaps
     * the read window atomically, and null means nothing moved (the editor stays, silently —
     * a failed load, a cancelled auto-merge, or a request for the scope already current).
     *
     * Entering SCOPE_NOTEBOOK with no stored notebook document runs the FIRST-toggle auto-merge
     * (per page: its document if non-blank, else its silent recognition when one is READY; pages
     * with nothing are dropped; blank-line joins) and serves the result `seeded = true` with the
     * notebook-wide watermark parked — stored only when the editor's drafted save lands, like any
     * seed. A merge with nothing to give still lands the toggle on an empty, seedable window.
     * The page target is RETAINED through the notebook visit: switching back serves that page
     * (seeding it exactly as a flip would), and the notebook's close catch-up still names it.
     */
    DocumentPageState requestScope(int scope);

    /**
     * M6: load the read window with the current page's freshly recognized text (BRING_REPLACE /
     * BRING_APPEND -- the sheet ran editor-side first, so nobody waits through a recognition
     * they then cancel; the mode is advisory here, the editor applies it through its own
     * buffer). The document row is NOT touched: the host parks the watermark it read before
     * recognizing, and the seed becomes real only when the editor stores it (a drafted save).
     * Recognition unavailable (no extension / model not ready / engine refused) is an
     * IllegalStateException carrying exactly DocumentContract.SEED_UNAVAILABLE.
     */
    DocumentPageState requestSeed(int mode);

    /**
     * M7: load the read window with the notebook-wide merge (same modes as requestSeed, same
     * not-stored rule — the notebook-wide watermark is parked and the document row untouched
     * until the editor's drafted save lands). SCOPE_NOTEBOOK only. Mirrors requestSeed's
     * throw-not-null asymmetry DELIBERATELY: a cancelled merge is an IllegalStateException
     * carrying exactly DocumentContract.MERGE_CANCELLED (nothing written, window untouched);
     * recognition never blocks a merge — page documents merge without one, and a merge with
     * nothing to give answers honestly with an empty window (the editor's call what to do).
     */
    DocumentPageState requestMerge(int mode);

    /**
     * M7: abandon an in-flight merge the editor no longer wants (its Cancel). The loop stops
     * between pages; the in-flight requestScope answers null / requestMerge throws
     * MERGE_CANCELLED. A cancel with nothing running is a harmless no-op.
     */
    void cancelRequest();

    /** M8, text documents only: rename the notebook from the edited title. The host validates
     *  against siblings and refuses with IllegalArgumentException; display text only. */
    void renameNotebook(String name);

    /** M8, text documents only: how the showing should end -- CLOSE_SHOW_PAGES / CLOSE_TO_LIBRARY.
     *  Advisory state the host reads when the result lands; the editor still finishes normally. */
    void closeNotebook(int mode);
}
