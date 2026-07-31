# Documents — the page's authored text

**The notebook page is the draft; the document is the result.** A page's handwriting flows into a
document once, as a starting text, and from then on the document belongs to the user — nothing on the
page overwrites it again unless asked. It is the step before export: where a handwritten page becomes
a finished piece of writing.

One document per page, stored inside the `.soil`, so it is encrypted at rest, travels on
export/import, and rides along with backup for free.

Surface: `DocumentEditorActivity` — full-screen Markdown editor, `Write` / `Preview`, format bar and
matching Ctrl shortcuts (`MarkdownFormatter`), opened from the notebook toolbar's **Document** button.
Page flips (`‹ ›`) and text size (`A`) sit with the page label in the header; **Reflow** and **Bring in**
sit in the source strip below it.

Chrome layout, top to bottom: header (notebook name · `‹ 4 / 12 ›` · text-size · Write / Preview /
Done) — rule — source strip — **rule** — format bar — overflow panel — rule — the text.

The title names the **notebook**: the screen is evidently a document, so the word would only take room
from the one piece of context the editor cannot otherwise give you. The page count sits *between* its
two arrows — the number belongs to the control that changes it, and the three read as one unit. The
strip, its rule, the format bar and the panel are one view (`writingChrome`) shown and hidden together,
so Preview cannot leave a stray divider or an open menu behind.

**The format bar overflows rather than scrolling.** The full palette is ~730dp of tools and a 6" screen
is `sw571dp`, so a scrolling bar hid its last few tools with nothing to say they existed. Reuses the
notebook's `ToolbarOverflowManager` unchanged — it takes four views and reads the bar's own children, so
it never knew anything about NotebookActivity (see [`toolbar.md`](toolbar.md) for the two contracts a
caller must honour). Because it *moves* the real views, their click handlers and long-press hints come
along untouched. What stays on the bar is stable for a given screen, so muscle memory holds; only the
tail moves, and it moves to one place.

