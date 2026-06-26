# Sticky Notes — Subsystem Reference

A **sticky note** is a first-class content object. It sits on a notebook page (or scratch pad page)
as a small icon (`sticker-2` from Tabler). Tapping the icon opens a content window — a popup on
large screens, full-screen on small devices — where the user draws and edits with pen, eraser,
lasso, and paste. The note's drawn content does **not** render on the page; only the icon does.

---

## Data model — embedded content in `data` (the link precedent)

A sticky note is a `type = "sticky_note"` row in the `.soil` `notebook` table (notebook pages) or
the `scratchpad` table (scratch pad pages). It follows the `LinkObject` pattern: the object's
**embedded content travels inside its `data` JSON** (`StickyNoteObject`), so copy/cut/paste, page
copy, full-notebook export, and clipboard persistence all carry it automatically.

### Two independent coordinate spaces

| Space | What it tracks |
|---|---|
| **Row `boundingBox`** | The icon's fixed-size rectangle **on the page** — what lasso hit-tests and moves |
| **Embedded content** | Strokes/headings/text/lines live in the **content window's own pixel space**, independent of icon position |

`contentWidth`/`contentHeight` record the canvas pixel size the content was authored at (used when
rendering on reopen or in the PDF export). Lines are stored as density-independent `EmbeddedLine`
dp values (same carrier as `LinkObject`) so a note round-trips correctly across devices of differing
display density.

`StickyNoteObject` carries **no links and no nested sticky notes** — only strokes, headings, text,
and lines.

### Key files

| File | Role |
|---|---|
| `data/StickyNoteObject.kt` | `@Serializable` `data` JSON payload (`toJson`/`fromJson`) |
| `data/StickyNoteRender.kt` | In-memory render model; `translate(dx,dy)` moves only the icon box; `toStickyNoteObject(density)` converts `LineRender` → `EmbeddedLine` for persistence |
| `data/NotebookObject.kt` | `TYPE_STICKY_NOTE = "sticky_note"` constant |
| `data/NotebookDao.kt` | `getStickyNotesForLayer(layerId)` query; `sticky_note` included in staleness query |
| `notebook/NotebookConstants.kt` | `STICKY_NOTE_ICON_SIZE_DP = 72f` |
| `res/drawable/ic_sticker_2.xml` | Tabler `sticker-2` VectorDrawable (24dp, strokeWidth=1.5, inkBlack) |

---

## On-page icon rendering

Both drawing engines (`OnyxNotebookView`, `GenericNotebookView`) maintain a
`stickyNotes: List<StickyNoteRender>` in-memory list and expose:

- `loadStickyNotes(stickyNotes)` — replaces list and redraws
- `getStickyNotes()` — returns current list
- `compositeStickyNotes(bitmap)` — composites icons onto an existing snapshot bitmap

The icon is drawn via `AppCompatResources.getDrawable(context, R.drawable.ic_sticker_2)`, bounded to
the icon box, in `redrawCanvas`, `buildRenderBitmap`, and the snapshot-composite paths — **after the
links loop, before the top-level strokes loop**.

Embedded content is **never drawn on the page** — it lives only inside the editor.

---

## Tap-to-open

In `NotebookActivity`, a **finger single-tap** hit-tests sticky notes first (before link follow).
The hit-test uses `stickyNoteAt(x, y)` — topmost icon bbox containing the point. On hit:
`openStickyNote(note, initialCreate = false)` launches `StickyNoteEditorActivity`. Stylus taps do
not open (only finger taps, below the long-press threshold, single pointer, no move — identical
thresholds to link follow). In `ScratchpadActivity`, finger tap-to-open uses the same approach
inside `dispatchTouchEvent`.

---

## Content window — transfer-singleton model

The content window is `StickyNoteEditorActivity`. It **never opens the `.soil`** — the host Activity
already holds that connection open and the project forbids cross-Activity `.soil` access. Content is
handed in and out through `StickyNoteEditorTransfer` (an in-memory singleton, same pattern as
`ScratchpadTransfer`):

```
host.openStickyNote(note):
    StickyNoteEditorTransfer.input  = note's embedded content (+ contentW/H)
    launch StickyNoteEditorActivity (editorLauncher)

StickyNoteEditorActivity:
    load input into drawing view's in-memory lists; edit fully in memory
    pen / eraser / lasso-eraser / scribble / paste from NotesproutClipboard
    on close (onPause): StickyNoteEditorTransfer.output = current in-memory content; finish()

host editorLauncher callback:
    read StickyNoteEditorTransfer.output; if changed → persist new data JSON to .soil row,
    update in-memory StickyNoteRender, rebuild page bitmap,
    push StickyNoteContentEdited(before, after). Clear the transfer.
```

