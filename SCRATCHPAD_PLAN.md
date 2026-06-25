# Scratch Pad — Implementation Plan

> A "scratch pad" for quick jots and notes that don't have a home yet. Accessible from almost
> anywhere, with frictionless two-way transfer of content to/from notebook pages.
>
> **Audience:** this plan is written so it can be executed by Sonnet (medium effort) one session at a
> time with minimal independent design decisions. Each session is self-contained, ends in a clean
> debug build + install on **G102** (`b7a46e13`), a device test checklist, and — once the user
> confirms tests pass — a status update + commit (no push). The final session documents the feature
> and pushes.
>
> **Commit rule:** do **not** commit at the end of a session until all checklist items pass and any
> issues discovered during testing are fully resolved. Fix first, then commit.

---

## Status Tracker

| # | Session | Status |
|---|---------|--------|
| 1 | Data layer — `scratchpad` table, DAO, repository, Room migration | ✅ Done |
| 2 | Icons + host window + launch buttons (empty bordered window) | ✅ Done |
| 3 | Canvas integration — reuse Onyx/Generic view, pen/eraser, load/save | ⬜ Not started |
| 4 | Multi-page — add/delete/navigate (swipe + indicator) | ⬜ Not started |
| 5 | Lasso on scratch pad — select/copy/cut/paste, smart-lasso, scribble-erase | ⬜ Not started |
| 6 | Notebook → Scratch Pad transfer ("Send to Scratch Pad" + fit/crop) | ⬜ Not started |
| 7 | Scratch Pad → Notebook transfer ("Send to Notebook") | ⬜ Not started |
| 8 | Wrap-up — docs, cleanup, final build + commit + **push** | ⬜ Not started |

Legend: ⬜ Not started · 🚧 In progress · ✅ Done

---

## Locked Decisions (from kickoff Q&A)

1. **Reuse the existing drawing views.** Host `OnyxNotebookView` / `GenericNotebookView` (the
   `NotebookView` interface) on the scratch pad screen — do **not** build a new view pair. The "new
   canvas" feel comes from the host window + chrome, not a new view class.
2. **Support all object types** (stroke / heading / text / line / link). Because we reuse the views
   and the existing clipboard model already carries all five types, the scratch pad's storage,
   rendering, and transfer are **object-type-agnostic from day one**. The scratch pad has no
   *authoring* tools for non-stroke types (no text/heading/line/link insert buttons) — those objects
   only ever *arrive* via paste or "Send to Scratch Pad". This supersedes the original "strokes only"
   simplification.
3. **Host = floating-window Activity (`ScratchpadActivity`), translucent, e-ink styled.** Its own
   window → its own `SurfaceView`/`TouchHelper`, so Onyx EPD state stays clean. **No dim/scrim**
   behind it. Large screens: a centered window at **75%** of each screen dimension with a 1dp
   inkBlack `shape_bordered` border (the same border style used elsewhere). Small screens: full-screen
   with visible scratch-pad chrome (title + page indicator) so it never reads as a notebook.
