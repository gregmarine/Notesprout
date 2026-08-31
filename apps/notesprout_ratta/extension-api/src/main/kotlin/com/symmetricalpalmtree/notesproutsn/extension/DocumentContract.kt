package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The DOCUMENT_EDITOR point (arc 19 / M3) — SN's FIFTH capability point (the user's explicit
 * 2026-08-30 decision), and its second screen-owning one: the extension owns the full-screen
 * Markdown editor Activity; **the host owns every `.soil` read and write** (og's invariant 3,
 * now enforced by a process boundary). The seam's new piece is `IDocumentHost` — a **host-side**
 * callback binder the host passes at `begin(store, host)`, so the editor's autosave pushes text
 * back to the host live and the flush-before-seal invariant holds across the boundary.
 *
 * Text is the only user content that crosses, and it crosses **chunked** ([TextChunks]) in both
 * directions: the Binder transaction budget is ~1 MB and a document may hold up to
 * [MAX_DOCUMENT_CHARS]. Ink never crosses this seam — recognition runs host-side, and only its
 * text output ever reaches the editor (through the same read window as everything else).
 *
 * Document text is never logged on either side — counts, lengths and durations only (the N-arc
 * privacy rule, covering the callback direction too).
 */
object DocumentContract {

    /** Intent action a document-editor `<service>` declares in its intent-filter. */
    const val ACTION_DOCUMENT_EDITOR: String =
        "com.symmetricalpalmtree.notesproutsn.extension.DOCUMENT_EDITOR"

    /** Intent action the extension's exported editor screen `<activity>` declares; the host
     *  resolves it with `setPackage(<the discovered service's package>)` and launches it **for a
     *  result** (a plain `startActivity` leaves `callingPackage` null and the screen refuses it). */
    const val ACTION_DOCUMENT_EDITOR_SCREEN: String =
        "com.symmetricalpalmtree.notesproutsn.extension.DOCUMENT_EDITOR_SCREEN"

    // ── The whole-document cap ──────
    // Guards BOTH directions: the host refuses to open the editor over a larger document before
    // any bind, and the host's save accumulator re-checks the running total on receipt.

    /**
     * Most chars in one document — 10,000,000, aligned with the text importer's 10 MB byte cap
     * (M8): UTF-8 encodes every char in at least one byte, so any file that import cap admits is
     * guaranteed to stay editable. Locked at the M3 wizard (2026-08-30).
     */
    const val MAX_DOCUMENT_CHARS: Int = 10_000_000

    // ── Text chunking (the InkChunks recipe applied to text) ──────

    /** Most chars in one text chunk (≈ 200 KB as UTF-16 on the wire — under the ~1 MB Binder
     *  transaction budget with the ink transfers' headroom). [TextChunks] holds the rule. */
    const val TEXT_CHUNK_CHARS: Int = 100_000

    /**
     * Most chunks one document can produce — a safe upper bound on what [TextChunks.chunk] yields
     * for any text within [MAX_DOCUMENT_CHARS], **computed** from the other two (the arc-11 / J6
     * lesson: derive the bound from the rules that produce it, never hand-write it). A chunk may
     * close one char short of [TEXT_CHUNK_CHARS] (the surrogate-pair backoff in [TextChunks]), so
     * the naive `MAX / CHUNK` undercounts by at most one; `+ 1` covers it, and empty text's one
     * empty chunk is inside the same bound.
     */
    const val TEXT_MAX_CHUNKS: Int = MAX_DOCUMENT_CHARS / TEXT_CHUNK_CHARS + 1

    // ── The save target key ──────

    /**
     * Longest `pageKey` (chars). The key is a **host-minted opaque token** naming the save
     * target — stable for the same target across showings (the extension keys its per-page
     * device-local state in the extension store with it), never displayed, never parsed. It is
     * deliberately not a path and opens nothing; the extension treats it as a bare handle.
     * Every save names its target with it, which is what makes the mode-routing guard structural:
     * a save whose key is not the current target's is refused, so notebook-document text can
     * never land on a page row or vice versa.
     */
    const val MAX_PAGE_KEY_CHARS: Int = 64

    // ── DocumentPageState fields ──────

    /** [DocumentPageState.scope]: the target is a page's document / the notebook document. */
    const val SCOPE_PAGE: Int = 0
    const val SCOPE_NOTEBOOK: Int = 1

    /** [DocumentPageState.source] — the editor's source-strip state for the target: no draft
     *  relationship (authored by hand or empty) / drafted from the page and unchanged since /
     *  drafted but the page has changed since ("stale"). */
    const val SOURCE_NONE: Int = 0
    const val SOURCE_DRAFTED: Int = 1
    const val SOURCE_STALE: Int = 2

    /** Longest [DocumentPageState.title] (chars) — the notebook's display name, display only. */
    const val MAX_TITLE_CHARS: Int = 200

    // ── requestPage / closeNotebook arguments ──────

    /** `IDocumentHost.requestPage` direction: the previous / next page (M6). */
    const val PAGE_PREV: Int = -1
    const val PAGE_NEXT: Int = 1

    /** `IDocumentHost.requestSeed` / `requestMerge` mode: replace the document with the page's
     *  text / append it under a rule (M6 / M7). The Replace-or-Append sheet runs editor-side
     *  **before** the request, so nobody waits through a recognition they then cancel. */
    const val BRING_REPLACE: Int = 0
    const val BRING_APPEND: Int = 1

    /** `IDocumentHost.closeNotebook` mode (M8, text documents): ✓ Done = show the pages /
     *  Close = seal straight back to the library. */
    const val CLOSE_SHOW_PAGES: Int = 0
    const val CLOSE_TO_LIBRARY: Int = 1

    // ── Typed refusal messages (M6) ──────
    // The `RecognizerClient` recipe: an `IllegalStateException` crossing Binder intact, carrying one
    // of these EXACT strings, is a condition the caller can act on rather than a generic failure.
    // Matched with `==` on both sides — never `contains`, never a prefix.

    /**
     * `requestSeed` / `requestMerge` could not produce a draft because recognition is not there to
     * run: no recognizer extension installed, the model not downloaded, or the engine refused. The
     * editor explains ("recognition isn't available"); nothing was written and the page stays
     * seedable. Distinct from a generic failure so the editor can say *why* rather than "failed".
     */
    const val SEED_UNAVAILABLE: String = "SEED_UNAVAILABLE"

    /**
     * A `saveChunk` commit with `drafted = true` arrived with no watermark parked host-side — the
     * park died with a host restart, or an ordinary edit tried to invent a draft. The whole
     * accumulation was reset and **nothing was written**. The editor's honest recovery is to clear
     * its draft-pending flag and retry the same text as an ordinary save: the words land, and only
     * the provenance anchor is lost (the strip reads "not drafted" until the next Bring in).
     */
    const val NO_DRAFT_PENDING: String = "NO_DRAFT_PENDING"

    /**
     * A notebook merge (`requestMerge`, M7) the editor cancelled via `cancelRequest` — the host
     * abandoned the per-page loop between pages, **nothing was written**, and the target and scope
     * are exactly what they were. The editor stays silent: the user just said no, and a dialog
     * explaining a cancellation they asked for would be noise. (A cancelled `requestScope`
     * auto-merge answers null like any other failed scope switch — this message crosses only from
     * `requestMerge`, which mirrors `requestSeed`'s throw-not-null asymmetry.)
     */
    const val MERGE_CANCELLED: String = "MERGE_CANCELLED"
}
