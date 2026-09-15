package com.symmetricalpalmtree.notesproutsn.extension;

import com.symmetricalpalmtree.notesproutsn.extension.BibleNote;
import com.symmetricalpalmtree.notesproutsn.extension.BibleNoteTarget;
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore;
import com.symmetricalpalmtree.notesproutsn.extension.PassageText;
import com.symmetricalpalmtree.notesproutsn.extension.ResolvedReference;

/**
 * The Bible-reader point (arc 37 / B0) — SN's NINTH capability point, the fifth screen-owning one
 * and the second with no paper. The tag manager's showing bracket and nothing more: the host
 * pre-opens the store, holds the bind, calls `begin(store)`, launches the screen for a result,
 * and calls `end()` when the screen has returned. The store carries the reader's one row of
 * per-device state (the last-read position); scripture never crosses this seam.
 *
 * Nothing rides the screen's Intent. Only the three marshalable exceptions leave a stub method.
 *
 * **Arc 38 / R1 appended two compatible tails after `end()`** (transaction codes 3 and 4 — the
 * calendar's `render` precedent), both behind the METHOD floor
 * `ExtensionContract.MIN_API_VERSION_FOR_BIBLE_REFERENCE` (12): a reader declaring 11 still
 * serves the plain door, and the host offers the notebook's reference doors only to a reader
 * declaring 12. Neither changes an existing method, so no existing door moves.
 */
interface IBible {
    /** Lend the store for the showing. Called once per showing, before the screen launches. */
    void begin(IExtensionStore store);
    /** The showing is over; drop the parked store. Best-effort on the host side. */
    void end();

    /**
     * Arc 38 / R1 — **bind-per-call, no store.** Read [text] (the user's own words, e.g.
     * `"Jn 3:16-18, Prov 3:5-6"`) as one or more scripture references and answer the canonical
     * form, or null when any part of it is not a reference this Bible knows (an unknown book, a
     * chapter past the book's end, a verse that does not exist). The text is never logged on
     * either side. The extension parses and checks against its own `.bible`; the host never
     * learns the canon.
     */
    ResolvedReference resolve(String text);

    /**
     * Arc 38 / R1 — the showing bracket's second opening: `begin(store)` **and** a resolved
     * reference's wire form ([ResolvedReference.wire]) to open the screen on — the passage view
     * of just those verses, with the Full chapter door. Paired with the same `end()`.
     */
    void beginAt(IExtensionStore store, String reference);

    /**
     * B9 "Send" (2026-09-13) — a compatible tail (transaction code 5) behind the METHOD floor
     * `ExtensionContract.MIN_API_VERSION_FOR_BIBLE_SEND` (13): the reference the reader's Send
     * to notebook button parked — the chapter being read as a whole chapter, or the passage on
     * screen — as a resolved reference (wire + canonical label), or null when nothing was parked.
     * Once-only: the parked reference is cleared on the way out, and `end()` clears it too. The
     * host calls it on the held bind right after the screen returned `RESULT_BIBLE_SEND`, before
     * `end()` — the calendar's `takeOutgoing` shape on a reference instead of ink. Never logged.
     */
    ResolvedReference takeOutgoingReference();

    /**
     * Arc 40 "Verses" (2026-09-13) — a compatible tail (transaction code 6) behind the METHOD
     * floor `ExtensionContract.MIN_API_VERSION_FOR_BIBLE_TEXT` (15), and **the first method
     * through which scripture crosses this seam**: the verses [wire] names, as Markdown the host
     * lands on the page as a text object (a bold canonical label line, then the verses with plain
     * numbers, one paragraph per chapter run). Bind-per-call or on the held bind — no store.
     * A whole chapter, or more than the reader's verse cap, is refused with
     * `PassageText.STATUS_TOO_LONG`; a wire this reader cannot read, or a source it cannot open,
     * is null. Neither the wire nor a character of the text is ever logged on either side.
     */
    PassageText passageText(String wire);

    // ── Arc 42 "Notes" (2026-09-14) — seven compatible tails (transaction codes 7–13) behind the
    // METHOD floor `ExtensionContract.MIN_API_VERSION_FOR_BIBLE_NOTES` (16). The reader keeps an
    // index of where Bible references sit in the user's notebooks (`note_ref` in its store) and
    // lists the pages referencing the chapter being read — a personal commentary. The HOST pushes
    // rows at the moment of each act and never parses a wire; the extension decodes it to verse
    // keys on insert. The six push calls are STORE-TAKING, BIND-PER-CALL — the tag manager's
    // `assign` shape: the store rides the call, nothing is held. Notebook names are user content:
    // never logged on either side (counts and durations only). Every method:
    // HostCallerCheck.enforce first; IllegalArgumentException for a malformed argument,
    // IllegalStateException("store unavailable") for a store that will not answer.

    /**
     * Replace the LINK rows of one page with [notes] (every Bible link object now live on it, one
     * `BibleNote` per link; an empty list clears the page), refreshing [notebookName] on every
     * row of the notebook and stamping [pageNumber] (1-based) on this page's rows. Idempotent:
     * the same call twice is one state. One transaction. Every note's `pageId` must equal [pageId].
     */
    void replacePageNotes(IExtensionStore store, String notebookId, String notebookName,
                          String pageId, int pageNumber, in List<BibleNote> notes);

    /**
     * Replace every LINK row of a notebook with [notes], refresh its name, renumber every row of
     * the notebook from [livePageIds] (its pages in order, 1-based) and drop the rows of pages no
     * longer in that list (document rows included; a document row on the notebook itself — an
     * empty page id — is kept). One transaction. The rebuild's and a structural page change's door.
     */
    void replaceNotebookNotes(IExtensionStore store, String notebookId, String notebookName,
                              in List<String> livePageIds, in List<BibleNote> notes);

    /** The notebook was renamed: one UPDATE of every row's name. */
    void renameNotebookNotes(IExtensionStore store, String notebookId, String notebookName);

    /** The notebook is gone: one DELETE of every row of it, any kind. */
    void deleteNotebookNotes(IExtensionStore store, String notebookId);

    /** Drop every row of a notebook that is not in [aliveNotebookIds] — the rebuild's first step. */
    void pruneNotes(IExtensionStore store, in List<String> aliveNotebookIds);

    /**
     * A reference was looked up from a document (the editor's Bible action, arc 39): record a
     * DOCUMENT row for it against [pageId] ([pageNumber] 1-based), or against the notebook itself
     * when [pageId] is empty (a text-document notebook, the notebook document; [pageNumber] 0).
     * Re-looking up the same reference on the same document re-stamps the row it already has.
     * [wire] is a `ResolvedReference.wire`, opaque to the host.
     */
    void noteDocumentReference(IExtensionStore store, String notebookId, String notebookName,
                               String pageId, int pageNumber, String wire);

    /**
     * On the held bind, once-only (`takeOutgoingReference`'s shape): the target of the note row
     * the user tapped in the reader's Notes panel, or null when nothing was parked. The host calls
     * it right after the screen returned `RESULT_BIBLE_OPEN_NOTE`, before `end()`.
     */
    BibleNoteTarget takeOutgoingNote();
}