4. **Page navigation = swipe + indicator.** One-finger deliberate horizontal swipe (reusing the
   notebook's swipe guards) **and** a `‹ 2 / 3 ›` indicator with prev/next arrows in the chrome.
5. **Keep always-on gestures.** Smart-lasso (circle-to-select) and scribble-to-erase stay active in
   pen mode on the scratch pad, matching the notebook.

---

## Assumptions (override any of these before/at the relevant session)

- **One global scratch pad** (single set of pages), shared everywhere. Not per-notebook.
- **Persistence:** scratch pad content lives in `notesprout.db` (always plaintext — the global index
  is never encrypted). The "current page" index persists in `SharedPreferences`
  (`notesprout_scratchpad_prefs`, key `current_page_index`).
- **Encryption warning:** sending content from an *encrypted* notebook into the scratch pad reuses the
  exact clipboard warning copy/flow (`awaitEncryptionClipboardConfirm`) — the scratch pad is plaintext
  storage, so the user must accept the same "stored unencrypted on this device" warning.
- **Pen:** a single default pen reusing the persisted global tool settings (`ToolPreferencesManager`);
  no per-pad pen-style picker in phase 1.
- **"Send to Notebook" (toolbar button)** closes the scratch pad, pastes the *current page's* content
  onto the notebook's current page, and leaves it as the active lasso selection.
- **"Send to Notebook" (lasso option)** sends *only the selection*, also closes the scratch pad, and
  leaves the pasted content selected on the notebook. Both Send-to-Notebook paths are visible **only**
  when the scratch pad was launched from a notebook.
- **MainActivity launch button** lives in the top toolbar cluster (next to `btnPinned` / `btnSearch` /
  `btnRecents` / `btnSort`) so it stays reachable from the Pinned / Recents / Search sub-views.
- **Crop semantics (Session 6):** when a notebook selection is too large for a scratch pad page, "crop"
  translates the selection so its top-left sits at the page origin `(0,0)` and lets the canvas clip the
  overflow (no geometric point-cutting in phase 1). True geometric cropping is a future enhancement.
  Confirm at Session 6.
- **Launch surfaces:** scratch pad launches **only** from MainActivity (incl. its Pinned/Recents/Search
  views) and NotebookActivity. Never from PageIndex, Link picker, Template browser, settings, etc.

---

## Architecture Overview

### Storage — `scratchpad` table in `notesprout.db`

A new table in the global index, **structured exactly like the `.soil` `notebook` table** so the
existing object payloads (`LiveStroke`, `HeadingStroke`, `TextRender`, `LineRender`, `LinkRender`) and
their `toJson()`/`fromJson()` serializers are reused verbatim.

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

**Row hierarchy** (mirrors a single notebook): a fixed-id root → pages → one content layer per page →
content objects.

- Root container: `type="scratchpad_root"`, fixed id `SCRATCHPAD_ROOT_ID =
  "00000000-0000-0000-0000-736372746368"` ("scrtch" in hex), `parentId = ""`. Created once on first use.
- Page: `type="page"`, `parentId = SCRATCHPAD_ROOT_ID`, `data = PageData(width,height,template="")`.
- Layer: `type="layer"`, `parentId = pageId`, `data = {"label":"Content","isLocked":false,"isVisible":true}`.
- Objects: `type ∈ {stroke,heading,text,line,link}`, `parentId = layerId`, same `data` JSON as `.soil`.

Soft deletes only (`deletedAt`), same as everywhere else.

### Why this shape

It lets the scratch pad reuse: the object serializers, the `NotebookView` load/save/render path
(`loadStrokes` / `loadHeadings` / `loadTextObjects` / `loadLineObjects` / `loadLinks` +
`buildRenderBitmap` / `loadStrokesWithBitmap`), the lasso copy/cut/paste pipeline
(`NotesproutClipboard` + `ClipboardStore` + `ClipboardMappers`), and the floating-selection toolbar.

### Transfer model (reuse the clipboard)

`NotesproutClipboard.ClipboardContent` (strokes + headings + text + line + link + bounding box) is the
neutral carrier between notebook and scratch pad. Both directions build a `ClipboardContent` and feed
it through the existing `performLassoPaste`-style insert path (translate + fresh UUIDs + leave
selected). A small in-memory hand-off singleton (`ScratchpadTransfer`) plus an `ActivityResult` flag
carries the "paste this on return" payload back to the notebook.

### Key reuse references (read before coding)

| Need | Reuse from |
|------|-----------|
| Engine pick | `NotebookActivity.isBooxDevice()` (line ~9555) → extract to a shared top-level `fun isBooxDevice()` in a new `core` util so both activities use it |
| Page/layer/object row creation | `NotebookActivity.addPage()` (~4263) and `data/NotebookFactory.createBlankNotebook` |
| Drawing-view interface | `notebook/NotebookView.kt` |
| Load + render path | `NotebookActivity.loadCurrentPage` (~3529) / `displayPage` (~3627) |
| Lasso copy/cut/paste | `NotebookActivity.performLassoCopy/performLassoCut/performLassoPaste` (~4593+) |
| Clipboard carrier | `NotesproutClipboard`, `data/ClipboardMappers.kt`, `data/ClipboardStore.kt` |
| Encryption warning | `NotebookActivity.awaitEncryptionClipboardConfirm()` |
| Toolbar styling | `Widget.Notesprout.ToolbarButton`, `bg_toolbar_button`, `shape_bordered` |
| Page indicator | `tvPageIndicator` pattern in `activity_notebook.xml` |
| Swipe nav guards | `NotebookActivity` one-finger swipe (distance ≥50% width, velocity ≥1.5× fling, horizontal dominance) |

---

## Session 1 — Data Layer

**Goal:** the `scratchpad` table exists, migrates cleanly, and has a tested repository API. No UI.

**Files**
- `data/index/NotesproutDatabase.kt` — bump `version = 1 → 2`; add `ScratchpadEntity::class` to
  `@Database entities`; add `abstract fun scratchpadDao(): ScratchpadDao`; register `MIGRATION_1_2`
  (creates the table + index above) at the builder site in `NotesproutIndex`.
- `data/index/ScratchpadEntity.kt` — Room `@Entity(tableName = "scratchpad")` mirroring the columns
  above (use `@ColumnInfo(name = "order")` for the reserved word).
- `data/index/ScratchpadDao.kt` — `getPagesSorted()`, `getLayerForPage(pageId)`,
  `getObjectsForLayer(layerId)` (returns all non-deleted child rows; caller splits by `type`),
  `insertObject`, `insertAll`, `updateOrder`, `softDelete(id, deletedAt)`, `getRootCount()`,
  `getObjectById(id)`. Mirror the equivalent queries in `data/NotebookDao.kt`.
- `data/ScratchpadRepository.kt` — higher-level API on `Dispatchers.IO`:
  - `ensureBootstrap()` — if no root row, create root + one blank page + its layer (page size = a
    sensible default; real size is set when the canvas first lays out, see Session 3).
  - `loadPage(pageId)` → returns lists of `LiveStroke`/`HeadingStroke`/`TextRender`/`LineRender`/
    `LinkRender` (reuse the same row→object decoders the notebook load path uses).
  - `saveStrokes(layerId, strokes)` (insert-or-ignore, transaction), `insertObjects(layerId, content)`,
    `softDeleteObjects(ids)`, `addPage(afterIndex)`, `deletePage(pageId)`, `getPages()`,
    `setPageSize(pageId, w, h)`.
- `notebook/ScratchpadPreferences.kt` — `SharedPreferences("notesprout_scratchpad_prefs")`,
  `currentPageIndex` get/set (mirror `SnapPreferences`).
- Constant `SCRATCHPAD_ROOT_ID` (place next to `ListIds`).

**Notes for the implementer**
- The decoders for `data`→object exist in the notebook load path; factor the shared decode helpers so
  both `.soil` loading and scratch pad loading call the same code (avoid copy-paste drift). If that's
  too invasive, replicate minimally and add a `// keep in sync with NotebookActivity` comment.
- `notesprout.db` is opened in `NotesproutApplication.onCreate`; the migration runs there. Do **not**
  destructively migrate.

**Build + install:** `./gradlew assembleDebug` → install on G102.

**Device test checklist**
1. App launches without crash on a DB that predates the migration (install over the existing dev
   build — do not uninstall first).
2. Open and close a notebook; no regressions.
3. (Optional dev check) `adb -s b7a46e13 pull …/files/notesprout.db` and confirm a `scratchpad` table
   exists (`.schema scratchpad`).

**Exit:** all checklist items pass and any issues are resolved → mark Session 1 ✅ in the tracker → commit (no push).

---

## Session 2 — Icons + Host Window + Launch Buttons

**Goal:** tapping the scratch pad button from MainActivity or a notebook opens an empty, correctly
sized, bordered scratch-pad window with chrome + a placeholder toolbar. No drawing yet.

**Icons** (Tabler stroke style, 24dp, `@color/inkBlack`, no fill)
- Download the Tabler **`sketching`** icon → `res/drawable/ic_sketching.xml` (scratch pad launch).
- `res/drawable/ic_sketching_send.xml` — copy of `ic_sketching` with a small arrow in the **top-right**
  pointing up-and-to-the-right ("send to scratch pad").
- `res/drawable/ic_sketching_receive.xml` — copy with a small arrow in the **bottom-right** pointing
  down-and-to-the-right ("send to notebook").
- Follow the multi-`<path>` pattern in `ic_lasso_clipboard.xml` for the added arrow strokes.

**Host**
- `res/values/themes.xml` — add `Theme.Notesprout.Scratchpad` (parent `Theme.Notesprout`):
  `android:windowIsTranslucent = true`, `android:windowBackground = @android:color/transparent`,
  `android:backgroundDimEnabled = false`, `android:windowAnimationStyle = @null`.
- `AndroidManifest.xml` — register `ScratchpadActivity` with that theme, `android:launchMode` left
  default, `configChanges` for orientation/size to match other activities.
- `res/layout/activity_scratchpad.xml` — root transparent `FrameLayout` (gravity center) →
  `scratchpadWindow` (`shape_bordered`, paper-white) containing:
  - **chrome bar** (top): title `TextView` "Scratch Pad" + spacer + prev arrow (`ic_arrow_left`) +
    `tvScratchpadPageIndicator` ("1 / 1") + next arrow (mirror `ic_arrow_left`/use a right chevron).
  - **drawingContainer** `FrameLayout` (drawing view added in Session 3).
  - **scratchpadToolbar** `LinearLayout` (placeholder buttons now): Pen, Eraser, Lasso, Add Page,
    Delete Page, Send to Notebook (Send hidden unless launched-from-notebook). Reuse
    `Widget.Notesprout.ToolbarButton` + `toolbar_background_*`.
  - A `floatingSelectionToolbar` block copied/adapted from `activity_notebook.xml` (used in Session 5).
- `ScratchpadActivity.kt` — skeleton:
  - `EXTRA_FROM_NOTEBOOK_ID` / `EXTRA_FROM_NOTEBOOK_NAME` (nullable) + `EXTRA_FROM_NOTEBOOK_ENCRYPTED`.
  - In `onCreate`, after `setContentView`, size `scratchpadWindow`: if smallest screen width is "large"
    (use a `bool` resource `is_large_screen` defined via `values-sw600dp`), set window to 75% × 75% of
    display metrics, centered; else `match_parent` (full screen). Border always shown.
  - Show/hide Send-to-Notebook based on `EXTRA_FROM_NOTEBOOK_ID != null`.

**Launch wiring**
- MainActivity: add `btnScratchpad` (`ic_sketching`) to the top toolbar cluster in `activity_main.xml`;
  wire in `MainActivity.kt` to start `ScratchpadActivity` (no notebook extras).
- NotebookActivity: add a `"scratchpad"` button to the toolbar. Add the view to
  `activity_notebook.xml`, register it in `notebook/ToolbarButtonRegistry.kt`
  (**append-only key** `"scratchpad"`, icon `ic_sketching`, group `GROUP_NOTEBOOK` or a sensible
  group), and wire the listener in `NotebookActivity.kt` to launch `ScratchpadActivity` **via an
  `ActivityResultLauncher`** (Session 7 needs the result), passing notebook id/name/encrypted. The
  append-only registry migration already handles inserting the new key for existing users.

**Build + install** on G102.

**Device test checklist**
1. MainActivity shows the scratch pad button; tapping opens the bordered window. On G102 (large
   e-ink) it's ~75% centered with a visible 1dp border and the area behind it still visible (no dim).
2. The notebook toolbar shows the scratch pad button; tapping from inside a notebook opens the window
   and the "Send to Notebook" button is visible.
3. From MainActivity launch, "Send to Notebook" is **hidden**.
4. Chrome shows "Scratch Pad" + "1 / 1" + arrows; toolbar shows placeholder buttons.
5. Back/outside dismiss closes it cleanly and returns to the prior screen with no EPD artifacts.

**Exit:** all checklist items pass and any issues are resolved → Session 2 ✅ → commit.

---

## Session 3 — Canvas Integration (Onyx + Generic)

> **Highest-risk session.** Onyx raw drawing must work in an **offset, translucent, sized** window.
> Validate alignment early. If Onyx raw drawing cannot align in the 75% offset window, fall back to
> full-screen-on-all-devices for the scratch pad (still bordered chrome) and note it in the tracker.

**Goal:** write with pen, erase, and have strokes (and any object types present) render and persist
per page.

**Files / steps**
- Extract `isBooxDevice()` to a shared `core/Device.kt` top-level `fun isBooxDevice()`; have
  `NotebookActivity` and `ScratchpadActivity` both call it.
- In `ScratchpadActivity`: create `drawingView = if (isBooxDevice()) OnyxNotebookView(this) else
  GenericNotebookView(this)`, add to `drawingContainer`.
- On first layout (`drawingContainer.doOnLayout`): compute the canvas pixel size; if the current page
  has no real size yet, `repository.setPageSize(...)`; set `PageData` width/height accordingly.
- Wire pen + eraser buttons to `setEraserMode` / pen selection (mirror NotebookActivity's button
  handlers, minus tools we don't expose). Persist active tool via the existing
  `ToolPreferencesManager` or a scratch-pad-local equivalent (decide: reuse global — recommended).
- Load path: `repository.loadPage(currentPageId)` → `loadHeadings/loadTextObjects/loadLineObjects/
  loadLinks` then `buildRenderBitmap` + `loadStrokesWithBitmap` (off-thread build, main-thread swap),
  exactly like `displayPage`. Blank white background (no template).
- Save path: wire `drawingView.onPenLifted` → incremental `repository.saveStrokes(currentLayerId, …)`
  (insert-or-ignore, transaction, `persistedStrokeIds` tracking — mirror the notebook).
- Wire `onStrokeErased`, `onHeadingErased`, `onTextErased`, `onLineErased`, `onLinkErased`,
  `onSnapshotReady` (snapshots optional for scratch pad — may skip; if skipped, always full-load).
- EPD overlay handoff + toolbar-touch release: replicate the `dispatchTouchEvent` toolbar-release rule
  and tool-state invariants from the notebook (see `docs/drawing-engine.md`).
- `bootstrap` on open: `repository.ensureBootstrap()`; restore `currentPageIndex` from prefs (clamp).
- Save current state on close (`onPause`/back): flush strokes, persist current page index.

**Build + install** on G102.

**Device test checklist**
1. Open scratch pad → write with the pen. **Ink lands exactly under the stylus** (no offset) inside the
   75% window on G102.
2. Strokes survive: close scratch pad, reopen → strokes are still there.
3. Eraser erases; erased strokes stay gone after reopen.
4. Scribble-to-erase and smart-lasso are *not yet expected to work* (Session 5) — but writing a normal
   loop must not crash.
5. Open scratch pad from a notebook, write, close, reopen the notebook — the **notebook canvas is
   intact** (no EPD corruption from the overlapping surfaces).
6. (Generic device, optional — e.g. Wacom) writing works there too.

**Exit:** all checklist items pass and any issues are resolved → Session 3 ✅ → commit.

---

## Session 4 — Multi-Page (Add / Delete / Navigate)

**Goal:** multiple pages with swipe + indicator navigation, plus Add Page / Delete Page.

**Files / steps**
- Track `pages`, `currentPageIndex`, `currentPageId`, `currentLayerId` in `ScratchpadActivity` (mirror
  the notebook fields).
- Add Page button → `repository.addPage(afterIndex = currentPageIndex)` → reload pages → navigate to
  the new page (mirror `NotebookActivity.addPage` semantics: insert after current, advance pointer).
- Delete Page button → `repository.deletePage(currentPageId)`; if it was the last remaining page,
  instead clear it (soft-delete all objects, keep the page) so there's always ≥1 page. Re-clamp index.
- Page navigation:
  - **Swipe:** one-finger deliberate horizontal swipe in `dispatchTouchEvent`, reusing the notebook's
    three guards (distance ≥50% canvas width, velocity ≥1.5× fling threshold, horizontal dominance).
    Finger-only (stylus writes). Prev page on right-swipe, next on left-swipe (match notebook).
  - **Arrows + indicator:** prev/next chrome arrows call the same navigate function; update
    `tvScratchpadPageIndicator` to `"{i+1} / {n}"`. Disable-look the arrow at the ends (hide or
    no-op — remember disabled buttons are invisible on e-ink, so use a no-op + nothing, or hide).
- Navigation must save the leaving page's strokes first, then load the target (two-phase, like the
  notebook). No `eraseAll`-then-write races.
- Persist `currentPageIndex` to prefs on every navigation and on close.

**Build + install** on G102.

**Device test checklist**
1. Add Page → indicator goes "1 / 1" → "2 / 2"; new page is blank; lands on it.
2. Write distinct content on pages 1 and 2; swipe left/right → content matches the indicator.
3. Arrow buttons navigate identically; indicator stays in sync.
4. Delete a middle page → pages renumber; content correct; lands on a valid page.
5. Delete the only page → it clears to blank but a page still exists ("1 / 1").
6. Navigate to page 2, close, reopen → reopens on page 2 (current-page persistence).
7. Swipe does not fire while writing with the stylus.

**Exit:** all checklist items pass and any issues are resolved → Session 4 ✅ → commit.

---

## Session 5 — Lasso on Scratch Pad

**Goal:** lasso select + copy/cut/paste/delete on the scratch pad, plus the always-on smart-lasso and
scribble-erase, all reusing the existing clipboard.

**Files / steps**
- Wire the Lasso button → `setLassoMode` (mirror the notebook's lasso enter/exit, `isSelected` state).
- Wire `onLassoComplete` → run the hit test (reuse `LassoGeometry` + the same hit-test the notebook
  uses) → set `lassoSelectedIds` + selection box + show `floatingSelectionToolbar`.
- Floating selection toolbar (scratch pad subset): Copy, Cut, Delete, Paste behavior. Reuse:
  `performLassoCopy`/`performLassoCut`/`performLassoPaste` logic — factor the notebook's
  implementations into a shared helper that takes the DAO + drawing view, **or** replicate against
  `ScratchpadDao` with the same translate-+-fresh-UUID-+-leave-selected behavior. Use
  `NotesproutClipboard` + `ClipboardStore` (the same global clipboard — copy on the scratch pad can be
  pasted in a notebook and vice-versa; that's desirable).
- Smart-lasso: wire `onSmartLassoComplete` → enter lasso mode with the hit selection (mirror notebook).
- Scribble-erase: wire `onScribbleEraseComplete` → soft-delete hit objects + rebuild bitmap (mirror
  notebook). Snap-to-guide is optional on the scratch pad — recommend leaving the snap toggle out of
  the scratch-pad floating toolbar for phase 1.
- `onLassoTap` / `onLassoTapToDismiss` wired (paste on tap when clipboard has content; dismiss
  selection on tap).

**Build + install** on G102.

**Device test checklist**
1. Lasso-select content → floating toolbar appears; Copy → tap elsewhere → Paste places a copy,
   selected.
2. Cut → content removed; Paste restores it elsewhere.
3. Delete → content removed.
4. Smart-lasso: draw a quick closed circle around content → it gets selected (no toolbar tap).
5. Scribble hard back-and-forth over content → it erases.
6. Copy on the scratch pad, close it, open a notebook, paste → the copied content pastes into the
   notebook (shared clipboard).
7. Paste across scratch pad pages (copy on page 1, navigate, paste on page 2).

**Exit:** all checklist items pass and any issues are resolved → Session 5 ✅ → commit.

---

## Session 6 — Notebook → Scratch Pad ("Send to Scratch Pad")

**Goal:** from a notebook lasso selection, send content into the scratch pad, with a fit/crop prompt
and a new-page/current-page prompt.

**Files / steps**
- Add **"Send to Scratch Pad"** to the notebook's `floatingSelectionToolbar` (`activity_notebook.xml`,
  icon `ic_sketching_send`, hidden by default, shown whenever a selection exists). Wire in
  `NotebookActivity`.
- On tap, build a `ClipboardContent` from the current selection (reuse the copy path's gather logic).
- **Encryption guard:** if the notebook is encrypted, show `awaitEncryptionClipboardConfirm()` first;
  Cancel aborts.
- **Fit check:** read the scratch pad current page size (from `ScratchpadRepository`); compare to the
  selection bounding box. If the selection (translated to origin) doesn't fit within the page bounds,
  show an `AlertDialog`: *"This selection is larger than the scratch pad. Crop it to fit, or cancel?"*
  → **Crop / Cancel** (`shape_bordered`, e-ink dialog rules). Crop = translate-to-origin + canvas clip
  (see Assumptions; confirm semantics here).
- **Placement prompt:** `AlertDialog` *"Add to the scratch pad?"* → **New page** / **Current page** /
  Cancel. New page = `repository.addPage` then insert; Current page = insert on the current page.
- Insert via `repository.insertObjects(targetLayerId, content)` with fresh UUIDs + translation. The
  scratch pad's "current page" is whatever it was last on (tracked in prefs) — do **not** open the
  scratch pad UI; this is a background hand-off. Show a confirmation Toast ("Sent to scratch pad").
- The strokes/objects must be ready to see next time the scratch pad opens.

**Build + install** on G102.

**Device test checklist**
1. In a notebook, lasso-select content → "Send to Scratch Pad" appears.
2. Send with selection that fits → choose **New page** → open scratch pad → content is on a new page.
3. Send → choose **Current page** → content lands on the existing current page.
4. Send an over-large selection → crop/cancel prompt appears; Crop places it at the page origin;
   Cancel aborts with nothing added.
5. From an **encrypted** notebook → the unencrypted-clipboard warning appears; Cancel aborts; Continue
   proceeds.
6. Mixed-type selection (e.g. strokes + a text object) sends all types.

**Exit:** all checklist items pass and any issues are resolved → Session 6 ✅ → commit.

---

## Session 7 — Scratch Pad → Notebook ("Send to Notebook")

**Goal:** send scratch pad content back to the launching notebook — whole page or just a selection —
closing the scratch pad and leaving the content selected on the notebook.

**Files / steps**
- `ScratchpadTransfer` (in-memory singleton): `var pending: NotesproutClipboard.ClipboardContent?` +
  a flag. Set on send; consumed once by the notebook on return.
- **Toolbar "Send to Notebook"** (`ic_sketching_receive`, visible only when launched-from-notebook):
  gather all objects on the current scratch pad page → set `ScratchpadTransfer.pending` →
  `setResult(RESULT_OK)` → `finish()`.
- **Lasso "Send to Notebook"** option in the scratch pad floating selection toolbar
  (`ic_sketching_receive`, visible only when launched-from-notebook **and** a selection exists): gather
  the selection → same hand-off → finish.
- `NotebookActivity` launched the scratch pad via an `ActivityResultLauncher`. In the result callback:
  if `RESULT_OK` and `ScratchpadTransfer.pending != null`, run the existing paste path
  (`performLassoPaste`-equivalent) onto the current notebook page at origin offset, leave the pasted
  objects selected (lasso mode + floating toolbar), then clear `ScratchpadTransfer.pending`. The
  notebook is paused, not destroyed, so its `.soil` connection is still open.
- Coordinate handling: scratch pad pages are ≤ notebook pages, so content fits; paste at `(0,0)` offset
  (top-left aligned). No crop needed in this direction.

**Build + install** on G102.

**Device test checklist**
1. Open scratch pad from a notebook, write, tap **Send to Notebook** → scratch pad closes, content
   appears on the notebook's current page and is **selected** (floating toolbar visible).
2. The sent content is still also in the scratch pad (send = copy, not move) — confirm intended; if it
   should clear, adjust (default: copy/keep).
3. Lasso-select on the scratch pad → **Send to Notebook (selection)** → only that selection lands on
   the notebook, selected.
4. Launch scratch pad from MainActivity → neither Send-to-Notebook control is present.
5. After return, the notebook canvas + EPD state are clean; undo works on the pasted content.

**Exit:** all checklist items pass and any issues are resolved → Session 7 ✅ → commit.

---

## Session 8 — Wrap-Up (docs, cleanup, push)

**Goal:** document the feature and ship.

- `docs/scratchpad.md` — data model (`scratchpad` table + row hierarchy), host window/theme, canvas
  reuse, navigation, lasso, both transfer directions, encryption note, prefs, key files.
- `CLAUDE.md` — add a row to the Detailed Documentation table pointing at `docs/scratchpad.md`; add a
  line to Community Nomenclature only if a themed name is wanted (optional).
- `BACKLOG.md` — record deferred items surfaced during build (true geometric crop, per-pad pen styles,
  snapshot fast-load for the scratch pad, move-vs-copy on Send-to-Notebook, scratch-pad-only undo
  persistence, etc.).
- Code cleanup: remove dead placeholders, de-dupe any copy-pasted notebook logic into shared helpers
  where low-risk, ensure `Slog` (not `Log.d`), confirm no new Gradle deps, no Material, no reflection
  JSON.
- Clean build + install on G102; quick smoke of every prior session's headline test.
- Update tracker to all ✅; commit; **push** (the only session that pushes).

---

## Risks & Watch-Items

- **Onyx raw drawing in an offset/translucent window (Session 3).** The single biggest risk. Validate
  ink alignment first; fall back to full-screen scratch pad if needed.
- **Two live `SurfaceView`s** (notebook behind + scratch pad in front). The translucent Activity keeps
  the notebook's surface alive but unfocused; confirm the notebook's overlay releases on focus loss and
  re-arms cleanly on return (Sessions 3 & 7 tests #5).
- **Coordinate spaces differ** between scratch pad pages and notebook pages → the fit/crop logic
  (Session 6) and origin-aligned paste (Session 7) must use each surface's real pixel size.
- **Room migration** is additive and non-destructive; never bump with `fallbackToDestructiveMigration`.
- **Append-only toolbar registry keys** — never rename/reorder existing keys; the new `"scratchpad"`
  key must be appended.
</content>
</invoke>
