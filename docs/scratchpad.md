# Scratch Pad — Subsystem Reference

A single global scratch pad for quick jots, accessible from MainActivity and any notebook. Content
persists across app restarts. Two-way transfer to/from notebook pages reuses the clipboard model.

---

## Data model — `scratchpad` table in `notesprout.db`

The global index (`notesprout.db`) holds a `scratchpad` table added in Room migration 1 → 2.
The schema mirrors the `.soil` `notebook` table so the same object payloads and serializers work
without modification.

```sql
CREATE TABLE scratchpad (
    id          TEXT    NOT NULL PRIMARY KEY,
    parentId    TEXT    NOT NULL,
    type        TEXT    NOT NULL,
    boundingBox TEXT    NOT NULL,
    "order"     INTEGER NOT NULL DEFAULT 0,
    createdAt   INTEGER NOT NULL,
    updatedAt   INTEGER NOT NULL,
    deletedAt   INTEGER,
    data        TEXT    NOT NULL
);
CREATE INDEX idx_scratchpad_parent_order ON scratchpad(parentId, "order", deletedAt);
```

### Row hierarchy

```
scratchpad_root  (type="scratchpad_root", parentId="", fixed id SCRATCHPAD_ROOT_ID)
  └── page       (type="page",  parentId=SCRATCHPAD_ROOT_ID, data=PageData{width,height,template=""})
        └── layer (type="layer", parentId=pageId,  data={label,isLocked,isVisible})
              └── stroke / heading / text / line / link  (parentId=layerId)
```

- **Root id:** `SCRATCHPAD_ROOT_ID = "00000000-0000-0000-0000-736372746368"` ("scrtch" in hex),
  defined in `data/index/ListIds.kt`. Created once by `ScratchpadRepository.ensureBootstrap()`.
- `PageData.template` is always `""` (no template on the scratch pad).
- Soft deletes only — `deletedAt` set, rows never physically removed. Stable UUIDs throughout.
- Content is **always plaintext** — `notesprout.db` is never encrypted.

### Key files

| File | Role |
|---|---|
| `data/index/ScratchpadEntity.kt` | Room `@Entity` for the `scratchpad` table |
| `data/index/ScratchpadDao.kt` | CRUD queries (insert, select by type/parent, soft-delete, update) |
| `data/ScratchpadRepository.kt` | Higher-level API (`ensureBootstrap`, `loadPage`, `saveStrokes`, `insertObjects`, `addPage`, `deletePage`, `setPageSize`) |
| `data/index/ListIds.kt` | `SCRATCHPAD_ROOT_ID` constant |
| `data/index/NotesproutDatabase.kt` | `version=2`; `ScratchpadEntity` in `@Database entities`; `MIGRATION_1_2` |

---

## Host window and theme

**`ScratchpadActivity`** (`Theme.Notesprout.Scratchpad`) is a translucent floating Activity:

- `windowIsTranslucent=true`, `windowBackground=transparent`, `backgroundDimEnabled=false`,
  `windowAnimationStyle=@null`.
- **Large screens** (`res/values-sw600dp/bools.xml`, `is_large_screen=true`): the bordered window is
  constrained to 75% × 75% of display metrics, centered, in `onCreate`.
- **Small screens**: `match_parent` so it fills the display and reads as a dedicated screen.
- A 1dp `inkBlack` border (`shape_bordered`) is always visible.
- Tapping outside `scratchpadWindow` calls `finish()`; tapping inside is consumed silently.

**Layout:** `res/layout/activity_scratchpad.xml`

```
FrameLayout (transparent root)
  └── scratchpadWindow (LinearLayout, shape_bordered, paperWhite)
        ├── chromeBar (LinearLayout, 56dp)
        │     title "Scratch Pad" · spacer · btnScratchpadPrev · tvScratchpadPageIndicator · btnScratchpadNext
        ├── 1dp divider
        ├── drawingContainer (FrameLayout — drawing view added programmatically)
        │     └── floatingSelectionToolbar (gone by default)
        ├── 1dp divider
        └── scratchpadToolbar (LinearLayout, 56dp)
              btnScratchPen · btnScratchEraser · btnScratchLasso · btnScratchAddPage · btnScratchDeletePage
              spacer · btnSendToNotebook (GONE unless launched-from-notebook)
```

---

## Canvas reuse