Three details: the panel is **in-flow**, so it pushes the text rather than covering the line being
written (the notebook's floats because a canvas must not be pushed); a tap outside dismisses it but is
**not consumed**, since that touch is the writer choosing where to type; and the dots button is the one
control that does not auto-dismiss, or it would close and immediately re-open itself.

**Tools are icons; named actions keep their words.** Every tool — the format bar, the page flips, text
size — is a 24dp Tabler outline glyph in `inkBlack` at stroke width 2, the same set and weight as the
notebook's own toolbar, which is what makes the two screens read as one app. Write / Preview / Done and
the strip's Reflow / Bring in stay as words: they are modes and named actions, not tools, and the app's
other view switches (`PageTextViewerActivity`, `TextEditDialog`) are worded the same way. Every icon
button is built by one factory (`iconButton`) so hit area, background and the long-press that names the
tool cannot drift apart; the long-press string doubles as the content description.

**The editor opens where you left off.** `DocumentPreferences` remembers the caret per page (a bounded
LRU of 100, oldest evicted) and the `Session` carries it in. Deliberately **device-local rather than in
the `.soil`**: where a caret sits is this device's view state, not part of the document, and putting it
in the file would mean a column in a format written to be handed to other projects. The cost is that it
does not travel with an exported notebook — the right trade for something the next keystroke overwrites.
Failing that, the caret starts at the **top**, not the end: a document is usually read before it is added
to, and landing at the bottom hides everything that was written. The caret is handed over on every save
even when the words are unchanged, or browsing a page without typing would lose the place.

**Images** are a source-level placeholder — `![description](url)`, the mirror of the link button, with
no picker behind it. `MarkdownParser` understands the syntax so Preview shows the alt text in italic, the
way a caption reads; without that the `!` would be left as literal text and the rest parsed as a link, so
an image reference came out as "!alt", underlined and pretending to be one. The renderer still draws no
images (out of scope — see [`content-objects.md`](content-objects.md)).

**Text size** is a saved global preference (`DocumentPreferences`, five steps from Small to Largest),
not a per-document one — it is about the user's eyes and their device. Preview renders
`PREVIEW_BUMP` larger than the editing surface: source is monospace Markdown where columns carry
meaning, preview is prose meant to be read. Changing it re-renders Preview rather than just re-measuring,
because `MarkdownRenderer` bakes sizes into spans from the paint it is handed.

The button lives in the header, not in the writing chrome, so it is still reachable in Preview — reading
size matters at least as much as writing size. Below 400dp `smallestScreenWidthDp` the title drops the
word "Document" and keeps the page label, which is the part that says where you are.

### Lists continue themselves

Enter inside a list item writes the next marker, so a series keeps going by itself: the same bullet
character, the next number, or a fresh **unchecked** box (a finished task yields an unfinished one — the
next thing you write is not already done). Indentation carries over, so a nested list stays nested.

A second Enter finds an item with nothing in it, deletes that marker — indentation included, or the new
paragraph would start indented — and leaves a clean blank line. Two Enters are a paragraph break with no
stray dash, number or checkbox left behind:

```
- milk          →  - milk          →  - milk
                   - |                              ← marker gone
                                      |             ← plain line, ready to type
```

Two details worth keeping:

- **It is decided by the state of the line, not by how fast the keys were pressed.** "Double Enter"
  therefore works at any speed and cannot leave a rogue marker behind, which a timing window could.
- **Splitting an item mid-way carries on instead of ending**, so text that moves down keeps its place in
  the list rather than losing its marker.

**Ordered lists renumber themselves.** Markdown numbers a list itself — it takes the first item's number
and counts from there, ignoring what the later items claim — so an item added in the middle renders
correctly while the source reads `1. 2. 2. 3.`. The editor then looks wrong about a document that is
fine. `MarkdownFormatter.renumberOrderedLists` returns the marker rewrites that make the source agree,
and because it writes exactly what Markdown would render, **it can never change the rendered output**.
That is what makes it safe to run automatically: after every list Enter, and as part of Reflow (which
also closes the gaps left by a deleted item — and runs document-wide even for a selection, being
rendering-neutral).

A list that starts at 3 keeps starting at 3. Runs are tracked per indent width, so a nested list counts
separately from its parent; a run survives a single blank line (a loose list is still one list) and ends
at two. Left alone: bullets and tasks, fenced code, and a deeply indented run with no list above it —
that is an indented code block, not a nested list.

Rewrites are returned rather than applied so the caller can make narrow edits, each confined to a marker:
an untouched list costs no edit at all, and the caret is carried by the length change of everything
ending before it rather than being shuffled out of the words it was in.

The decisions are `MarkdownFormatter.listEnter(before, after)` and `renumberOrderedLists(text)` — both
pure, and covered by JVM tests alongside the format-bar operations. **`TextEditDialog` (on-page text
objects) calls the same two functions**, so the app's two writing surfaces cannot drift apart; it
previously had its own ordered-only implementation that always renumbered from 1.

> **Digits only — no lettered or roman lists.** `a.` / `i.` ordered lists exist only in Pandoc's
> `fancy_lists` extension, not in CommonMark or GFM, where they are paragraphs — and consecutive lines
> get *joined* into one. Supporting them in this app's own renderer would mean documents that read as
> lists here and as a run-together paragraph in GitHub or Obsidian, which is the opposite of what a
> pre-export surface is for. Decided 2026-07-29; revisit only if the export target becomes Pandoc. The hook is the editor's **text watcher**, not the Enter key: a soft keyboard
commits `"\n"` through the input connection and may send no key event at all, so watching the text is what
makes this work on both keyboards. It edits from `afterTextChanged`, the one callback where the buffer may
be changed, and neither edit it makes is a lone newline — so it cannot re-enter itself.

**The software keyboard shrinks the layout** rather than covering it:
`TopGuard.applyInsetPadding(root, followIme = true)` pads the root by the IME inset, and
`keepCaretVisible` nudges the caret's line back into view when the surface's height changes. With a
physical keyboard the editor refuses an input connection at all, so the inset stays 0 and the surface
keeps the full screen. See [`design-system.md`](design-system.md) for why the manifest's `adjustResize`
is not sufficient on its own.

---

## Four invariants

1. **The only writer of a `document` row is the editor** (plus the page copy helpers). No recognition
   path ever writes one.
2. **Seed once, then never automatically.** The page's text flows in only when the document holds
   nothing — `DocumentDraft.isUndrafted`.
3. **The `.soil` is written only through `NotebookActivity`'s connection.** The editor never opens the
   file.
4. **A document travels with its page; `page_text` does not.** The cache regenerates; a document is
   the user's writing.

---

## Data model

`type = "document"`, `parentId = pageId`, one row per page, fully columnar — no `data` JSON.

| Field | Column | Notes |
|---|---|---|
| markdown | `text` | The document itself |
| source watermark | `srcUpdatedAt` | `getMaxContentUpdatedAt(layerId)` at the last seed/refresh. NULL = authored by hand, never drafted from the page |

`srcUpdatedAt` is a `.soil` **v5** column (`SoilSchema.ADDED_COLUMNS_V5`, `SoilDatabase.MIGRATION_4_5`)
— additive and nullable, no row rewritten. It needed a column of its own because the table had no
spare 64-bit slot; every other free column is `REAL` or a Kotlin `Int`, and epoch milliseconds fit in
neither.

**Blank means absent.** A document with no text is never inserted, which is what lets "seed once" work
without a separate "has been seeded" flag: no text ⇒ undrafted ⇒ offer the page's text again.

`document` is deliberately **excluded** from `getMaxContentUpdatedAt` and `countContentModifiedSince`
(both type whitelists). A document is a product of the page, not content on it; including it would
invalidate the page's own snapshot and mark the notebook edited.

### Why not `page_text`

`page_text` looks like the natural home and is the wrong one. It is a derived cache with three
writers — `RtrScheduler`, `NotebookTextExporter`, and the viewer's recognize-on-open — so anything
authored there is clobbered by the next RTR pass. It also carries per-line `strokeIds` for the
viewer's tap-to-correct flow, which a document has no use for.

Keeping the two apart also meant RTR needed no changes at all: `page_text` stays exactly what it was.
What documents reuse is the *pipeline*, not the row — `PageTextRepository.freshOrRecognize()` produces
the seed draft.

---

## Reading and writing — why the editor has no DB

`SoilDatabase` is one-instance-per-open-notebook by design, and a second **writing** connection to a
file another activity holds open is the shape of this project's worst data-loss bugs (see
[`encryption.md`](encryption.md)). The one child screen that does open its own connection,
`PageIndexActivity`, is read-only for exactly this reason.