The editor is **fully in-memory** — it owns no DB/repository and persists nothing itself. The host
owns persistence.

### Window sizing

- **Large screens** (`is_large_screen=true`, ≥600dp): constrained to 75% × 75% of display, centered.
  Translucent `Theme.Notesprout.Scratchpad` (tap outside = `finish()`).
- **Small screens**: `match_parent`, reads as a dedicated screen.

Engine is chosen via `isBooxDevice()` (same as the scratch pad).

---

## Create-flow

`insertStickyNote()` in `NotebookActivity` / `ScratchpadActivity`:

1. Computes a square icon box (`STICKY_NOTE_ICON_SIZE_DP × density`) centered on the canvas,
   clamped to page bounds.
2. Inserts a `type = "sticky_note"` row with empty `StickyNoteObject().toJson()` and icon bbox.
   Invalidates the page snapshot.
3. Pushes a `StickyNoteInserted` undo action.
4. **Immediately opens the editor** (`openStickyNote(render, initialCreate = true)`) so the user
   draws before placing.
5. On editor close (initial create): enters lasso mode and auto-selects the icon so it is immediately
   draggable for placement.

For a tap-to-open (non-create): persists content and rebuilds the bitmap, but does **not** change
selection or enter lasso mode.

---

## Lasso and undo parity

Sticky notes are full lasso citizens, mirroring link objects in every callback:

| Action | Behaviour |
|---|---|
| Lasso-select | Region-intersects icon bbox; selected notes appear in `selectedObjectIds`; floating toolbar shows |
| Drag-move | Icon box translated; persisted in `onStrokesMoved`; `StrokesMoved` undo action carries before/after |
| Delete (floating toolbar) | Soft-delete row; `LassoDeleted` carries `stickyNoteIds`/`stickyNotes` |
| Cut | Soft-delete + write to `NotesproutClipboard`; `LassoCut` carries them |
| Copy | Add to `NotesproutClipboard` with fresh UUIDs; `LassoPasted` carries paste result |
| Lasso-eraser | Hit-test icon bbox center; soft-delete; `onStickyNoteErased` → `LassoErased` |
| Scribble-erase | Penetration hit-test; included in `onScribbleEraseComplete` payload; `ScribbleErased` |
| Hardware eraser (barrel) | `onStickyNoteErased` → soft-delete |
| Erase-all (page clear) | Wipe in-memory list + all rows; `PageEraseAll` records them for restore |

**Undo actions** added in `history/UndoRedoAction.kt`:

| Action | When |
|---|---|
| `StickyNoteInserted` | After `insertStickyNote()` |
| `StickyNoteContentEdited` | After editor close with changed content (one action per session, before/after full content snapshot) |
| Extended fields on `StrokesMoved`, `LassoDeleted`, `LassoErased`, `ScribbleErased`, `PageEraseAll` | Carry `stickyNoteIds`/`stickyNotes` lists |

**Rule:** after any undo/redo that touches sticky notes, re-read via `loadStickyNotesFromDb` +
`loadStickyNotes` to keep the in-memory list in sync (identical to the links rule).

---

## Scratch pad parity

Sticky notes work on scratch pad pages (stored in the `scratchpad` table in `notesprout.db`). The
scratch pad has **no undo stack** — consistent with all scratch pad objects.

`ScratchpadActivity` mirrors `NotebookActivity` for all sticky-note operations:
- `btnScratchStickyNote` (toolbar, `ic_sticker_2`) → `insertStickyNote()` → opens editor immediately.
- `editorLauncher` + `openStickyNote(...)` using the same `StickyNoteEditorTransfer` singleton.
- `stickyNoteAt()` + finger tap-to-open.
- Lasso callbacks (`onLassoComplete`, `onStrokesMoved`, `onScribbleEraseComplete`,
  `onStickyNoteErased`, `performLassoCopy/Cut/Delete/Paste`, `selectInsertedObjects`) all thread
  sticky notes through, exactly as links do.

**Clipboard is shared:** a sticky note copied on the scratch pad can be pasted in a notebook and vice-versa.

---

## PDF export — footnote/endnote (pdfbox post-process)

`android.graphics.pdf.PdfDocument` is Canvas-only and cannot emit annotations. `pdfbox-android`
(`com.tom-roush:pdfbox-android:2.0.27.0`, already a dependency) can. The export uses a two-pass
approach:

### Part A — on-page icon (Canvas pass)

`NotebookExporter` loads `getStickyNotesForLayer` per page, parses each into a `StickyNoteRender`,
and draws the `ic_sticker_2` icon at the icon `boundingBox` **after the links loop, before strokes**
— matching the on-screen render order. Embedded content is **not drawn** on the page (hidden, as on
screen). Each rendered icon is collected into a `StickyExport(pdfPageIndex, iconBox, note, pageW, pageH)`.

### Part B — pdfbox post-process

`addStickyEndnotes(input, output, stickyExports, context)` runs after the Canvas pass:

1. `PDDocument.load(input)`.
2. For each `StickyExport` (1-based endnote number N):
   - Renders the note's embedded content to a bitmap (at `contentWidth × contentHeight` px, white
     background, no template) → PNG/JPEG bytes → `PDImageXObject`.
   - Appends an endnote `PDPage` sized to the content bitmap. Draws the image + a small caption
     ("Note N — from page P") with a back-link region.
   - Adds a `PDAnnotationLink` on the source page (over the icon rect) → `PDActionGoTo` →
     `PDPageFitDestination` at the endnote page.
   - Adds a `PDAnnotationLink` on the endnote page (over the caption) → `PDActionGoTo` →
     `PDPageFitDestination` at the source page.
   - Link borders set to width 0 (invisible, e-ink).
   - **Coordinate flip:** pdfbox uses points, bottom-left origin; source boxes are pixels, top-left.
     `lly = pageH − box.bottom`, `ury = pageH − box.top` (1px ≈ 1pt because pages are built at
     bitmap-pixel size).
3. `doc.save(output)`; `doc.close()`.

### Wire-up in both PDF paths

| Path | Order |
|---|---|
| Plaintext PDF | `PdfDocument` → tmpA; if sticky notes: `addStickyEndnotes(tmpA, out)`; else move tmpA → out |
| Encrypted PDF | `PdfDocument` → tmpA; `addStickyEndnotes(tmpA, tmpB)`; `encryptPdfFile(tmpB, out, password)` — annotate **before** encrypt |

PNG export paths get the on-page icon only (Part A); endnotes and link annotations are PDF-only.
When no sticky notes are present, the export is byte-identical to the pre-sticky-note behavior.

---

## Export / import

Full-notebook export (`NotebookPackager`) copies the raw `.soil` file — `sticky_note` rows travel
with it verbatim and need no special handling. Import (`NotebookImporter`) places the `.soil` as-is
in Garden and re-registers it; no object-type allow-list exists. Sticky notes survive round-trip
export → import with all content intact.

---

## Encryption note

Sticky note content lives in the `.soil` row's `data` column, encrypted with the notebook key
(SQLCipher) when the notebook is encrypted. No special handling is required — the existing
`SoilCrypto` / `KeyResolver` path covers the row automatically. Copy-out to the shared clipboard
from an encrypted notebook uses the existing `awaitEncryptionClipboardConfirm()` warning (same as
any other object type).

---

## Toolbar integration

- Notebook toolbar: `btnInsertStickyNote` (`id/btnInsertStickyNote`), toolbar registry key
  `"stickyNote"` (last in `SPECS`, visible by default, hideable via Customize Toolbar).
- Scratch pad toolbar: `btnScratchStickyNote` (`id/btnScratchStickyNote`).
- Registry: `ToolbarButtonRegistry.kt` `SPECS`, `ButtonSpec("stickyNote", …, GROUP_TOOLS)`.

---

## Deferred items

- **Cross-size content scaling:** when the editor window differs from `contentWidth/Height` (rotation,
  cross-device paste), proportionally rescale embedded content instead of rendering as-authored + clipping.
- **In-editor autosave / process-death durability:** the editor holds content in memory until close;
  persist incrementally (or on `onPause`) so an OS kill mid-edit doesn't lose strokes.
- **Multi-page sticky notes** (D1 chose single page).
- **Native text/line insertion inside the editor** (D2 chose pen/eraser/lasso + paste only).
- **Content affordance on the icon** (e.g. "has content" mark or mini-preview — currently one static icon).
- **Sticky-note content in search / TOC** — content is hidden and excluded from ML Kit search and
  page-name/TOC rules (intentional for v1).
- **Live undo inside the editor** (D3: one before/after action per window session only).
- **Endnote pagination / fit:** S6 renders each note's content onto a single endnote page sized to the
  content; content larger than one page is not split across multiple endnote pages (acceptable for v1).
