package com.symmetricalpalmtree.notesproutsn.extension;

// A .aidl that takes a parcelable needs an explicit import for it.
import com.symmetricalpalmtree.notesproutsn.extension.SketchPageState;
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke;

/**
 * The host-side callback binder of the SKETCH point (arc 43 / K2) -- the SECOND host-side stub on
 * any SN extension seam, after IDocumentHost, and built to its recipe: the host mints one per
 * showing, bound to the extension's uid, passes it at ISketch.begin(), and revokes it with the
 * unbind; every method refuses any other uid (and everything after the revoke) with a
 * SecurityException, the extension-side HostCallerCheck's mirror.
 *
 * Pixels cross ONLY here, chunked by the shared ByteChunks rule, and are never logged on either
 * side -- counts, byte totals and durations only. The read direction is a pull: every
 * state-answering call parks its PNG in the host's read window atomically with the
 * SketchPageState it returns, and readSketchChunk() serves that window. The write direction is a
 * push: saveSketchChunk() accumulates and the last chunk commits.
 *
 * Unlike the editor's, a save is accepted for ANY LIVE PAGE of the open notebook, named by
 * [pageKey] -- the sketch screen turns its own pages and a save flushed a moment after a turn
 * still belongs to the page it was drawn on. What the key still guarantees is that pixels can
 * never land on a page nobody drew them on.
 *
 * Only SecurityException / IllegalArgumentException / IllegalStateException may cross. The two
 * typed IllegalStateException messages -- SketchContract.SKETCH_TOO_LARGE and
 * SketchContract.SKETCH_BAD_PNG -- are compared verbatim by the extension (==, never contains).
 */
interface ISketchHost {
    /** The current page's state; parks its sketch PNG in the read window. */
    SketchPageState current();

    /**
     * Flip to the page SketchContract.PAGE_PREV / PAGE_NEXT of the current one: the host moves its
     * read window atomically with the state it answers. At either edge the host answers the SAME
     * page unchanged -- the extension compares pageKey and stays where it is -- so a page turn is
     * never an exception and never a null the caller has to word. The extension pushes its own
     * pixels FIRST: the host's window is what it will read back, and a save left in flight would
     * land on the page it just left.
     */
    SketchPageState requestPage(int direction);

    /** One chunk of the read window's PNG, 0-based; outside 0..sketchChunks-1 is refused. The
     *  window is the page current() / requestPage() last answered with. */
    byte[] readSketchChunk(int chunkIndex);

    /**
     * One chunk of a save for the page [pageKey] names -- any live page of the open notebook.
     * Chunks arrive in order from 0; the host re-checks the running total against
     * SketchContract.MAX_BYTES on receipt (over it: IllegalStateException carrying exactly
     * SketchContract.SKETCH_TOO_LARGE, the accumulation reset, nothing written); [last] commits,
     * and the committed bytes are checked against the page's size before any decode (a mismatch or
     * a malformed header: IllegalStateException carrying exactly SketchContract.SKETCH_BAD_PNG).
     *
     * ONE EMPTY CHUNK with last = true is the wire form of "this page has no sketch": the host
     * soft-deletes the row rather than storing a blank page of pixels. A refused chunk resets the
     * whole accumulation; the extension restarts from chunk 0.
     */
    void saveSketchChunk(String pageKey, int chunkIndex, in byte[] chunk, boolean last);

    /**
     * "Bring in ink" (decision 8): stage the page [pageKey] names' BARE strokes -- link-wrapped
     * and sticky ink excluded, host-side -- in the host's ink window, and answer how many
     * readInkChunk() calls serve them. ZERO is a legal answer and the only one for a page with no
     * bare ink: the extension words its own "no ink here", and a count is a better shape for that
     * than an exception (the pad's zero-chunk park, arc 31 / HV5). Chunked under the pad's own
     * InkChunks / TransferCaps rules, so this loop and the pad's are the same loop.
     */
    int requestInk(String pageKey);

    /** One chunk of the ink window, 0-based; outside 0..the count requestInk answered is refused. */
    List<WireStroke> readInkChunk(int chunkIndex);
}