So the editor holds text and nothing else. `DocumentTransfer` carries the hand-off and the host's two
capabilities:

```kotlin
interface Host {
    fun saveDocument(text: String)                              // NotebookActivity writes it
    fun requestPageDraft(onResult: (Draft?) -> Unit)            // recognize the page, main-thread callback
}
```

Both host calls are keyed on `NotebookActivity.documentPageId`, **never** `currentPageId` — a
recreated host may have moved on.

### Autosave, and the three ways text could have been lost

There is no Cancel: writing on paper is not cancellable. Text is written on a 2 s idle debounce, on the
`Write`↔`Preview` switch, on `onPause`, and on **Done**.

| Failure | What closes it |
|---|---|
| Host destroyed under the editor (low-memory kill), taking the DB with it | The editor republishes `DocumentTransfer.live` on every save; `sealNotebook` calls `flushPendingDocument(db)` **before** the connection closes |
| Process death | `NotebookActivity.onSaveInstanceState` keeps `documentPageId` + watermark (next to `pendingStickyNote`); the recreated host reinstalls itself as host, and `documentLauncher` flushes `live` when the editor closes |
| Editor recreation | The editor's views are built in code with no ids, so `EditText` state is **not** restored for us — it saves its buffer explicitly and prefers it over the hand-off |

