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
 * M6..M8 own the semantics of the calls their phases land (requestPage/requestSeed: M6;
 * requestScope/requestMerge/cancelRequest: M7; renameNotebook/closeNotebook: M8) and may
 * reshape them before the arc freezes; begin/current/readChunk/saveChunk are M3's and stable.
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

    /** M6: flip to the page PAGE_PREV/PAGE_NEXT of the current one. The editor saves first
     *  (the no-save zone is guarded on both sides); null = no page there / load failed, and the
     *  editor stays where it was. Answers with the new target's state + read window. */
    DocumentPageState requestPage(int direction);

    /** M7: switch the target between SCOPE_PAGE and SCOPE_NOTEBOOK. Same guards as a flip. */
    DocumentPageState requestScope(int scope);

    /** M6: load the read window with the current page's recognized text (BRING_REPLACE /
     *  BRING_APPEND -- the sheet ran editor-side first). The document row is NOT touched: the
     *  seed becomes real only when the editor stores it (a drafted save). */
    DocumentPageState requestSeed(int mode);

    /** M7: load the read window with the notebook-wide merge (same modes, same not-stored rule). */
    DocumentPageState requestMerge(int mode);

    /** M7: abandon an in-flight seed/merge the editor no longer wants (its Cancel). */
    void cancelRequest();

    /** M8, text documents only: rename the notebook from the edited title. The host validates
     *  against siblings and refuses with IllegalArgumentException; display text only. */
    void renameNotebook(String name);

    /** M8, text documents only: how the showing should end -- CLOSE_SHOW_PAGES / CLOSE_TO_LIBRARY.
     *  Advisory state the host reads when the result lands; the editor still finishes normally. */
    void closeNotebook(int mode);
}
