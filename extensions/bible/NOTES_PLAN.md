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
- **N2 — live push (host call sites).** The notebook and the library now feed the index at the
  moment of every act. `NotebookActivity`: the `noteSync` field (`BibleNoteSync` on the
  companion's `appScope`, both reads through the open session — `dao().liveLinkRows()` /
  `session.pages`), `ordinalOf` + `markNotes` (the page's links **after** `liveLinks` is in line
  with the rows), `noteSync.prime()` fired off `openSession` once the `.soil` is open, and the
  marks themselves — page marks from `removeContent` (the three erases), `deleteSelection`,
  `applyLinkEdit` (the picker's Edit), `landLink` (every wrap: `LinkCreated` **and**
  `BibleRefCreated`), `relandEditedLink` (`BibleRefEdited`), `unlinkSelection`, `doErase`, the
  object paste (`doObjectPaste`'s `ObjectsPasted`) and `pasteTransferred`; structural marks from
  `doInsert`, `doDelete`, the page **cut** half of `doCopy`, `doPaste` (`PagePasted`) and `receiveCalendarPages`
  (`PageReceived`/`PagesReceived`); and `markReplayed(a)` at the tail of
  both `doUndo` and `doRedo`, over `BibleNoteIndex.isStructural` / `mayTouchLinks` and gated on
  the replayed page still being the displayed one (the replay ends in `refreshToPage` →
  `navigateTo`, so `liveLinks` is already reloaded when it runs). `flushBeforeSeal()` before
  `s.seal()` in both `close()` and `sealAbandonedOpen()`; `BibleNoteIndex.rename` from
  `renameTextDocument` (the editor's tap-the-title rename, on `appScope` — never a wait on the
  Binder thread) and `BibleNoteIndex.noteDocument` from `lookupBibleReference` after
  `LookupHandoff.park` (decision 3 — the notebook document and a text document go in with no page
  and no ordinal, read off `DocumentHostHooks.scopeIsNotebook` / `targetPageId`). `LibraryActivity`:
  `BibleNoteIndex.rename` after `repo.rename` in `showRenameDialog` (notebooks only) and a
  `forgetNotes` helper on a detached `MainScope` from `confirmDeleteNotebook`,
  `confirmDeleteFolder` (every notebook the folder took) and `retireNotebook`. Both `BibleEntry`
  constructions take `notesEnabled = true` with placeholder `onOpenNote` / `onRebuildNotes` log
  lines (TODO arc 42 N3/N4). 1708 `:app` JVM tests green.
- **N3 — follow (host).** DONE 2026-09-14. A row of the reader's Notes panel now goes where it
  points (decisions 7 + 8). `ForeignPageCheck` lifted out of `LinkFollowFlow` (arc 6's one-shot
  read-only "is that page still there?", now shared rather than copied — the flow calls it);
  `BibleNoteFollowRules.plan` + the four-hop `Plan` (`SamePage` · `SameNotebookDocument` ·
  `Other(notebookId, pageId, openEditor)` · `DeadPage`), pure and JVM-tested
  (`BibleNoteFollowPlanTest`, 8 cases — including the two the spec did not name: a **page-bound
  row whose page is gone is `DeadPage` for either kind**, and a page-less document row on another
  notebook is `Other` with `openEditor = false`); `BibleNoteFollow`, `followOut`'s ritual on a
  `BibleNoteTarget` — index row alive (else `BibleNoteIndex.delete` + the notebook-gone dialog),
  `NotebookPassphrasePrompt` for a `NOTEBOOK`-scope target (cancel = silence), `ForeignPageCheck`
  for a page-bound one (else a whole-notebook re-push from its own rows, or a `delete` when it
  cannot be read at all, + the page-gone dialog), `PassphraseCache.storeOnce`, the trail origin
  **only where there is a current notebook**, then `NotebookActivity.intent` with
  `resumeAbove = [DOCUMENT_EDITOR]` for a page-bound document row. **Both self-heals are
  fire-and-forget on a detached scope** (a judgment call: a cold `.soil` open is seconds, and a
  tap that says nothing for that long reads as broken on e-ink — the dialog's "has been
  refreshed" is a promise kept a moment later). `NotebookActivity`: the `noteFollow` field beside
  `noteSync`, `onDeadPage = { noteSync.markStructural() }`, and the editor opened through
  `runPageOp { documentSeedFlow.start() }` so it lands on the page the flip just asked for (the
  seed flow reads the displayed page at the tap, and `navigateToPage` is fire-and-forget).
  `LibraryActivity`: the same follow with `currentNotebookId = { null }` — `SamePage` is
  unreachable by construction, so its two in-notebook lambdas are log lines — and the launch
  under the library's own `launching` latch. Four strings. **The one thing the host cannot do:
  open *another* notebook's notebook document** — the editor's scope toggle lives inside the
  editor and the replay can only raise it over a page, so a page-less document row on another
  notebook opens that notebook and stops (which is already the whole answer for a text document,
  whose own route opens its editor).
- **N4 — rebuild (host).** DONE 2026-09-14. `BibleNoteRebuild.run(activity, openSession)`
  (decision 9): `IndexRepository.allNotebooks()` → `BibleNoteIndex.prune` first, then per
  notebook — the **open** one through the `OpenSessionReads` the notebook screen hands in (its
  live session's `liveLinkRows` / `pages`; `readOnce` may never be a second connection to an open
  file), everything else through `SoilDatabase.resolve` → `readOnce`, with `NeedsPrompt` / `NoKey`
  counted as `lockedSkipped` and **its existing rows kept** — then `pushNotebook` of
  `notebookNotes`. Progress is the backup screen's shape (a non-cancelable
  `bible_notes_rebuilding` counter, updated per notebook on Main), the done dialog is
  `Dialogs.confirm` (the counts *are* the result — the backup-report precedent) and `run`
  **suspends until it is dismissed**, so the caller's reopen lands on a screen with nothing on
  top of it. Counts and durations logged, never a name. Wired as `rebuildNotes(wire)` on both
  screens — one-at-a-time latch, the notebook passing its open session, the library `null` —
  each ending in `bible.reopen(parkedWire)` behind the same alive checks. Four strings.
  `:app` 1716 JVM tests green (1708 + 8).
- **N5 — docs + freeze.** DONE 2026-09-14. `extensions/bible/docs/bible.md` § "Notes — the
  personal commentary" (the eleven decisions, the seam, the store, `NotesModel`, `NotesPanel`,
  `btnNotes`, the two result codes, judgment calls, privacy, failure table, the Nomad adb walk);
  `apps/notesprout_sn/docs/extensions.md` § "Arc 42's tails: the notes index" + boundary-audit
  rows 61–63 + the `API_VERSION` ledger's 16 entry; `apps/notesprout_sn/docs/links.md` § "The
  Bible kind" (both kinds indexed, `PageLink.createdAt`); `apps/notesprout_sn/docs/notebook.md`
  § "The notes push (arc 42)"; `apps/notesprout_sn/CLAUDE.md` (the status-block paragraph, the
  `:ext-bible` module entry, the ledger line); root `CLAUDE.md` (the `extensions/bible` bullet,
  the docs-table row). **adb walk PASSED 2026-09-14** (`.dev` builds — Notes, Rebuild, follow, a
  live push and Send all verified, one finding fixed: a just-landed link's in-memory `createdAt`
  is now dated now rather than 0); **the user's Nomad hand walk is pending.**