`savedText` in the editor means only "this exact text has been handed to the host". Whatever the editor
opens with — a seed, or a buffer recovered from process death — counts as unsaved, because neither has
ever been written. `DocumentRepository.save` drops a write that would change nothing, so the redundant
first save costs a read.

---

## Seeding

Handled by the host, in `openDocumentEditor`, **before** the editor launches — which is also why a
seed never lands in the DB, only in the editor's buffer:

1. Flush ink (`saveStrokes`) — the draft is recognized from the `.soil`, so unsaved strokes would be
   missing from it.
2. `DocumentRepository.get(pageId)`. Non-blank text ⇒ hand it over, launch instantly, no recognition.
3. Otherwise `PageTextRepository.freshOrRecognize(...)` behind a non-cancelable "Reading this page…"
   dialog. On a notebook without RTR this is a full page recognition and is not instant.
4. No recognizer ready, or a page with nothing to give ⇒ open empty, write no row. The page stays
   eligible to be seeded on a later visit.

The seeded text becomes real the moment the editor stores it (on pause, mode switch, or Done) — so
opening the editor once on a written page *is* the act of drafting it.

---

## Bringing the page's text back in

The source strip — one line under the header, hidden in Preview — carries provenance and is the only
route by which the page can overwrite the document:

```
 Page has changed since this draft   [Reflow] [Bring in]
```

*Reflow* sits to the **left** of *Bring in* so *Bring in* keeps its position under the hand.

The line reads "Page has changed since this draft" when `DocumentDraft.isStale(srcUpdatedAt, layerMax)`
(computed by the host at launch, and again after each refresh — no polling), "Drafted from this page"
when it is current, and "Not drafted from this page" when the text was authored by hand. It lives here
rather than in the header because the header is already `Write | Preview | Done` and would crowd off a
narrow screen.

`Bring in` opens an `ActionSheetDialog` with two choices, because both situations are real:

- **Replace this document** — the edits were a false start.
- **Add below the current text** — more ink was written after the editing. Joined under a `---` rule
  by `DocumentDraft.append`, which adds the rule only when there is something to join to.

The choice is made **before** recognition runs, so there is no "cancel after waiting" state and no
watermark to un-stamp. Both choices then re-anchor `srcUpdatedAt` to the state just recognized — the
only two places it ever moves are here and the seed, which is what makes "page has changed"
meaningful.

Both are applied through the buffer's `Editable`, the same route the format bar takes, so the editor's
own Ctrl+Z takes a refresh back within the session. Beyond the session, the confirmation dialog is the
guard: nothing durable is kept.

---

## Reflow

Recognition emits **one line per line the user wrote** — all it can honestly know — so prose arrives
broken at every hand-wrapped line. **Reflow** (`MarkdownReflow`, `Ctrl+Shift+F`) repairs that: single
breaks inside a block become spaces, blank lines stay the paragraph breaks they already are, and runs of
blank lines collapse to one.

It is deliberately conservative — a break is only removed where it is *certain* to be a wrap:

| Construct | Behaviour |
|---|---|
| Headings, rules, table rows, indented lines | Stand alone — absorb nothing, join to nothing |
| List items, blockquotes | Start their own line, but **absorb** a following plain line (the wrapped-item case) |
| Fenced code | Passed through untouched; inside a fence every break is content |
| Hard break (line ending in 2+ spaces) | Honoured, and the trailing spaces are preserved |

Scope follows the selection: a selection is grown to whole lines and reflowed, otherwise the whole
document is. No confirmation — it is a formatting operation, like the format bar, and applied through the
`Editable` so Ctrl+Z takes it back. "Nothing to reflow" when the text is already settled, which is why
`reflow` returns settled input byte-identically.

Covered by 15 JVM tests in `MarkdownReflowTest`, including idempotency.

---

## Page flips

The editor can walk the notebook: `‹ ›` in the header (or `Ctrl+PgUp`/`Ctrl+PgDn`) move to the
neighbouring page's document, in both Write and Preview.

