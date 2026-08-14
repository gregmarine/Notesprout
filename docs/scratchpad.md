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
- Content lives in `notesprout.db`, which is
  [encrypted at rest](encryption.md#the-global-index-is-encrypted) under the **global** passphrase
  (never a per-notebook one).

### Key files

| File | Role |
|---|---|
| `data/index/ScratchpadEntity.kt` | Room `@Entity` for the `scratchpad` table |
| `data/index/ScratchpadDao.kt` | CRUD queries (insert, select by type/parent, soft-delete, update) |
| `data/ScratchpadRepository.kt` | Higher-level API (`ensureBootstrap`, `loadPage`, `saveStrokes`, `insertObjects`, `addPage`, `deletePage`, `setPageSize`) |
| `data/index/ListIds.kt` | `SCRATCHPAD_ROOT_ID` constant |
| `data/index/NotesproutDatabase.kt` | `ScratchpadEntity` in `@Database entities`; `MIGRATION_1_2` (DB is now `version=3` — see `docs/calendar.md`) |

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
        ├── chromeBar (LinearLayout, @dimen/toolbar_bar_thickness)
        │     title "Scratch Pad" · spacer · btnScratchpadPrev · tvScratchpadPageIndicator · btnScratchpadNext
        ├── 1dp divider
        ├── drawingContainer (FrameLayout — drawing view added programmatically)
        │     ├── floatingSelectionToolbar (gone by default)
        │     └── scratchOverflowMenu (gone by default — overflow rows, anchored bottom, above the bar)
        ├── 1dp divider
        └── scratchpadToolbar (LinearLayout, @dimen/toolbar_bar_thickness)
              btnScratchPen · btnScratchEraser · btnScratchLasso · btnScratchAddPage · btnScratchDeletePage
              spacer · btnSendToNotebook (launched-from-notebook only) · dividerScratchOverflow · btnScratchOverflow
```

**The tool bar overflows via the shared `ToolbarOverflowManager`** (see [`toolbar.md`](toolbar.md)):
at the tablet button size the full row no longer fits a narrow window (a Nomad's 75% window), so the
trailing tools spill into `scratchOverflowMenu`, opening *above* the bar. Send-to-Notebook is
registered as the manager's **trailing-pinned** view — right-aligned, never spilled — when launched
from a notebook, and **removed from the bar entirely** otherwise (a GONE child would still be counted
by the manager's fixed-size math). Dismiss rules in `dispatchTouchEvent` mirror the notebook's,
including the deferred close-on-UP so a tap on an overflowed button fires its click before the menu
hides.

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

**Snapshot:** none — the scratch pad stores no per-page snapshot (removed along with notebook page
snapshots). Content is re-rendered from strokes/objects on load.

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

The scratch pad stores content in `notesprout.db`, which is
[SQLCipher-encrypted at rest](encryption.md#the-global-index-is-encrypted) under the **global**
passphrase.

**There is no encryption gate on "Send to Scratch Pad".** Content moves from one encrypted store to
another; encrypted is encrypted. The old warning dialog (shared with clipboard copy) was removed
2026-07-18 — it existed to flag a move into *plaintext*, which no longer happens. See
[`clipboard-and-page-transfer.md`](clipboard-and-page-transfer.md#encryption-guard-objects--removed).

---

## Preferences

`ScratchpadPreferences` (`notebook/ScratchpadPreferences.kt`):
- Store: `SharedPreferences("notesprout_scratchpad_prefs")`
- Key: `current_page_index` (Int, default 0)

---

## Launch surfaces

| Surface | Intent extras |
|---|---|
| `MainActivity` (bottom-bar `btnScratchpad`, paired with `btnCalendar` in `surfaceButtonsGroup` — see [mainactivity-and-recents.md](mainactivity-and-recents.md)) | None — `fromNotebookId` is null, Send-to-Notebook hidden |
| `NotebookActivity` (toolbar button, via `scratchpadLauncher`) | `EXTRA_FROM_NOTEBOOK_ID`, `EXTRA_FROM_NOTEBOOK_NAME`, `EXTRA_FROM_NOTEBOOK_ENCRYPTED` |
| `CalendarActivity` / `DayDetailActivity` (toolbar button) | None — same as the library |
| `MainActivity` launch-restore (see below) | The extras of whatever it was opened from, replayed |

`NotebookActivity` may also re-launch the scratch pad with `EXTRA_JUMP_TO_PAGE_ID` and
`EXTRA_SELECT_OBJECT_IDS` after inserting content via "Send to Scratch Pad".

Never launched from: PageIndex, Link picker, Template browser, settings, or any other surface.

**Launch restore:** the scratch pad is one of the surfaces on the
[surface stack](mainactivity-and-recents.md#surface-stack--launch-restore-statesurfacestackkt) — if the app is killed with
it open, a cold launch reopens it **over whatever it was opened from** (the notebook, the calendar, the
day window), with the source notebook's `EXTRA_FROM_NOTEBOOK_*` replayed so Send-to-Notebook still
targets what it did. It records itself via `SurfaceStack.attach` / `markTop` in `onCreate` / `onResume`;
the page it comes back on is `ScratchpadPreferences`' job, as on any other open.
