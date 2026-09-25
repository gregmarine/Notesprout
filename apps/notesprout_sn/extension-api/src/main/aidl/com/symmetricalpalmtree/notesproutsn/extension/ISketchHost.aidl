package com.symmetricalpalmtree.notesproutsn.extension;

// A .aidl that takes a parcelable needs an explicit import for it.
import com.symmetricalpalmtree.notesproutsn.extension.SketchGuideSettings;
import com.symmetricalpalmtree.notesproutsn.extension.SketchGuideState;
import com.symmetricalpalmtree.notesproutsn.extension.SketchPageState;
import com.symmetricalpalmtree.notesproutsn.extension.SketchToolSettings;
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke;

/**
 * The host-side callback binder of the SKETCH point (arc 43 / K2) -- the SECOND host-side stub on
 * any SN extension seam, after IDocumentHost, and built to its recipe: the host mints one per
 * showing, bound to the extension's uid, passes it at ISketch.begin(), and revokes it with the
 * unbind; every method refuses any other uid (and everything after the revoke) with a
 * SecurityException, the extension-side HostCallerCheck's mirror.
 *
 * Pixels cross ONLY here, chunked by the shared ByteChunks rule, and are never logged on either
 * side -- counts, byte totals and durations only. Since arc 45 "Ink" / G2 (2026-09-17) a page's
 * sketch is TWO RASTERS -- graphite (SketchContract.LAYER_GRAPHITE: the pencil's, the rubber's)
 * and ink (SketchContract.LAYER_INK: the gel pen's and "Bring in ink"'s, NEVER erased -- the
 * user's decision 1) -- each its own lossless WebP, its own row, its own window and its own
 * accumulator. The read direction is a pull: every state-answering call parks BOTH images in the
 * host's per-layer read windows atomically with the SketchPageState it returns, and
 * readSketchChunk(layer, i) serves the window the layer names. The write direction is a push:
 * saveSketchChunk(pageKey, layer, ...) accumulates per layer and the last chunk commits that
 * layer's row alone -- a pencil scribble never re-sends the ink.
 *
 * Unlike the editor's, a save is accepted for ANY LIVE PAGE of the open notebook, named by
 * [pageKey] -- the sketch screen turns its own pages and a save flushed a moment after a turn
 * still belongs to the page it was drawn on. What the key still guarantees is that pixels can
 * never land on a page nobody drew them on.
 *
 * G2 CHANGED transaction codes 3 and 4 IN PLACE (a layer argument, and the state's wire form grew
 * a second byte/chunk pair) rather than appending tails: with no shipped library on the old shape
 * (decision 4, no legacy) the point's action floor moved to
 * SketchContract.MIN_API_VERSION_FOR_SKETCH = 20, and a host or screen below it never binds this
 * point at all. The five K5b tails (codes 7-11) and the two T2 tails (12-13) are unchanged.
 *
 * Only SecurityException / IllegalArgumentException / IllegalStateException may cross. The two
 * typed IllegalStateException messages -- SketchContract.SKETCH_TOO_LARGE and
 * SketchContract.SKETCH_BAD_IMAGE -- are compared verbatim by the extension (==, never contains).
 * A layer that is not LAYER_GRAPHITE / LAYER_INK is an IllegalArgumentException on either call.
 */
interface ISketchHost {
    /** The current page's state; parks both of its raster images in the read windows. */
    SketchPageState current();

    /**
     * Flip to the page SketchContract.PAGE_PREV / PAGE_NEXT of the current one: the host moves
     * both read windows atomically with the state it answers. At either edge the host answers the
     * SAME page unchanged -- the extension compares pageKey and stays where it is -- so a page turn
     * is never an exception and never a null the caller has to word. The extension pushes its own
     * pixels FIRST: the host's windows are what it will read back, and a save left in flight would
     * land on the page it just left.
     */
    SketchPageState requestPage(int direction);

    /**
     * One chunk of the read window the raster [layer] names, 0-based; outside
     * 0..(that layer's chunk count)-1 is refused, as is an unknown layer. The windows are the page
     * current() / requestPage() last answered with. An absent raster is one empty chunk.
     */
    byte[] readSketchChunk(int layer, int chunkIndex);

    /**
     * One chunk of a save of the raster [layer] names, for the page [pageKey] names -- any live
     * page of the open notebook. Chunks arrive in order from 0 per layer; the host re-checks that
     * layer's running total against SketchContract.MAX_BYTES on receipt (over it:
     * IllegalStateException carrying exactly SketchContract.SKETCH_TOO_LARGE, that layer's
     * accumulation reset, nothing written); [last] commits, and the committed bytes are checked
     * against the page's size before any decode (a mismatch or a malformed header:
     * IllegalStateException carrying exactly SketchContract.SKETCH_BAD_IMAGE).
     *
     * ONE EMPTY CHUNK with last = true is the wire form of "this page has no <layer> raster": the
     * host soft-deletes that layer's row rather than storing a blank page of pixels; the other
     * layer's row is untouched. A refused chunk resets that layer's accumulation; the extension
     * restarts it from chunk 0. The two layers' accumulations are independent (a graphite stream
     * and an ink stream may cross chunk-for-chunk without harm); two streams on the SAME layer may
     * not, which the extension's one push lock already guarantees (SketchSaver's rule).
     */
    void saveSketchChunk(String pageKey, int layer, int chunkIndex, in byte[] chunk, boolean last);

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