The order matters and is load-bearing:

1. The editor **stores its text first** — `saveDocument` captures `documentPageId` synchronously, so the
   write lands on the page being left.
2. `Host.requestPage` then switches `documentPageId`/`documentPageIndex` **and clears
   `DocumentTransfer.live`**, so a teardown in the gap cannot write the outgoing page's text onto the
   incoming one.
3. The new page's `Session` is loaded through the same `loadDocumentSession` the open path uses — so a
   flip seeds an undrafted page exactly like opening it does, progress shown in the strip rather than a
   dialog (the host is stopped; see below).
4. `applySession` stores the new page's seed immediately, keeping the in-memory-only window as short on a
   flip as it is on open.

**The notebook catches up when the editor closes**, not during. Navigation drives the drawing surface,
and the host is *stopped* behind the editor — the EPD rules forbid touching it there (see
[`drawing-engine.md`](drawing-engine.md)). So `documentLauncher` calls `navigateToPage(endedOn)` on
return, which is the same deferred-navigation pattern the calendar hand-off already uses. For the same
reason the seed's "Reading this page…" dialog is suppressed on any host-is-stopped path: a dialog on a
stopped activity's window is invisible at best and a bad-token crash at worst.

`applySession` is the one place text is installed with `setText` rather than an `Editable` edit —
arriving at another page is a new document, not an edit to this one, and it must not sit on the undo
stack. Undoing "the flip" would otherwise drop the page you left into the page you arrived at, and the
next autosave would store it there.

At the first or last page the arrows stay visible and say "First page." / "Last page." — a disabled
button is visually silent on e-ink (see [`design-system.md`](design-system.md)).

---

## Consumers and page lifecycle

**Export prefers the document.** `NotebookTextExporter.writeFile` — the single funnel behind both
`export()` and `exportFromPath()`, so `ExportActivity`/`ExportEngine`, MainActivity and PageIndexActivity
all inherit it — uses the page's document when non-blank and falls back to recognized text otherwise.
Pages with a document skip recognition entirely, so export gets faster as documents accumulate.
`Format.PLAIN` strips Markdown after assembly, so documents get that for free.

**Copy carries the document by name.** `PageCopier` deep-copies the *layer* subtree, so a
page-parented row is invisible to it — harmless for a cache, data loss for a document. All three copy
paths handle it explicitly:

| Path | How |
|---|---|
| `copyPageAfter` (in-notebook duplicate) | `DocumentRepository.copyToPage` inside the existing transaction |
| `copyPagesRelativeRaw` (raw same-file) | `readPageDocument` at capture, `writePageDocument` at insert |
| `copyPagesAcrossNotebooks` (copy **and** move) | Same, captured before the source DB closes |

**Delete and undo need nothing.** Every delete path soft-deletes the page's children by parentId
(`softDeleteByParentId(pageId)`, or `WHERE parentId = ?` in the raw paths) and undo restores them the
same way (`restoreChildrenDeletedSince`), so a page-parented document is carried by both already.
`getDocumentRow` filters on `deletedAt IS NULL` to match.

Encryption, full-notebook export/import, and backup need no changes at all — the row lives inside the
`.soil`.

---

## Not built

- **Notebook-level or multi-page documents.** Per-page storage composes into one later (export already
  concatenates pages) with no storage change.
- **Documents on Scratch Pad / Calendar day pages.** Those pages live in the global index's
  `scratchpad` / `calendar` tables, which would each need `srcUpdatedAt` added.
- **Sticky-note text in the seed.** `PageTextRepository.loadPageContent` reads content nested inside
  *links*, and the ink of a heading/text whose recognition failed, but not sticky notes — they are
  collapsed to an icon, and their children live in the note's local coordinate space. Changing that
  belongs to recognition, not here (see [`handwriting-recognition.md`](handwriting-recognition.md)).
- **Editing a document from outside an open notebook** (Page Index, MainActivity). Invariant 3 makes
  the notebook the only safe host.
- **PDF/PNG export of a document.** Text formats only.
