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

    /**
     * K5b (2026-09-15) -- a compatible tail (the seventh method, transaction code 7) behind the
     * extension-side declaration floor SketchContract.MIN_API_VERSION_FOR_SKETCH_PAGES (18):
     * insert a blank page next to THE FACE'S TARGET -- not the notebook's displayed page -- on the
     * side SketchContract.PAGE_PREV / PAGE_NEXT names, and land the target on it.
     *
     * The host inserts it exactly as its own notebook does: the new page inherits the target page's
     * template and authored size, the insert is ONE entry on the NOTEBOOK'S OWN undo stack (so the
     * notebook's undo takes it back after Show pages, as if the notebook had made it), and the read
     * window is loaded with the new page -- empty, because a page that did not exist a moment ago
     * has no pixels. The answer is the new page's state, and its structuralToken is the host's name
     * for this edit -- the face keeps it and hands it back at undoPage().
     *
     * It is safe for an extension to call this: the number an extension declares is what it
     * REQUIRES OF THE HOST, so an extension declaring 18 is only ever discovered by a host at
     * >= 18 (the arc-39 openReference precedent, the first host-side-stub tail).
     */
    SketchPageState insertPage(int direction);

    /**
     * K5b -- a compatible tail (the eighth method, transaction code 8) behind the same floor:
     * soft-delete the page [pageKey] names, which MUST be the face's current target
     * (IllegalArgumentException otherwise -- pixels and pages can never be acted on at a distance).
     *
     * The host deletes it the notebook's way: the page and every live descendant it owns -- its
     * strokes, its objects, its document AND its sketch -- go together, as ONE entry on the
     * NOTEBOOK'S OWN undo stack. Deleting the only page of a notebook answers a FRESH BLANK page
     * instead of an empty notebook (the session's own rule); otherwise the target lands on the page
     * the notebook lands on. The read window is loaded with whatever that page is, and its state is
     * the answer -- carrying the structuralToken that names this delete, so the face's own undo
     * gesture can ask for the page back without the person having to leave for the notebook.
     *
     * The extension does NOT flush the doomed page first: its pixels are being deleted, and a save
     * pushed into the gap would write a row that is soft-deleted a moment later. A push already in
     * flight when the row goes is refused with IllegalArgumentException("Unknown page"), which is
     * the same refusal any dead key gets.
     */
    SketchPageState deletePage(String pageKey);

    /**
     * K5b -- a compatible tail (the ninth method, transaction code 9) behind the same floor: what
     * else the page [pageKey] names is carrying, as a bit set of SketchContract.PAGE_HAS_INK (bare
     * strokes or objects) and SketchContract.PAGE_HAS_DOCUMENT (a live document row). ZERO means a
     * page with nothing on it but its sketch.
     *
     * It exists so the delete confirm can NAME what goes with the page rather than warn about
     * content that is not there. Any LIVE page of the open notebook may be asked (not only the
     * target); a key naming a page the notebook no longer has is an IllegalArgumentException.
     * The sketch itself is deliberately not a bit: the face is showing it.
     */
    int pageContent(String pageKey);

    /**
     * K5b (the user's follow-up decision, 2026-09-15) -- a compatible tail (the tenth method,
     * transaction code 10) behind the same floor: TAKE BACK the page insert or delete [token]
     * names, so the face's own undo gesture reverses one and nobody has to go back to the notebook
     * to undo a delete.
     *
     * [token] is the structuralToken an insertPage() / deletePage() answer carried. The host looks
     * up the snapshot it kept for that edit and replays it through exactly the arm the notebook's
     * own undo runs -- the same reconcile, so a page comes back with its handwriting, its document
     * and its sketch, at the position it had. It then takes that same edit off the NOTEBOOK'S undo
     * stack and puts it on the notebook's redo stack, because the two histories are one history
     * seen from two screens and they must stay in step.
     *
     * A token this host does not know (the edit was already undone, or the host died and came back)
     * is an IllegalArgumentException: the face drops that entry and carries on. The answer is the
     * state of the page the notebook lands on, with an EMPTY structuralToken -- a replay names an
     * edit, it does not make one.
     */
    SketchPageState undoPage(String token);

    /**
     * K5b -- a compatible tail (the eleventh method, transaction code 11) behind the same floor:
     * the mirror of undoPage(). PUT BACK the page insert or delete [token] names, through the arm
     * the notebook's own redo runs, and move that edit back from the notebook's redo stack to its
     * undo stack.
     *
     * Only a token undoPage() has taken back can be put back; anything else is an
     * IllegalArgumentException, as it is there. The answer is the page the notebook lands on, with
     * an empty structuralToken.
     */
    SketchPageState redoPage(String token);
}