    /**
     * Arc 44 / T2 (2026-09-17) -- a compatible tail (the twelfth method, transaction code 12)
     * behind the extension-side declaration floor SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS
     * (19): what the face's drawing tools were last set to ON THIS DEVICE -- the armed tool, the
     * pencil's shade level and its size index -- or NULL when nothing has been remembered yet (a
     * first showing, cleared app data, or a stored value that no longer reads as one). Null is a
     * legal answer to a legal question, never an exception: the face's defaults live in the face,
     * and the host never learns them.
     *
     * One setting for every notebook (the user's decision 6): it is kept in the host's
     * device-local prefs, not in the .soil, not in a backup, and it touches no window -- it is not
     * about a page at all. The face asks once, at begin.
     */
    SketchToolSettings toolSettings();

    /**
     * Arc 44 / T2 -- a compatible tail (the thirteenth method, transaction code 13) behind the
     * same floor: remember [settings] as this device's sketch tools, replacing whatever was kept.
     * The face pushes at every pick, so the value that survives a process death is the last one
     * the person chose. A null is an IllegalArgumentException; an out-of-bounds field never
     * arrives (SketchToolSettings' constructor refuses it at unmarshal).
     *
     * Indices, never values: the host stores three small integers and knows nothing of the greys
     * or the widths they name.
     */
    void putToolSettings(in SketchToolSettings settings);

    /**
     * Arc 51 "Guides" / J1 (2026-09-23) -- a compatible tail (the fourteenth method, transaction
     * code 14) behind the extension-side declaration floor
     * SketchContract.MIN_API_VERSION_FOR_SKETCH_GUIDES (22): the page [pageKey] names' GUIDES --
     * its grid settings and, parked in the host's third read window (the guide window, beside the
     * two raster windows), its reference image -- a lossy WebP with alpha of exactly the page's
     * size, or absent (0 bytes, one chunk). Any LIVE page of the open notebook may be asked; a key
     * naming a page the notebook no longer has is an IllegalArgumentException. The guide window
     * is loaded atomically with the answer and is untouched by current() / requestPage().
     *
     * Guides are TOOLS, never marks (the user's decision): the host stores them as two rows
     * parented to the page (`guide_grid`, `guide_image`) that it never draws, exports, covers or
     * erases, and that page copy / delete / undo carry with the page.
     */
    SketchGuideState guides(String pageKey);

    /**
     * J1 -- a compatible tail (the fifteenth method, transaction code 15) behind the same floor:
     * one chunk of the guide read window, 0-based; outside 0..(the chunk count guides() answered)-1
     * is refused. An absent image is one empty chunk.
     */
    byte[] readGuideImageChunk(int chunkIndex);

    /**
     * J1 -- a compatible tail (the sixteenth method, transaction code 16) behind the same floor:
     * the page [pageKey] names' guide SETTINGS, replacing whatever was kept -- five small ints,
     * indices and a percent, never pixels. GRID_OFF soft-deletes the page's grid row; any other
     * kind upserts it. The image's two fields are written to the image row if one is live and are
     * otherwise IGNORED: a freshly saved image row starts at opacity 0, and the face pushes its
     * settings right after the image lands (save, then put — J2's recorded order). The face pushes at every pick, so
     * the value that survives a process death is the last one the person chose. A null is an
     * IllegalArgumentException; an out-of-bounds field never arrives (the constructor refuses it).
     */
    void putGuides(String pageKey, in SketchGuideSettings settings);

    /**
     * J1 -- a compatible tail (the seventeenth method, transaction code 17) behind the same floor:
     * one chunk of a save of the page [pageKey] names' reference image -- saveSketchChunk's rules
     * exactly, on the guide accumulator (independent of both raster accumulations): chunks in
     * order from 0, the running total re-checked against SketchContract.MAX_BYTES (over it:
     * IllegalStateException carrying exactly SKETCH_TOO_LARGE, the guide accumulation reset,
     * nothing written), [last] commits, and the committed bytes are checked against the page's
     * size before any decode (IllegalStateException carrying exactly SKETCH_BAD_IMAGE). ONE EMPTY
     * CHUNK with last = true is "remove the image": the host soft-deletes the image row. The
     * extension pushes one image per pick under its own push lock, never interleaved with a
     * second guide stream.
     */
    void saveGuideImageChunk(String pageKey, int chunkIndex, in byte[] chunk, boolean last);
}