`ScratchpadActivity` picks the drawing engine with the shared helper in `core/Device.kt`:

```kotlin
drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
binding.drawingContainer.addView(drawingView.asView(), MATCH_PARENT × MATCH_PARENT)
```

**Load path** (mirrors `NotebookActivity.displayPage`):

1. `repository.loadPage(pageId, density)` on `Dispatchers.IO` → `ScratchpadPageContent`
2. `drawingView.buildRenderBitmap(strokes, null, headings, texts, lines, links)` on `Dispatchers.IO`
3. Main thread: `loadHeadings`, `loadTextObjects`, `loadLineObjects`, `loadLinks`,
   then `loadStrokesWithBitmap` (or `loadStrokes` if bitmap is null)

**Save path** (`onPenLifted` → `saveStrokes()`):

- Tracks `persistedStrokeIds` to avoid re-inserting.
- `repository.saveStrokes(layerId, newStrokes)` does insert-or-ignore in a transaction on `Dispatchers.IO`.
- Also called in `onPause` and before every page navigation.

**Snapshot:** `onSnapshotReady` persists the bitmap snapshot string into the page row's `PageData`
via `ScratchpadDao.updateData`.

**Page size:** on first layout (`drawingContainer.doOnLayout`) if `PageData.width == 0`, calls
`repository.setPageSize(pageId, w, h)` to record the real canvas pixel dimensions.

**Tool state** is restored from and persisted to `ToolPreferencesManager` (shared with notebooks).

---

## Multi-page navigation

Page state tracked in `ScratchpadActivity`: `pages`, `currentPageIndex`, `currentPageId`, `currentLayerId`.

### Navigation (two-phase, same contract as notebook)

1. `saveStrokes()` on `Dispatchers.IO` for the leaving page.
2. Clear lasso selection if active.
3. Update index + prefs + IDs, `drawingView.eraseAll()`, `loadCurrentPage()`.

### Swipe gesture

One-finger horizontal swipe in `dispatchTouchEvent` (stylus events ignored):

- Guards: `absDx > absDy`, distance ≥ 30% canvas width, AND (velocity ≥ fling threshold OR
  distance ≥ 50% canvas width).
- Right swipe → previous page; left swipe → next page.
- Left swipe past the last page → `addPage()` (inserts a new blank page and navigates to it).

### Chrome arrows + indicator

`btnScratchpadPrev` / `btnScratchpadNext` call `navigateTo`. `tvScratchpadPageIndicator` shows
`"{n+1} / {total}"`. Arrow buttons are no-ops (not hidden) at boundaries — disabled is invisible
on e-ink.

### Add / delete page

- **Add:** `repository.addPage(afterIndex = currentPageIndex)` inserts a blank page + layer after
  current; navigates to it.
- **Delete:** `AlertDialog` confirmation first (e-ink dialog rules: `setElevation(0f)` +
  `setBackgroundDrawableResource(R.drawable.shape_bordered)` after `show()`). On confirm:
  `repository.deletePage(pageId)`. If last page, clears content only (never zero pages).

### Current-page persistence

`ScratchpadPreferences` (`SharedPreferences("notesprout_scratchpad_prefs")`, key
`current_page_index`) is updated on every navigation and in `onPause`.

---

## Lasso

Lasso, smart-lasso, and scribble-erase all work on the scratch pad, reusing the notebook's
`LassoGeometry` hit-test and the shared `NotesproutClipboard` / `ClipboardStore`.

| Action | What happens |
|---|---|
| Lasso button | Toggle `isLassoMode` → `drawingView.setLassoMode(true/false)` |
| Draw lasso path | `onLassoComplete` → hit-test all object types → `selectedObjectIds` + `floatingSelectionToolbar` |
| Tap empty (with clipboard) | `onLassoTap` → `performLassoPaste(tapX, tapY)` — translate content to tap point, fresh UUIDs, persist, leave selected |
| Tap to dismiss | `onLassoTapToDismiss` → clear selection; if smart-lasso session, also exit lasso mode |
| Smart-lasso (circle) | `onSmartLassoComplete` → auto-enter lasso mode, select hit objects, show floating toolbar |
| Scribble-erase | `onScribbleEraseComplete` → soft-delete hit objects, rebuild bitmap |
| Drag selection | `onStrokesMoved` → update bounding boxes in DB via `ScratchpadDao.updateObjectData` |

