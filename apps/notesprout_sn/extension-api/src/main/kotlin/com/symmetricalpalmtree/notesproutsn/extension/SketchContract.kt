package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The SKETCH point (arc 43 / K2) — SN's **TENTH** capability point, the user's explicit
 * 2026-09-15 decision (`extensions/sketch/SKETCH_PLAN.md`, decision 2), and its **sixth
 * screen-owning** one: the extension owns a full-screen raster sketch surface — a graphite pencil
 * and a rubbing eraser over a page-sized bitmap — and **the host owns every `.soil` write**, the
 * document editor's invariant enforced by the same process boundary.
 *
 * A notebook page may carry **one sketch** beside its ink, the way it carries a document. The
 * pixels are a PNG (ARGB_8888, transparent where empty, exactly the page's `width`×`height`) and
 * they cross **chunked in both directions** at [SKETCH_CHUNK_BYTES] ([ByteChunks], the [TextChunks]
 * recipe applied to bytes): the Binder transaction budget is ~1 MB and a page's PNG is measured in
 * megabytes. Reads pull from the host's read window ([ISketchHost.readSketchChunk]); writes push
 * into the host's save accumulator ([ISketchHost.saveSketchChunk]), and the last chunk commits.
 *
 * **Pixels are never logged on either side** — counts, byte totals and durations only (the N-arc
 * privacy rule: what a person drew is content exactly as what they wrote is).
 *
 * **[MAX_BYTES] is the one deliberate deviation from "never refuse".** Everywhere else in this app
 * a size problem is absorbed rather than reported; here it cannot be. The row lives in the `.soil`
 * and comes back through a SQLCipher cursor window, so a PNG written above that window **can never
 * be read back** — accepting it would trade a refusal the person can see for pixels that quietly
 * stop existing. So the host refuses the save, says so, and leaves the stored row exactly as it
 * was. [WATCH_BYTES] is the earlier, silent line: logged, never refused.
 *
 * Nothing rides the screen's launch Intent but [ExtensionContract.EXTRA_CHROME_HIDDEN] — the
 * editor's and the reader's precedent. Show-pages is a **result code**
 * ([RESULT_SKETCH_SHOW_PAGES]), not a stub method; Back is `RESULT_CANCELED`.
 */
object SketchContract {

    /** Intent action a sketch `<service>` declares in its intent-filter (arc 43 / K2). */
    const val ACTION_SKETCH: String =
        "com.symmetricalpalmtree.notesproutsn.extension.SKETCH"

    /** Intent action the extension's exported sketch screen `<activity>` declares; the host
     *  resolves it with `setPackage(<the discovered service's package>)` and launches it **for a
     *  result** (a plain `startActivity` leaves `callingPackage` null and the screen refuses it). */
    const val ACTION_SKETCH_SCREEN: String =
        "com.symmetricalpalmtree.notesproutsn.extension.SKETCH_SCREEN"

    /**
     * The point's **birth floor**: `ACTION_SKETCH` is listed in `MIN_API_VERSIONS` only at 17,
     * because it was never reachable below it — the Bible's 11 and the calendar's 7 exactly. Pinned
     * forever: a floor pins to its birth number, never to [ExtensionContract.API_VERSION] (the
     * arc-31 / HV1 lesson, which is what made `CloudContractTest` re-pin the cloud's to 8).
     */
    const val MIN_API_VERSION_FOR_SKETCH: Int = 17

    /**
     * The **method** floor for [ISketchHost.insertPage] / [ISketchHost.deletePage] /
     * [ISketchHost.pageContent] / [ISketchHost.undoPage] / [ISketchHost.redoPage] (K5b,
     * 2026-09-15 — the user's amendment to decision 7: the face inserts and deletes pages exactly
     * as the notebook does, **and its own undo/redo gestures reverse one**). The number an
     * extension declares is what it **requires of the host**, so a sketch screen that calls those
     * five transaction codes declares 18 and is never discovered by a 17 host that would land them
     * on nothing — the arc-39 `openReference` precedent, this seam's other host-side stub.
     *
     * **Not an action floor:** [MIN_API_VERSION_FOR_SKETCH] stays 17 and `MIN_API_VERSIONS` is
     * untouched, so a screen declaring 17 still binds and still draws; it simply never turns a page
     * into a new one. Only `:ext-sketch` redeclares.
     */
    const val MIN_API_VERSION_FOR_SKETCH_PAGES: Int = 18

    // ── The PNG on the wire ──────

    /** Most bytes in one chunk — 512 KiB, the store's inline carrier, comfortably under the ~1 MB
     *  Binder transaction budget with the ink transfers' headroom. [ByteChunks] holds the rule. */
    const val SKETCH_CHUNK_BYTES: Int = 512 * 1024

    /**
     * The hard refusal: most bytes one page's PNG may be — **6 MiB, the SQLCipher cursor window**.
     * A blob larger than the window cannot be read back out of the `.soil` at all, so a PNG written
     * above this is pixels the person can never see again. The host's accumulator re-checks the
     * running total on every chunk and refuses with [SKETCH_TOO_LARGE]; nothing is written and the
     * stored row stays exactly as it was. See the class note on why this one refuses.
     */
    const val MAX_BYTES: Int = 6 * 1024 * 1024

    /** The quiet line: a save over this is **logged** (bytes only, never pixels) and accepted. It
     *  is how a page on its way to [MAX_BYTES] shows up in a walk's log before it is a problem. */
    const val WATCH_BYTES: Int = 4_000_000

    /**
     * Most chunks one page's PNG can produce — **computed** from the other two (the arc-11 / J6
     * lesson: derive the bound from the rules that produce it, never hand-write it). [ByteChunks]
     * fills every chunk but the last, so `MAX / CHUNK` is exact when the cap divides evenly and
     * short by one when it does not; `+ 1` covers both, and an empty image's one empty chunk is
     * inside the same bound.
     */
    const val MAX_CHUNKS: Int = MAX_BYTES / SKETCH_CHUNK_BYTES + 1

    // ── The save target key ──────

    /**
     * Longest `pageKey` (chars) — **[DocumentContract.MAX_PAGE_KEY_CHARS] itself**, not a copy of
     * its number: the key is the same host-minted opaque token under the same rule (stable for the
     * same target across showings, never displayed, never parsed, not a path and opening nothing),
     * and two constants that must agree are one constant. A save names its target with it, which is
     * what makes the routing guard structural — but unlike the document editor's, a sketch save is
     * accepted for **any live page of the open notebook** (identity by key, no mode routing): the
     * screen turns pages itself and a save flushed a moment after a turn still belongs to the page
     * it was drawn on.
     */
    const val MAX_PAGE_KEY_CHARS: Int = DocumentContract.MAX_PAGE_KEY_CHARS

    // ── The structural edit's name (K5b) ──────

    /**
     * Longest [SketchPageState.structuralToken] (chars) — the host's opaque name for **one page
     * insert or delete**, minted by the host and carried in the face's history so its own undo /
     * redo gestures can ask for that edit back ([ISketchHost.undoPage] / [ISketchHost.redoPage]).
     *
     * A token is a name, never a payload: **the snapshot never crosses.** What a page insert or a
     * delete has to remember — the live page ids either side, the rows it soft-deleted, the page
     * the notebook was on — is the notebook's own undo record and belongs on the notebook's own
     * stack; the face only has to be able to say *which* edit it means. So the wire carries a short
     * word and the host looks it up, which is also what keeps the two stacks provably in step.
     *
     * 64 is generous by an order of magnitude (the host mints `s1`, `s2`, …) and is here for the
     * same reason [MAX_PAGE_KEY_CHARS] is: a bound on an unmarshalled string, checked before it can
     * be held. Displayed nowhere, parsed by nobody, and **space-free** so a token is always one word
     * in a log line.
     */
    const val MAX_STRUCTURAL_TOKEN_CHARS: Int = 64

    // ── requestPage arguments ──────

    /** [ISketchHost.requestPage] direction: the previous / next page. The document editor's values,
     *  aliased rather than restated — one wire vocabulary for "a page flip" across the seam. At an
     *  edge the host answers with the **same** page, so the extension compares `pageKey`. */
    const val PAGE_PREV: Int = DocumentContract.PAGE_PREV
    const val PAGE_NEXT: Int = DocumentContract.PAGE_NEXT

    // ── Page geometry ──────

    /** Smallest / largest page edge in px a [SketchPageState] may name — a sanity bound on an
     *  unmarshalled state, not a device limit (the Nomad's page is 1404×1685, the Manta's 1860×2480).
     *  A bitmap at the upper bound is 256 MB, which is the point: past here the number is wrong. */
    const val MIN_PAGE_PX: Int = 1
    const val MAX_PAGE_PX: Int = 8192

    // ── What else a page is carrying (K5b) ──────

    /**
     * [ISketchHost.pageContent] bit: the page has **live bare content** — strokes, headings, links,
     * text, shapes, sticky notes; everything the host's `liveErasableIds` counts, which is every
     * descendant of the page **except** its sketch (the face is showing that one).
     */
    const val PAGE_HAS_INK: Int = 1

    /** [ISketchHost.pageContent] bit: the page has a live `document` row — its authored Markdown,
     *  which a page delete takes with it like everything else the page owns. */
    const val PAGE_HAS_DOCUMENT: Int = 2

    // ── The screen's result ──────

    /**
     * The sketch screen's result when its **Show pages** door was tapped (= `Activity.RESULT_FIRST_USER`,
     * kept as a literal so a plain JVM test can pin it without `android.app`): the host puts the
     * notebook's own canvas back up on the page the screen ended on. Back is `RESULT_CANCELED` and
     * the host catches the notebook up to that page without showing it. Nothing else crosses on the
     * result Intent but [ExtensionContract.EXTRA_CHROME_HIDDEN], which every paper screen carries.
     */
    const val RESULT_SKETCH_SHOW_PAGES: Int = 1

    // ── Typed refusal messages ──────
    // The `RecognizerClient` recipe (M6's, the document editor's): an `IllegalStateException`
    // crossing Binder intact, carrying one of these EXACT strings, is a condition the caller can act
    // on rather than a generic failure. Matched with `==` on both sides — never `contains`, never a
    // prefix.

    /**
     * A save whose running total passed [MAX_BYTES]. The whole accumulation was reset and
     * **nothing was written**; the stored row is what it was. The screen says so with a dialog and
     * keeps its pixels — they have no other copy — rather than pretending the save landed.
     */
    const val SKETCH_TOO_LARGE: String = "SKETCH_TOO_LARGE"

    /**
     * The committed bytes are not a PNG the host will store: no signature, a truncated header, a
     * first chunk that is not `IHDR`, or an `IHDR` whose width/height are not the page's
     * ([PngHeader.matches], checked on the **last** chunk, before any decode). Nothing was written.
     * A mis-sized image is the one thing a later read cannot recover from, so it is refused at the
     * door rather than stored and discovered.
     */
    const val SKETCH_BAD_PNG: String = "SKETCH_BAD_PNG"

    // There is deliberately NO "no ink" refusal. `requestInk` on a page with no bare strokes answers
    // **0 chunks**, which is a legal answer to a legal question — the pad's zero-chunk park (arc 31 /
    // HV5) already reads an empty first bundle as "done", and a door that throws to say "nothing
    // there" makes the caller handle an exception where a count would do. The screen words the
    // "No ink on this page" alert from the count.
}
