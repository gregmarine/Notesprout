# Document (arc 19)

**The page is the draft; the document is the result.** A handwritten page's ink flows into a
Markdown document once — seeded via recognition — and from then on the document belongs to the
user; nothing on the page overwrites it again unless asked. One document per page, one more per
**notebook** (the merged final draft), and **text documents** — notebooks whose primary surface
*is* the editor rather than a canvas.

This arc ports og's Documents feature (`docs/documents.md` + `docs/proofread.md` at the monorepo
root, the reading references — no code copied) as SN's **fifth capability point**,
`ACTION_DOCUMENT_EDITOR`, served by a new extension: `NSE · Document` (`:ext-document`). It is the
biggest arc yet: eleven phases (M1–M11, letter M — D is taken by the PDF arc), a new shared
engine module (`:markdown`), and — on the user's explicit call — Proofread, ported in full.

This is the feature doc. The seam — the AIDL, the host-callback binder, the boundary audit — is
[`docs/extensions.md`](extensions.md) § the fifth point. The export screen's document-source rows
are [`docs/export.md`](export.md). The importer's result-kind tail is
[`docs/import.md`](import.md) § the text path. The notebook's Document button is
[`docs/notebook.md`](notebook.md).

**Status: arc 19 in progress** — M1–M10 complete (M1 `:markdown` · M2 host data layer · M3 the
point/AIDL/`:ext-document` · M4 the real editor · M5 editor tools · M6 seeding · M7 the notebook
document · M8 text documents + import · M9 export · M10 proofread), M11 (review, boundary audit,
docs, freeze) in progress — this doc is part of M11.

---

## Why an extension, and what that costs

The seam's foundational rule from arc 11 stands: **the host owns every `.soil` read and write**
(an extension writes nothing to disk, ever).
og's `DocumentEditorActivity` ran in the same process as the notebook and read the file itself;
SN's editor runs in a **different process**, so that invariant now has to survive an actual
process boundary rather than just a code convention. The seam's new piece is `IDocumentHost` — a
callback binder the host passes to the extension at `begin()`, so autosave can push text to the
host live. It is SN's **first host-side stub on any extension seam**: every previous point had
the host calling the extension; this one has the extension calling back into the host, minted per
showing and gated on the extension's own uid in every method (`DocumentHostBinder.gate` — the
`ExtensionStoreBinder` recipe, mirrored), revoked with the unbind.

Two things follow directly from that boundary, and both recur through this doc:

- **Text crosses chunked.** A document can run to 10,000,000 characters
  (`DocumentContract.MAX_DOCUMENT_CHARS`) and the Binder transaction budget is ~1 MB, so text
  moves in `TEXT_CHUNK_CHARS`-sized pieces (100,000 chars, `TextChunks` — the same chunking recipe
  arc 11's `InkChunks` established for strokes, applied to text and pinned by test on both sides).
  `TEXT_MAX_CHUNKS` is **computed**, not hand-picked (101, from the cap and the chunk size).
  Reading is a **pull**: every state-answering call parks its text in the host's read window
  atomically with the state, and `readChunk` serves pieces of it. Writing is a **push**:
  `saveChunk(pageKey, index, chunk, last, drafted)`, ordered from 0, the cap re-checked on
  receipt — **any refusal resets the whole save**, never a partial document.
- **The two-process teardown table is the feature's soul.** og's four-failure autosave table
  (flush before seal, process death, config-change recreation, editor recreation) had to be
  re-derived with an actual process boundary in the middle of it, not just a screen boundary. See
  [Two-process autosave and teardown](#two-process-autosave-and-teardown) below.

`pageKey` names the save/load target on every call — the mode-routing guard made structural. It
is the page row's own id used as an **opaque stable token** (never parsed, only compared by
equality); the notebook document's is the minted token `"nb:<notebookId>"` (below). No path, no
notebook id, no passphrase, and no ink ever crosses this seam — only chunked text and the small
per-device state (caret memory, text size, the proofread toggle, the user dictionary) that lives
in the extension store.

---

## Data model

A third additive row type on the family shape — the heading/link precedent: no `.soil` version
bump, no migration, Paper ignores the rows.

| Field | Column | Notes |
|---|---|---|
| `type` | — | `TYPE_DOCUMENT = "document"` |
| `parentId` | — | The page's id (a page document), or the notebook root object's id (the notebook document — og's shape) |
| markdown | `text` | The document itself |
| source watermark | `flags` | Epoch millis of the source content at the last seed/refresh; **NULL = authored by hand, never drafted** |

**The watermark rides the free `flags` column instead of a new one.** og needed a dedicated
`srcUpdatedAt` column because its table had no spare 64-bit slot; SN's universal `notebook` table
does — `flags` is a 64-bit SQLite `INTEGER` each row type uses its own way (a heading's level
lives there), and for document rows it carries the watermark.
Making this work meant retyping `SoilObjectEntity.flags` (and `ClipRow.flags`, so a page copy
carries the watermark verbatim) from `Int?` to `Long?` — same `INTEGER` column affinity, so the
Room identity hash does not move and a pre-arc file stays openable (verified against a real file
on the Nomad before M2 shipped).

**Blank means absent — and SN enforces it stricter than og.** og allowed a document row to hold
empty text; SN's `DocumentRepository` treats a blank save as a **soft-delete**: no row, or a row
soft-deleted back to nothing, both read as "undrafted," and no separate "has been seeded" flag is
needed. `save` also **drops a write that would change nothing** (read-then-compare before the
write), and — structurally — `save` can never touch the watermark; only `saveDrafted` moves it.
That split is what makes "the only writer of `srcUpdatedAt` is a seed or a refresh" hold without
relying on caller discipline the way og's single method + convention did.

**Staleness sweeps count soft-deleted rows** (og's rule: an erase is a page change too) — with
one SN-only wrinkle, written down rather than "fixed": arc 17's seal-time purge hard-deletes
soft-deleted rows on close, so an erase-raised staleness signal lasts only until the notebook next
closes; after that, the max content timestamp honestly describes what remains. This is accepted
behavior, not a bug — do not "fix" it by, say, excluding the purge from documents.

`NotebookFlags.TEXT_DOCUMENT = 4` (bit 2) marks a notebook whose primary surface is the editor
(below); `notebook_meta`'s JSON gains an additive `textDocument` field (codec default `false`).
All three sites that rebuild `notebook_meta` from the index source this bit from the **index row**,
never from the previous meta blob — the same wipe trap arc 17's `EXCLUDE_FROM_BACKUP` bit had to
be defended against. Import reads the probed manifest's flag once and feeds both writes, so a
text document imported from another device stays one.

Document rows are **excluded** from the page-level content-staleness whitelist (a document is a
product of the page, not content on it — including it would mark the page edited by its own
document) but **included** in the notebook document's own notebook-wide sweep (a page-document
edit is still "the pages have changed," og's rule). `liveDescendantIds` gained `'document'` at the
**page level only** — a page copy/cut/paste/delete/undo carries its document; a link never wraps
one. `SoilCompactor` purges a document row only via cascade from its purged page — there is no
independent document-purge path.

---

## The editor

`:ext-document`'s Activity is the extension's screen for `ACTION_DOCUMENT_EDITOR` — SN's second
screen-owning (tier-2) point after the scratch pad, and its first non-paper full-screen Activity
launched over a live `NotebookActivity`. Chrome, top to bottom: header (`‹ n / m ›`, text size,
Write/Preview, the scope toggle) — source strip — format bar (with overflow) — the text.

**There is no Done button.** og kept a `✓ Done` beside `Write`/`Preview`; SN's M6 user checklist
removed it deliberately: every way out of the editor already saves and nothing ever discards, so a
second control that did the same thing as the back arrow was pure duplication on a narrow screen.
The back arrow is the **one leave door**. (The debug automation hook's `done` command still exists
and leaves with `RESULT_OK`, which the host treats identically to a back-arrow close.) This is a
deliberate divergence from og, not an omission — the user's own call, recorded at M6.

**Format bar: full og parity.** 13 tools across og's four groups and dividers, og's hint strings
with their chords, plus og's four **chord-only** extras that have no bar button at all: `Ctrl+P`
(the one live Preview-mode chord), `Ctrl+0` (paragraph), `Ctrl+4`–`Ctrl+6` (headings). The bar
overflows rather than scrolling — the same reasoning and the same reused overflow mechanism as
the notebook toolbar (`FormatBarOverflow`: moves real views, in-flow, an outside tap dismisses it
without being consumed, the dots button never auto-dismisses itself).

**List continuation** is watcher-based, not key-event-based (a soft keyboard's Enter may not send
a key event at all): `newlineAt`, read-and-clear, is the re-entrancy guard against the watcher
re-triggering itself. Ordered runs renumber **back-to-front with caret delta**, so a mid-list
insertion or deletion never shuffles the caret out of the word it was in — the same
`MarkdownFormatter.listEnter`/`renumberOrderedLists` pure logic `:markdown` carries from M1,
shared with nothing else in SN (there is no on-page `TextEditDialog` equivalent yet).

**The Ratta hardware-keyboard rule holds from day one**: hardware keys type only while the IME is
shown, so an attached keyboard is never a reason to hide it here — the manifest declares
`keyboard|keyboardHidden` on both `NotebookActivity` and the editor Activity so a Bluetooth
keyboard attaching never destroys either process behind the other, and there is no IME-hide call
anywhere in the editor's code.

**The EPD question — answered, no handoff needed.** Whether the notebook needed
`releaseForHandoff()` around the editor (the scratch-pad ordering) was measured on the Nomad, not
assumed: with the Document screen open, pen scribble on the notebook drew nothing (no daemon ink,
no trails), and on return ink flowed normally with no ghosting. **Stop-behind is enough** — the
arc-13 template-picker precedent extends across the process boundary, and every later phase
inherits this answer with no handoff code anywhere in the seam.

---

## Two-process autosave and teardown

og's four-failure table (flush before seal, process death, config-change recreation, editor
recreation) is the feature's soul, re-derived for a real process boundary and pinned by test.

The pure pieces: `AutosaveGovernor` (tracks `savedText`/dirty/one-push-in-flight, newest-wins
queue — a stale in-flight push can never overwrite a newer edit), `ChunkPush` (a fun-interface
sink the governor pushes through), `PendingPark` (a save that failed to reach the host, parked
keyed by target page — **a key mismatch on delivery is a deliberate drop**, not a retry: page
UUIDs are globally unique, so a park addressed to one page landing on another would be
corruption, never a coincidence worth forgiving), and `DocumentSaver` (snapshots the buffer on
Main, pushes on IO under a `Mutex`, 2 s idle debounce + 2 s retry, and — critically — its
coroutine scope is **never cancelled**, so the save fired from `onPause` survives the Activity's
own teardown).

| Failure | What closes it |
|---|---|
| Flush before the host seals the `.soil` | `end()`'s backstop: the service asks the host `current()` first (a host that cannot answer leaves everything parked, never lost), then pushes the live buffer **through the saver's own push lock** (`FlushHook.pushBlocking` — never interleaving with an in-flight autosave) and resolves the park by key; failures re-park. Host-side, every seal path joins the entry's `finish()` Job before sealing and flips the document gate only then — flush-before-seal enforced, not hoped for. The host's `end()` call carries an `END_TIMEOUT_MS` of 15 s so a stuck extension cannot hang the seal forever. |
| Host process dies behind a live editor | Host-driven **reconnect**, not extension-driven: saved state (`KEY_DOCUMENT_SHOWING`) tells the recreated host to call `DocumentEditorEntry.reconnect()`, which re-opens the connection **without re-launching** the editor Activity; a normal `onResult` arriving mid-reconnect **joins** the same operation rather than cancelling it. On the extension side, a `begin()` call arriving while one is already held means "the host restarted": a live screen re-reads `current()` and flushes on a matching key; with no screen alive, a daemon thread retries the pending push against `current()` up to 10× over 500 ms. `DocumentHostHooks.openSession` waits up to 8 s (bounded) for the recreated host's own async DB open to finish before it can accept the reconnect. |
| Config change (a Bluetooth keyboard attaching, etc.) | `keyboard\|keyboardHidden` in both Activities' `configChanges`, plus `stateUnchanged\|adjustResize` — no recreation, so nothing here is even exercised by the common case. |
| Editor process recreation | The buffer travels explicitly in saved instance state, capped at 256k chars (a `Bundle` rides a Binder transaction too) — the restored buffer is treated as unsaved, same as og's shape. |

**Caret handover rides every save trigger**, even one that changes no text — og's rule, carried
over so that browsing without typing never loses the reading position; `DocumentSaver` fires
`caretSink` at the top of `saveNow()` and again in `flushAndThen` ahead of its clean-buffer early
return.

**The kill-host edge is accepted, not fixed.** If the host process is killed *and* the editor is
closed before the host's own reconnect can run, `IndexGuard`'s bounce (a relaunch through
Bootstrap) eats the host's saved state entirely — no reconnect fires at all. The parked final save
then sits in the still-live extension process and is **dropped on key mismatch** at the next
showing (the same `PendingPark` rule doing its job on a stranger target). The exposure is limited
to keystrokes typed between the host's death and the editor's own close; it is corruption-proof by
construction (a wrong-key write is never attempted), and it is accepted because og's single-process
shape simply cannot express this failure mode at all — there is no smaller fix that keeps the
never-write-the-wrong-page guarantee.

**The flip gap is also a no-save zone**, guarded the same way og guarded it, now across the
process boundary: `PageFlipController.prepareFlip` fires the outgoing page's caret save under the
*outgoing* key and **abandons the governor's queue** (a queued older snapshot completing after the
flip's own push started could otherwise land stale text over what the flip just wrote), then
blocks on the outgoing push under the shared save lock — bookkeeping wrapped `NonCancellable` so a
Done landing mid-flip cannot skip clearing the pending park and leave a stale replay for the next
teardown flush. A failed flip leaves the editor exactly where it was (abort-on-failure); a
successful one calls `requestPage`, looks its own caret up fresh (`EditorPrefs.caret`, top for a
freshly-seeded page), and installs the new text with `setText` — **never** through the `Editable`
edit path, so arriving at a different page is never itself an undoable action.

---

## Seeding, Bring in, and page flips

Seeding is host-side, ordered exactly like og's: flush ink first (an unflushed stroke would be
missing from recognition), then check the stored document — non-blank text hands over instantly
with no recognition at all. Only an undocumented page goes to `RecognizerClient.recognizePage`
behind a "Reading this page…" popup, gated by the existing `RecognizerReadiness` consent flow (no
recognizer ready ⇒ open empty, page stays seedable for a later visit). **The watermark is read
before recognition runs, on every path** — seed, Bring in, and a flip alike. **Recognition is
entirely host-side**: only text crosses the seam, never ink.

`DocumentPageState` carries a `seeded` flag meaning "the read window holds a fresh draft the host
has not stored yet" — the editor treats that state as unsaved and pushes `drafted = true` until a
save actually commits it, which is what makes "open the editor once on a written page" the act of
drafting it, exactly as og described.

**Bring in** (renamed **Merge** in notebook scope, below) opens its Replace/Append sheet
**before** any recognition runs — no "cancel after waiting" state exists, matching og. Applied
through the buffer's `Editable`, so the editor's own Ctrl+Z takes a Bring-in back within the
session. One SN-specific fix landed here at M6's device walk: a Bring-in whose recognized text
came out **identical** to the buffer never pushed, because `saveNow` drops unchanged writes —
which silently orphaned the parked watermark. `AutosaveGovernor.requestDraft` +
`DocumentSaver.saveDraftNow` give a Bring-in save its own path that bypasses the unchanged-drop,
so both choices always re-anchor `srcUpdatedAt`, even when the words did not change — device-proven
across a close/reopen.

Two typed refusal messages travel the seam (matched by `==`, the `RecognizerClient` recipe):
`SEED_UNAVAILABLE` (Bring in tapped with no recognizer ready) and `NO_DRAFT_PENDING` (a drafted
commit whose parked watermark died before it landed — the editor **downgrades** gracefully: the
drafted claim is cleared and the same words are re-sent as an ordinary save, so only provenance is
lost, never the text).

**Source strip** states, unchanged from og's wording: "Drafted from this page" / "Page has changed
since this draft" / "Not drafted from this page" (hand-authored).

**In-editor page flips** (`‹ ›`, `Ctrl+PgUp`/`Ctrl+PgDn`, both Write and Preview) move to a
neighbouring page's document through `IDocumentHost.requestPage`, always answering from a
`finally` — `null` on any failure, with the target and read window left untouched so a failed flip
never leaves the editor stuck. A flip's own seed check is **silent READY-or-nothing**: no consent
dialog, and it never triggers a model download — that consent belongs only to the explicit
open-time and Bring-in/Merge paths. The editor's own "Reading this page…" popup on a flip is
delayed 350 ms so a drafted page flips with no dialog flash at all on e-ink; Bring in/Merge show it
immediately, since those always read in full. Edge taps say "First page." / "Last page." — never a
disabled arrow. **The notebook catches up only when the editor closes** (`navigateToPage(endedOn)`
on the result) — the host is stopped behind the editor the whole time it is open, so no dialog and
no navigation can safely touch it before then.

---

## The notebook document

An ordinary `document` row parented to the notebook root object — the whole notebook's pages
merged into one final draft, edited as its own text once merged, and re-merged only by asking.

**Scope toggle**, in the editor header: `ic_notebook` on a page's document, `ic_file_text` on the
notebook document — the icon names **where the tap goes**, not where you are, og's own
convention. It is a page flip in every way that matters: text is stored first, the host switches
targets, the gap is the same no-save zone under the same guards
(`PageFlipController.switchScope` shares its one private `move()` path with the ordinary page
flip). The `‹ n / m ›` cluster disappears entirely in notebook scope (there is no neighbouring
page), and `Ctrl+PgUp`/`Ctrl+PgDn` become silent no-ops.

**`pageKey` is the minted token `"nb:<notebookId>"`** — og's mode-routing flag made structural
rather than a saved-state boolean: no page row can ever own that key, so the accumulator's own key
guard refuses a cross-scope save by construction, and the caret-memory table lands on that same
key for free with no special-casing.

**First toggle auto-merges.** An undrafted notebook document is seeded exactly the way an
undrafted page is — toggling in *is* the act of drafting. The merge loop is pure
`mergePagePart` (a page's own document wins when non-blank; otherwise recognized text, but **only
when a recognizer is READY** — see below) joined by pure `mergeText`: `"\n\n"` between pages,
whole-trimmed once at the end, **not** trimmed per part (og-verbatim; the `---` separator belongs
only to Append via `DocumentDraft.append`, never to the merge join itself). Because this run can
recognize every undocumented page, it is the **one** reading popup in the whole feature that
carries a real Cancel button — a volatile flag checked between pages (harmless as a no-op when the
merge is idle), surfaced as the typed `MERGE_CANCELLED` message. **A blank merge is a silent
no-op** (`ScopeRules.mergeLands`) — the same Replace-over-blank-pages protection og's null-draft
rule provided, now pure and tested.

**Fixed relative to og, deliberately:** og's merge voided the *entire* pass — documents included —
when no recognizer was READY at all. SN's merge **always** assembles page documents; recognition
only ever contributes a per-page fallback when READY, silently skipped otherwise (the same silent
rule M6's flips use). This was a wizard-locked decision, not a bug carried forward.

**A reconnect never re-merges.** `loadCurrent`'s notebook branch serves only the **stored** notebook
document on a host reconnect — never a fresh merge, never fresh recognition — so a recreated
editor's still-pending drafted save can still anchor correctly against the row that is actually on
disk.

**Staleness is notebook-wide**: any page's ink *or* any page document's own edit reads as "Pages
have changed since this merge," while the notebook document itself is excluded from its own
staleness sweep. "Bring in" becomes **Merge** in this scope, same Replace/Append sheet ("Merge
pages" title), same before-recognition ordering.

---

## Text documents

A notebook whose **primary surface is the editor** — a typed document that happens to live in the
`.soil`/index/backup/export pipeline exactly like any other notebook. Flagged by
`NotebookFlags.TEXT_DOCUMENT` (index) mirrored by `notebook_meta.textDocument` (file), created via
a **Handwritten / Text** radio on the create screen.

**Routing is a pure decision table** (`TextDocRouting`, structurally separated from the Activity
so the shape can be tested without a device): `openDecision` resolves to `CANVAS` / `SEAL_AND_LEAVE`
/ `EDITOR_LAUNCH` / `EDITOR_RECONNECT`; `closeDecision` to `CATCH_UP` / `LOAD_CANVAS` /
`SEAL_TO_LIBRARY`. A close arriving while a recreated host is still opening is captured by
`parkClose` — deliberately a box type (`ParkedClose`), not a bare nullable `Int`, because "no
result yet" and "a result that carries no advisory" are different states that a plain `Int?` could
not distinguish. `canvasShown` is a **one-way latch** (saved as `KEY_CANVAS_SHOWN`): once a text
document's pages have been shown in this incarnation, it behaves like an ordinary notebook for the
rest of it.

**`closeNotebook` is advisory, host-recorded state, consumed exactly once** via
`takeCloseMode()` — read **before** `resetTarget()` clears it. **A null advisory means "to the
library"** — the deliberate fail-safe direction: a notebook that reopens to the library when it
should have shown its canvas is merely inconvenient; a canvas that loads when it should not have
is a page loaded that nothing asked to load.

**The Show-Pages control replaces og's `✓ Done`.** Since M6 removed the editor's Done button
entirely, text documents needed a different exit for "leave the editor, but land on the canvas
instead of the library." `btnShowPages` sits at the header's far trailing end and is visible in
**either** scope of a text document (a checklist fix from the initial build, which had it
notebook-scope-only — true og parity actually put Show-Pages in both scopes; only og's separate
Close button was notebook-mode-only, and SN's back arrow already fills that role). A tap calls
`IDocumentHost.closeNotebook(CLOSE_SHOW_PAGES)` on IO (a failure is logged, and the leave proceeds
regardless) and then leaves with `RESULT_OK`. **The back arrow itself calls nothing** — silence is
what sends the notebook to the library, the same rule every other leave path in this feature
follows.

**The text cover** (`TextCover`, in `:app`) renders the document's opening lines through
`:markdown` onto a fixed **600×800** canvas — constant density so covers match across devices —
regenerated at every seal after the document flush, and once at import. The library card falls
back to a centered `ic_file_text` glyph when there is nothing to render yet.

**Rename from the title**: tapping the header title (clickable only for a text document) opens the
family's standard name dialog and calls `IDocumentHost.renameNotebook`; a refusal (duplicate name)
raises an `IllegalArgumentException` whose **message is the user-readable reason** — the library's
own rename strings, verbatim — and the dialog stays open for correction rather than closing on the
failed attempt.

**`.md`/`.txt` import** rides the existing single Import entry point — no og-style import sheet is
needed, because the arc-16 picker already unions every importer's MIME filters and dispatches by
extension. `TextImporterService` streams bytes verbatim (the `:ext-soil` idiom, its own
`TextStreams`); the host forks **after** delivery on `ImporterInfo.resultKind`'s compatible tail
(`RESULT_TEXT_DOCUMENT`). `TextImport.decode` requires **strict UTF-8**
(`CodingErrorAction.REPORT`, never the JVM's lossy default) — a raw NUL byte anywhere is treated as
binary wearing a text extension and refused; a leading BOM is stripped; line endings normalize to
`\n`; the byte cap (10 MB) and `MAX_DOCUMENT_CHARS` are enforced together, deliberately aligned so
anything the importer accepts is guaranteed editable. A name clash silently dedupes
(`ImportNames.freeName`); an **empty** `.txt` is a legal import that lands as a genuinely empty text
document (a zero-byte `.soil` still refuses — the two paths are deliberately asymmetric); the
created document row's watermark is `NULL` ("authored elsewhere," never a draft); a blank body
writes **no** document row at all, consistent with blank-means-absent. There are no questions on
this path — it always creates a new text document and opens straight into the editor.

---

## Export

The full mechanics live in [`docs/export.md`](export.md); this is the summary.

`DocumentExporterService` adds a **`SOURCE_DOCUMENT`** exporter to the existing generic
`ACTION_NOTEBOOK_EXPORTER` point, offered only when the notebook actually has a document
(`hasDocument`, a blank-means-absent SQL check feeding both the chooser gate and the export
screen's Source-row visibility). One option, Markdown (`.md`) or Plain text (`.txt`). **The host
assembles the final bytes; the extension only streams them verbatim** — `ExportText.markdownOf`
is the one read both the `.md` and `.txt` paths share (the notebook document wins whole when
non-blank, otherwise page documents joined in page order using M7's own merge join, verbatim), and
a `.txt` request strips Markdown **host-side** through `MarkdownText.toPlainText` (og's own
stripper, ported into `:markdown`). This resolved a genuine contradiction in the phase's own plan
sentence, which had first suggested the extension would strip via `:markdown` — that could not
coexist with the pinned rule that the exported stream is byte-verbatim what was handed to the
extension, so the host does the stripping and the extension stays a pure copy.

**PDF-of-preview needed no change to `:ext-pdf` at all.** The Export screen grows a host-side
**Source row** (Notebook pages / Document), shown only when a document exists *and* the currently
selected exporter is `SOURCE_PAGES`. Choosing Document makes the host paginate and render the
Markdown preview itself — `MarkdownPaginator` (from `:markdown`) slicing a `StaticLayout` at
**exactly the editor Preview's own metrics** (`DocumentPdfMetrics` mirrors `EditorPrefs`'s key
layout, including the user's saved text-size preference, read from the editor's extension store
**only if that store file already exists** — an export must never mint one) — into the standard
`PageBundle` `:ext-pdf` already knows how to assemble. The render is **plain white ground,
always**; the page-template toggle is hidden in Document mode, since prose under ruled paper made
no sense once tried.

---

## Proofread

Spelling and grammar checking, **only** in the document editor — the one SN surface where the
user produces finished prose. It is a faithful, extension-local port of og's subsystem
(`docs/proofread.md` at the monorepo root is the full design reference and still applies almost
unchanged) — the M10 outcome records a **zero-line normalized diff** against og across all four
pure engine files.

- **Engine and dictionary**: SymSpellKt, module-local to `:ext-document` (approved on the same
  footing as pdfbox in `:ext-pdf`) — never leaks into another module. The bundled dictionary asset
  is a sha256-identical copy of og's VarCon-patched `en_82765.dict` (both spellings of every
  standard US/UK/CA/AU pair accepted, neither ever flagged) plus its `NOTICE.txt` — data reused
  verbatim, not code. It ships as `assets/proofread/en_82765.dict` — **never named `.gz`**, the
  standing og trap: AAPT gunzips a `.gz` asset at build time and strips the extension, so the
  runtime name would stop matching the source tree.
- **Pure port**: `ext.document.proofread/` holds `SpellEngine`, `ProofreadTokenizer`,
  `ProofreadCheck`, `GrammarRules` — og's 55 test methods plus 16 hole-fillers, and the dictionary
  rides the test classpath via a `sourceSets` mount of `src/main/assets` rather than a duplicated
  copy. Two og subtleties were checked and pinned rather than "fixed": a **zero-width flagged
  region still judges the word it sits inside** (`misspelled`'s intersection test — grammar
  early-returns on truly empty input, an asymmetry og already had); and og's `articleAgreement`
  KDoc claimed a capitalized-after-capitalized guard the *code* never actually implemented — the
  KDoc was corrected, the code left untouched (matching behavior, not matching comments, is the
  contract).
- **User dictionary lives in the extension store** — an extension writes nothing to disk itself,
  and the store is exactly the mechanism for small per-device state. `UserWords`' line-blob codec
  sits under `EditorPrefs.KEY_USER_WORDS = "dict"`; the normalized form (lowercase, folded
  apostrophe) is the storage form; removal is a hard drop; insertion order preserves og's
  `addedAt` ordering with the actual clock removed (the store carries no timestamp column).
- **The on/off toggle** lives under `EditorPrefs.KEY_PROOFREAD = "proofread"`, **absent means
  on** (matching og's default-on), and is read **asynchronously** in `start()` — the store is
  Binder I/O, so there is no synchronous constructor read, and the dictionary is never loaded at
  all while the toggle is off (a user who turned it off should not pay the load cost).
- **The suggestion index builds on a process-level scope**, not the editor's own lifecycle scope —
  `:ext-document` has no Application class, so this is a deliberate choice: a lifecycle-scoped
  build would restart the ~40 s Nomad-measured index build from zero on every editor open. og's
  words-before-engine publish ordering (the dictionary's word set is available before the
  suggestion index finishes) is preserved across the store seam.
- **Three of og's informational toasts moved to `Dialogs.problem`**, following SN's own
  toast-vs-dialog rule (a toast only confirms something that already happened); "Removed
  "word"" is the one that stayed a toast, since removing a word from the dictionary is exactly that
  kind of already-happened confirmation.
- **`ProofreadPeer`** is a second, separate automation interface implemented by the controller
  (alongside the editor's own `EditorAutomation`) — kept the controller at a written-reason size by
  extracting `watchHeight`/`watchWidth` out to `EditorTools`/`FormatBarOverflow` to make room.
- **Walk-expectation correction, worth keeping in mind for anyone testing this by hand:** the
  sentence-capital grammar rule will **not** fire on "the cat sat. the dog barked." — "sat" is
  itself in the `ABBREVIATIONS` set (it collides with the Saturday abbreviation), so the rule
  declines to judge across that boundary. This is og's own precision-over-recall behavior,
  confirmed live on-device, not a bug in the port. A hand walk that wants the rule to actually fire
  must avoid ending the prior sentence on a weekday/month abbreviation word.

No word or document text is logged on either side of this feature, matching the seam's own
never-log rule for document text generally.

---

## Entry points

The **Document** button sits on the notebook's top bar in the right-hand cluster, **before
Recents** (`… Document · Recents · Scratch Pad` — page-bound, so it is the leftmost item of the
"leave this page" cluster). Icon is Tabler `file-text` (`ic_file_text`, og's own vocabulary,
redrawn for `:app`). Like every extension-backed control in SN, it is **`GONE`, never
disabled**, when no trusted `ACTION_DOCUMENT_EDITOR` extension is discovered — a disabled control
is invisible on e-ink, and a lie about what a tap will do is worse than the button simply not
being there.

The debug-only automation hook exists for exactly one reason: Supernote swallows both
`adb shell input text` and `input keyevent` letter input, so no walk agent can type into the
editor through the normal channels. `AutomationReceiver` (`src/debug/` only, action
`….ext.document.AUTOMATION`, absent entirely from release builds) exchanges text through files in
`/data/local/tmp` and answers a broadcast-driven command set that grew across the arc: set/append/
get text, get_state, set_caret, mode switch, save, done, close, find/replace/reflow/word_count/
undo/size (M5), scope/merge/cancel (M6–M7), show_pages/rename/get_title (M8), and the separate
`ProofreadPeer` surface for fix/ignore/add-to-dictionary/status (M10). It is a genuinely separate
mechanism from the walk-visible parts of the app — nothing here is reachable outside a debug
build, and it never assigns its peer at all in release.

---

## Failure table

| Failure | What happens |
|---|---|
| Bring in tapped with no recognizer ready | `SEED_UNAVAILABLE` typed refusal; the sheet still opens, but recognition never runs |
| A drafted commit's parked watermark died before it landed | `NO_DRAFT_PENDING`; the editor downgrades — claim cleared, same text resent as an ordinary save, only provenance lost |
| Merge cancelled mid-run | `MERGE_CANCELLED`; the volatile cancel flag is checked between pages (a no-op if the merge is idle), the editor stays on the page it was on, nothing written |
| A save chunk is refused (cap exceeded, bad ordering) | The **whole save resets** — never a partial document on disk |
| `MAX_DOCUMENT_CHARS` exceeded (editor buffer or an import) | Refused at the boundary that would have exceeded it — the editor caps its own buffer save at 256k chars in saved state, and `TextImport` enforces the cap alongside the 10 MB byte cap |
| Extension store unavailable | `EditorPrefs` treats every exception from the store as "store unavailable" and degrades — a lost caret costs a scroll position, a lost dictionary entry costs one flag, never the document itself |
| No `ACTION_DOCUMENT_EDITOR` extension installed | The Document button is `GONE`; for a **text document**, `TextDocRouting` falls back to `loadCanvas` (the ordinary notebook surface) with a 10 s watchdog in case an editor never appears |
| Host process dies behind a live editor | Host-driven reconnect (`KEY_DOCUMENT_SHOWING` → `reconnect()`); see [teardown](#two-process-autosave-and-teardown) |
| Host dies *and* the editor closes before reconnect can run | The `IndexGuard` bounce eats the saved state; the parked final save is dropped on key mismatch at the next showing — corruption-safe, accepted edge |
| Rename to a duplicate name | `IllegalArgumentException` carrying the library's own rename-refusal message; the rename dialog stays open |
| Import: not valid UTF-8, or a raw NUL byte | `NOT_TEXT` — treated as binary wearing a text extension |
| Import: over the 10 MB / `MAX_DOCUMENT_CHARS` cap | `TEXT_TOO_LONG` (`TextImport.Refusal.TOO_LONG` mapped onto the import problem dialog) |
| Notebook killed with no advisory close mode recorded | Null advisory reads as **to the library** — the deliberate fail-safe direction |

---

## Deliberate divergences from og (the M11 review)

Two engine bugs found at the arc's review exist in og too; SN fixed them and now deliberately
diverges (og carries both upstream — noted in the monorepo backlog):

- **Reflow keeps a joined line's hard break.** og's join branch drops the two trailing spaces of
  a hard-break-terminated line that wraps, deleting the very break its own comment says the rule
  protects — and breaking reflow's idempotence. SN's join re-appends them.
- **Block toggles skip blank separator lines.** og stamps `1. ` / `- ` / `> ` onto the blank
  line between two selected paragraphs, minting empty list items. SN's toggle passes blanks
  through unmarked (an all-blank selection — the caret on an empty line — still takes the
  marker, which is how a list is started).

---

## What this arc deliberately did not do

Carried over unchanged from og's own exclusions, all reaffirmed at the arc-19 wizard: open-with/
share-to intents for text import (the arc-16 single Import-button entry point stands), images
beyond the source-level `![alt](url)` placeholder, and og's digits-only ordered-list rule
(lettered/roman lists — rejected in og, not re-raised here). SN adds one exclusion of its own:
Page-Index selection-merge for the notebook document — og offered a merge over a chosen page
range from its Page Index; SN has no Page Index, and the wizard's answer was that auto-merge plus
the Merge sheet cover the need well enough to not build a third path, revisitable on demand.

---

## Related

- [`docs/extensions.md`](extensions.md) — the seam: `ACTION_DOCUMENT_EDITOR`, `IDocumentEditor`,
  `IDocumentHost` (the seam's first host-side stub), the text-chunking rule, the boundary audit
  rows for the fifth point.
- [`docs/export.md`](export.md) — `SOURCE_DOCUMENT`, the Source row, `DocumentExporterService`.
- [`docs/import.md`](import.md) — the result-kind tail, `TextImporterService`, the text import
  fork.
- [`docs/notebook.md`](notebook.md) — the Document button's place in the top-bar cluster.
- `apps/notesprout_ratta/RATTA_PLAN.md` § "Phases — Arc 19 \"Document\"" — the wizard's locked
  decisions and every phase's outcome record, in full.
- og's `docs/documents.md` + `docs/proofread.md` (monorepo root) — the reading references this
  arc ported from; where this doc and those disagree, this doc (and the plan's recorded outcomes)
  describe what SN actually built.