**Floating selection toolbar** (scratch pad subset):

- Copy, Cut, Delete, Send to Notebook (visible only when launched-from-notebook and selection exists).
- Positioned dynamically below (or above) the selection bounding box via `positionFloatingToolbar`.

**Clipboard is shared** with notebooks — copy on the scratch pad can be pasted in a notebook and
vice-versa.

---

## Notebook → Scratch Pad ("Send to Scratch Pad")

Entry point: "Send to Scratch Pad" button in `NotebookActivity`'s `floatingSelectionToolbar`
(icon `ic_sketching_send`; shown when a lasso selection exists).

Flow:

1. Build `ClipboardContent` from the current lasso selection.
2. **Encryption guard**: if the notebook is encrypted, show `awaitEncryptionClipboardConfirm()`.
   Cancel aborts.
3. **Fit check**: compare selection bounding box to the current scratch pad page size.
   If it doesn't fit: `AlertDialog` "Crop to fit / Cancel". Crop = translate-to-origin; canvas clips
   overflow (no geometric point-cutting in phase 1).
4. **Placement**: `AlertDialog` "New page / Current page / Cancel".
   - New page → `scratchpadRepo.addPage(...)` then insert.
   - Current page → insert on the existing current page.
5. `scratchpadRepo.insertObjects(targetLayerId, content, density)` with fresh UUIDs + translation.
   Scratch pad UI is **not** opened; content is ready the next time it opens.
6. Toast "Sent to scratch pad".
7. After the toast, the scratch pad is opened via `scratchpadLauncher` with
   `EXTRA_JUMP_TO_PAGE_ID` + `EXTRA_SELECT_OBJECT_IDS` so the inserted objects are pre-selected.

---

## Scratch Pad → Notebook ("Send to Notebook")

Two entry points, both only visible when launched from a notebook (`EXTRA_FROM_NOTEBOOK_ID != null`):

| Entry point | Content sent |
|---|---|
| Toolbar `btnSendToNotebook` | All objects on the current scratch pad page |
| Floating toolbar `btnLassoSendToNotebook` | Current lasso selection only |

Both set `ScratchpadTransfer.pending` (`NotesproutClipboard.ClipboardContent`), call
`setResult(RESULT_OK)`, then `finish()`.

**`ScratchpadTransfer`** (`ScratchpadTransfer.kt`): a one-field in-memory singleton. Set by
`ScratchpadActivity` before `finish()`; consumed exactly once by `NotebookActivity` in the
`ActivityResultLauncher` result callback.

**`NotebookActivity` result callback** (`scratchpadLauncher`): if `RESULT_OK` and
`ScratchpadTransfer.pending != null`, pastes via the existing `performLassoPaste`-style path
(translate to origin, fresh UUIDs, leave selected), then clears `ScratchpadTransfer.pending`.
The notebook's `.soil` connection stays open since the activity is only paused, not destroyed.

**Semantics**: Send = copy, not move. Content remains in the scratch pad after sending.

---

## Encryption note

The scratch pad stores content in `notesprout.db`, which is **never encrypted**. Any content sent
to the scratch pad from an encrypted notebook uses the same `awaitEncryptionClipboardConfirm()`
warning shown for clipboard operations ("stored unencrypted on this device"). Cancel aborts the
transfer.

---

## Preferences

`ScratchpadPreferences` (`notebook/ScratchpadPreferences.kt`):
- Store: `SharedPreferences("notesprout_scratchpad_prefs")`
- Key: `current_page_index` (Int, default 0)

---

## Launch surfaces

The scratch pad launches from **two** places only:

| Surface | Intent extras |
|---|---|
| `MainActivity` (toolbar `btnScratchpad`) | None — `fromNotebookId` is null, Send-to-Notebook hidden |
| `NotebookActivity` (toolbar button, via `scratchpadLauncher`) | `EXTRA_FROM_NOTEBOOK_ID`, `EXTRA_FROM_NOTEBOOK_NAME`, `EXTRA_FROM_NOTEBOOK_ENCRYPTED` |

`NotebookActivity` may also re-launch the scratch pad with `EXTRA_JUMP_TO_PAGE_ID` and
`EXTRA_SELECT_OBJECT_IDS` after inserting content via "Send to Scratch Pad".

Never launched from: PageIndex, Link picker, Template browser, settings, or any other surface.
