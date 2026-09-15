# Arc 42 "Notes" — the personal commentary (branch `crossref`, 2026-09-14)

**The user's decision (2026-09-14):** "add a feature to the Bible extension where any notebooks
with references to the Bible can easily be available while reading the Bible. Basically, like a
personal commentary … everything the user writes about any given book, chapter, or reference will
be available when they want to regardless of when they wrote the note."

Biblesprout's commentary keyed entries by an inclusive verse-key range and read them by overlap;
here the entries are the user's own notebook pages, found through the Bible reference objects
(arc 38 / 40) they hold, and the document editor's Lookups (arc 39). The reader never had an index
of where its references sit — the global index knows nothing about pages or objects, the link row
lives inside an encrypted `.soil`, and `IBible` was pull-only — so this arc builds one, in the
reader's own store, fed by the host at the moment of every act.

## The user's locked decisions (2026-09-14 wizard) — do not re-raise

1. **Index owner = the Bible extension's store** (Tags' shape): `note_ref` in `BibleSchema.V4`;
   the host pushes rows over new `IBible` tails; backed up with every store.
2. **Sync = live push at every act + a Rebuild door** (walks every openable notebook; skips
   locked ones silently).
3. **Documents indexed at Lookup only** (the editor's Bible action); nothing scans document text.
4. **One index row per link object** (both kinds), one row per verse range of it.
5. **Scope = chapter in view, verse-sorted** (passage mode: the passage's ranges). Overlap test
   `startKey <= scopeEnd AND endKey >= scopeStart`.
6. **Surface = a side panel in the Recents' shape**, right-hand, "Notes"; **no mark on the page**.
7. **Follow = open over the reader**: the reader closes (the Send rule); the host opens that
   notebook at that page, or the editor for a document row. Same notebook → just flip.
8. **Locked notebooks are indexed; following prompts** for the passphrase.
9. **Rebuild door lives inside the Notes panel** (header button); the reader returns a code, the
   host rebuilds with progress + a done dialog with counts, then reopens the reader where it was.
10. **Door = a button at the lower right of the reader's BOTTOM bar** — a second granted exception
    to "bottom bars are pager-only" (the host's Bible button was the first). `ic_notebook`.
11. Branch `crossref` (stays open); freeze = adb walk then the user's Nomad hand walk; **no code
    review** (the arc 37–41 waiver).

## The shape

- **Seam:** `API_VERSION` 15 → 16; method floor `MIN_API_VERSION_FOR_BIBLE_NOTES` = 16
  (`MIN_API_VERSION_FOR_BIBLE` stays 11). Seven `IBible` tails after `passageText` (codes 7–13):
  `replacePageNotes` · `replaceNotebookNotes` · `renameNotebookNotes` · `deleteNotebookNotes` ·
  `pruneNotes` · `noteDocumentReference` (store-taking, bind-per-call — `ITagManager.assign`'s
  shape) and `takeOutgoingNote()` on the held bind. Parcelables `BibleNote(noteId, pageId,
  pageNumber, wire, at)` and `BibleNoteTarget(notebookId, pageId, kind)`, unmarshal = validation.
  `EXTRA_BIBLE_NOTES_ENABLED`; `RESULT_BIBLE_OPEN_NOTE` 3, `RESULT_BIBLE_REBUILD_NOTES` 4.
- **Store:** `note_ref(noteId, rangeIx, notebookId, pageId, kind, wire, startKey, endKey,
  notebookName, pageNumber, at)`, PK `(noteId, rangeIx)`, indexes on the span and the target.
  `NoteRows` is the ONE reader of a pushed wire (the host never parses one). Every push is one
  `exec` batch; the renumber is a sentinel (unnumber → number each live page → delete the 0s) so
  no id list ever crosses. A document row's id is `doc:<page or notebook>:<wire>`.
- **Host:** per-PAGE reconcile — `replacePageNotes(page, liveLinks)` after every act that touches
  links, debounced 750 ms on `appScope`, gated so a notebook without a Bible link never binds;
  structural page ops push the whole notebook; **nothing at close** (a cold lease pays the KDF).
  Rebuild walks every openable `.soil` (the open one through its live session), prunes first,
  skips locked, reopens the reader. Follow = `LinkFollowFlow.followOut`'s ritual on a
  `BibleNoteTarget`, self-healing a dead target before its dialog.
- **Reader:** `NotesPanel` (the Recents' shape, 60 % sidebar, Rebuild in the header), `btnNotes`
  at the bottom bar's right, `NotesModel` groups rows per notebook + page + kind.

## Judgment calls (stated, not asked)

No Notes door under the editor's Lookup trampoline · one link kind in the panel (kind only
separates link vs document rows) · grouped rows · `at` = the link's `createdAt` · self-heal a
dead target before its dialog · a passage reopens at page 1 after a rebuild, a Full-chapter
instance's Rebuild reopens the chapter · locked notebooks keep their rows on rebuild · 60 % width.

## Ledger

- **N0 — seam + store.** IN PROGRESS 2026-09-14. `ExtensionContract` 16 + the notes constants +
  the test pin; `BibleNote` / `BibleNoteTarget` (+ `BibleNoteTest`); the seven `IBible` tails;
  manifest 16; `BibleSchema.V4` (`NOTE_STEP`), `BibleSql`'s eleven statements (all through the
  host gate in `BibleSqlTest`), `NoteRows` (+ test), `BibleStore`'s seven methods,
  `BibleService`'s seven stubs, `BibleSession.outgoingNote`. `:ext-bible` 139 JVM tests green.
- **N1 — the panel.** DONE 2026-09-14. `NotesModel` (the scope — passage ranges win over the
  chapter's band; `overlaps`, `SELECT_NOTES`' Kotlin twin over the range the wire and
  `rangeIx` name; `group` — `(noteId, rangeIx)` de-duplicated across a passage's several
  queries, then one entry per `(notebookId, pageId, kind)` labelling each note once, sorted
  by where it points then newest first; the two row lines; the 60 % sidebar), `NotesPanel`
  (`RecentsPanel`'s shape in a third subject — right sidebar / full screen below 480 dp,
  `ListSwipe` flip, measured rows, pager `INVISIBLE` at one page, **Rebuild** in the header),
  `dialog_notes.xml` + `item_note_entry.xml` + six strings, `btnNotes` at the bottom bar's
  right end (decision 10's granted exception, `ic_notebook`, GONE without
  `EXTRA_BIBLE_NOTES_ENABLED`), and `BibleActivity`'s three doors — `openNotes`,
  `leaveWithNote` (`BibleNoteTarget` parked, `RESULT_BIBLE_OPEN_NOTE`; a row the type
  refuses is a log line, never a crash) and `leaveForRebuild` (`RESULT_BIBLE_REBUILD_NOTES`,
  parking the passage only) — with both in-process relays widened to the two new codes
  (`relayed`) and the flag forwarded on both own launches. `:ext-bible` 155 JVM tests green.
- N2 — live push.
- N3 — follow.
- N4 — rebuild.
- N5 — docs + freeze.
