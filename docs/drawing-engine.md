# Drawing Engine Architecture

> Referenced from `CLAUDE.md`. Covers the two drawing engines, EPD/overlay rules, tool-state
> invariants, performance rules, and the committed-content render model.

## Files

- `notebook/NotebookView.kt` — interface for both engines; all drawing, lasso, heading, render ops
- `notebook/OnyxNotebookView.kt` — BOOX: TouchHelper, RawInputCallback. `onPenLifted` fires on `onEndRawDrawing`. `onBeginRawDrawing` re-enables render guarded by `!isEraserMode`.
- `notebook/GenericNotebookView.kt` — standard Canvas: two-layer Bitmap, stylus-only (`TOOL_TYPE_STYLUS` + `TOOL_TYPE_ERASER`), historical point capture. `onPenLifted` fires on `ACTION_UP`.
- `NotebookActivity.kt` — fullscreen immersive, multi-page state, incremental save via `insertOrIgnore`. One-finger deliberate swipe for page navigation (three guards: distance ≥50% screen width, velocity ≥1.5× fling threshold, horizontal dominance). Two-finger swipe left/right inserts a page after/before current and navigates to it (same guards). Two-finger stationary double-tap = undo; three-finger stationary double-tap = redo. On BOOX the Onyx SDK intercepts 3-finger touches and sends `ACTION_CANCEL` before `ACTION_UP` — the 3-finger detector treats a cancel on an armed, stationary 3-finger gesture as tap completion. All of these detectors sit behind the pen-activity gate (see below) so a resting palm can't drive them.
- `MainActivity.kt` — notebook list, adaptive grid (3/2 cols at 480dp), pagination, empty state, bottom bar.

## Key Build Facts

- `minSdk = 29`; `android.enableJetifier=true` (Onyx SDK bundles old support classes)
- `jniLibs.pickFirsts` for `libc++_shared.so`
- `defaultConfig.ndk { abiFilters += "arm64-v8a" }` — all target devices are 64-bit ARM. Do NOT `exclude com.tencent:mmkv:1.0.19` — `onyxsdk-base` references it; removing it risks `NoClassDefFoundError`. ML Kit `libdigitalink.so` is 16 KB-aligned at `digital-ink-recognition:19.0.0`.
- `NotesproutApplication.onCreate` calls `HiddenApiBypass.addHiddenApiExemptions("")` before any SDK init
- `setStrokeColor(Color.BLACK)` required on TouchHelper init — NoteAir5C color panel defaults to non-black
- Toolbar z-order: toolbar must overlay the drawing container in a `FrameLayout` — native SurfaceView occludes siblings below it
- `onSizeChanged()` calls `redrawCanvas()` (not just white fill) in both drawing views — handles the case where `loadStrokes()` runs before view layout

## EPD Rules — Never Violate These

**First-stroke fast-mode (`HWR_APP_SCOPE`):**
- The first stroke after opening a page / after a page-flip used to lag 1–2s on BOOX (G6/G102): the panel sits in a quality waveform and the first stroke pays a GC→handwriting mode-switch. Fix: `EpdController.applyAppScopeUpdate(HWR_APP_SCOPE, true, false, UpdateMode.HAND_WRITING_REPAINT_MODE, 0)` in `openRawDrawing` (pen branch only), pinning the app in the fast handwriting waveform so there's no switch. Proven the **sole** fix by an on-device sweep of every EPD mode (scribble / view-mode / system-fast all still lagged; only app-scope was instant, no ghosting).
- Applied once when the pen pipeline opens; stays active across page-flips; **cleared in `closeRawDrawingIfOwner`** (`clearAppScopeUpdate()`) so menus / dialogs / other screens render in normal quality. It follows pen ownership across sticky / scratch-pad handoff automatically. Keep it out of the handoff/lifecycle code — the minimal apply-on-open / clear-on-close placement is deliberate.

**Overlay lifetime:**
- The overlay ("writing mode") stays active indefinitely while the user writes. No idle-release timer.
- Legitimate handoff points: `setEraserMode(true)`, `eraseAll()`, `setTemplate()`, `loadStrokesWithBitmap()`, `onWindowFocusChanged(false)`, toolbar finger touch.
- `onPenLifted` is a DB-save trigger only — does NOT touch the overlay.

**Toolbar touch → overlay release:**
- Any finger `ACTION_DOWN` within `drawingToolbar.bottom` (intercepted in `NotebookActivity.dispatchTouchEvent`) calls `drawingView.releaseRender()` before the child button handles the event — **unless the pen-activity gate is closed** (see below); a palm landing over the bar mid-word is not a button press.
- `releaseRender()`: `setRawDrawingRenderEnabled(false)` → `invalidate()`. No `handwritingRepaint` needed.
- Overlay re-enables automatically via `onBeginRawDrawing` on the next pen stroke.
- Must use `dispatchTouchEvent` (not `setOnTouchListener`) — button children always consume touches, so `setOnTouchListener` on the ViewGroup never fires.

**Overlay handoff (`eraseAll()`):**
- `setRawDrawingRenderEnabled(false)` → white bitmap → `invalidate()` → `EpdController.handwritingRepaint(view, Rect(0,0,w,h))` → re-enable
- **`handwritingRepaint` is required.** `setRawDrawingRenderEnabled` is a lightweight toggle; it does NOT clear the hardware buffer. Without it: gray residue + black flash.
- `EpdController.setUpdListSize(512)` in `openRawDrawing()` suppresses mid-session GC16 refresh — do not remove.
- `renderStroke` calls `invalidate()` on every stroke so the Android canvas stays continuously current with the overlay.

**Eraser overlay:**
- On eraser start: `setRawDrawingRenderEnabled(false)` + `invalidate()` — immediately, before any erase logic. If not released first, the overlay hides the bitmap erase result (phantom strokes remain visible).
- `handwritingRepaint` after erase gesture ends only — NEVER during move events (causes full EPD flash per stroke).
- `onBeginRawDrawing` re-enables render guarded by `!isEraserMode`.

**setTemplate() EPD handoff:**
- `setRawDrawingRenderEnabled(false)` → `redrawCanvas()` → `EpdController.handwritingRepaint()` → `setRawDrawingEnabled(true)`. Without `handwritingRepaint`, the template change is invisible on e-ink.

**Process-global pen ownership (cross-activity handoff):**
- The BOOX raw-drawing pipeline (`TouchHelper` → `EpdController` raw input) is a **single process-global hardware resource** — only one `OnyxNotebookView` can own it at a time, even though every drawing host (notebook, calendar, day-detail, scratch pad, sticky note) builds its own view + `TouchHelper`. All hosts are `standard`-launch Activities, so **two live drawing views coexist during any transition.**
- **The hazard:** Android runs the incoming screen's `openRawDrawing()` *before* the outgoing screen's `onDestroy → closeRawDrawing()`. The outgoing view's late close then tears down the pipeline the incoming view just claimed → the canvas silently stops accepting the stylus until a focus cycle re-arms it. Seen when switching calendar↔notebook, notebook↔notebook (recents/link), moving/copying pages, or closing a scratch-pad/sticky overlay. Intermittent because the incoming open is async (layout/focus-driven) while the outgoing close is synchronous in `onDestroy`.
- **Ownership guard (`OnyxNotebookView.penOwner`, companion `@Volatile`):** `openRawDrawing()` sets `penOwner = this`; both close sites (`onDetachedFromWindow`, `releaseResources`) route through `closeRawDrawingIfOwner()`, which calls `touchHelper.closeRawDrawing()` **only when `penOwner === this`** and otherwise skips (dropping local `isSetup` only). A superseded view can never close the live view's session. All access is main-thread — it is purely an ordering guard, no locking. This is the backbone; it makes every teardown ordering safe on its own.
- **Focus-independent reclaim (`resumeDrawing()`):** hosts call this from `onResume` (**not** `enableDrawing()`) so the surviving screen reclaims the pipeline without depending on the BOOX-flaky `onWindowFocusChanged(true)` event — reopen if released, restart if superseded (an overlay took it), else just re-enable. DayDetail also calls it when entering **Note** mode (`setViewMode`).
- **Clean handoff (`releaseForHandoff()`):** the opaque "open another drawing screen and finish" paths — calendar/day-detail → notebook (paste/send), notebook → notebook (recents-switch / link-follow), encrypt/decrypt reopen — call this immediately before `startActivity` so the outgoing view closes its session *while still the owner*, leaving no dangling raw-input session. The ownership guard remains the safety net for paths that don't (translucent overlays, backgrounding).
- **Diagnostics (`EPD_TIMING`):** `PEN_OWNER_CLAIMED`, `CLOSE_RAW_DRAWING_SKIPPED reason=notOwner` (guard catching a would-be clobber), `RESUME_DRAWING`, `RELEASE_FOR_HANDOFF`.

## Pen-Activity Gate — Palm vs. Finger Gestures

**The problem.** On Onyx, stylus ink runs through the SDK raw-drawing pipeline and never produces
Android `MotionEvent`s — but a palm resting on the glass still does. Every drawing host fed finger
events unconditionally to its gesture detectors, so a palm roll mid-word registered as a
tap / swipe / double-tap. The handlers that fired then reached into the **live pen session**
(`releaseRender()`, or `setLimitRect()` via the toolbar toggle), dropping the stroke being written.
One cause, two symptoms: strokes intermittently not registering, and false-positive double-taps.

**The gate.** `NotebookView.isPenActive` — true while the stylus is down, plus a tail of
`PEN_ACTIVE_TAIL_MS` (350 ms) after it lifts. The tail is deliberately longer than the platform
double-tap window (~300 ms) so the second half of a palm-induced "double tap" can't land just after
the pen leaves the glass and be treated as a clean gesture. Short enough that a deliberate finger tap
right after writing still registers — **this constant is the tuning dial** if taps ever feel
swallowed.

Tracked in both engines, from both directions:

| Engine | Source of truth |
|---|---|
| `OnyxNotebookView` | `onBeginRawDrawing` / `onEndRawDrawing` **and** `onBeginRawErasing` / `onEndRawErasing` (marked *before* the mode guards), **plus** stylus `MotionEvent`s in `onTouchEvent` — lasso / lasso-eraser / text-placement / shape-transform disable raw drawing, so the SDK callbacks never fire in those modes and the stylus arrives as an ordinary event |
| `GenericNotebookView` | stylus `MotionEvent`s in `onTouchEvent` — all ink arrives this way, so one hook covers every mode |

**Applied on all five drawing screens.** Each checks `drawingView.isPenActive` at the top of its
finger branch in `dispatchTouchEvent` and, on the first suppressed event, latches
`fingerGesturesSuppressed` and calls its own `cancelFingerGestures()`.

| Screen | Gated detectors |
|---|---|
| `NotebookActivity` | page swipe, link/sticky follow, toolbar toggle, multi-finger undo/redo |
| `ScratchpadActivity` | page swipe, sticky tap, multi-finger undo/redo |
| `CalendarActivity` | nav swipe + day tap, sticky tap, multi-finger undo/redo |
| `DayDetailActivity` | sticky tap, multi-finger undo/redo (NOTE view only) |
| `StickyNoteEditorActivity` | multi-finger undo/redo |

**Two rules when touching this code:**

- **`cancelFingerGestures()` must reset state fields directly — never route a synthetic
  `ACTION_CANCEL` through the detectors.** Every screen carries the Onyx 3-finger workaround, where a
  cancel on an armed, stationary 3-finger gesture counts as a *completed* tap. Routing through it
  would fire the exact false positive the gate exists to prevent.
- **Clear the first-tap timestamps** (`twoFingerTapFirstTime`, `threeFingerTapFirstTime`,
  `toggleFirstTapTime`), not just the in-flight flags. A stale armed first tap otherwise pairs with a
  real tap after the pen lifts and fires a phantom undo / redo / toolbar toggle.

On the four secondary screens the gate returns early out of the whole finger branch, so the
chrome/toolbar `releaseRender()` is skipped too. In `NotebookActivity` that release is entangled with
overflow-menu dismissal logic that must keep running, so there only the release call itself is
guarded. Same effect, different shape.

## Tool-State Invariants (OnyxNotebookView)

When a Dialog is shown over NotebookActivity, focus changes trigger `onWindowFocusChanged(false)` → `setRawDrawingEnabled(false)`. On return: `onWindowFocusChanged(true)` → `openRawDrawing()`. Also triggered by `onResume()` → `resumeDrawing()` (which reclaims the process-global pen pipeline — see the pen-ownership rule above).

| Active tool | `setRawDrawingEnabled` | `setRawDrawingRenderEnabled` |
|---|---|---|
| Pen | `true` | `true` (SDK manages) |
| Eraser | `true` | `false` (prevents phantom pen strokes on overlay) |
| Lasso / Lasso Eraser | `false` | n/a |
| Text placement | `false` | n/a |

`openRawDrawing()` and `enableDrawing()` must guard `setRawDrawingEnabled(true)` with `!isLassoMode && !isLassoEraserMode && !isTextPlacementMode`. If the guard passes and `isEraserMode` is true, immediately follow with `setRawDrawingRenderEnabled(false)`. Failing this causes phantom pen strokes on the EPD overlay — they look real but vanish on the next EPD refresh.

## Stylus Barrel Button

The BOOX stylus barrel button is reported to Android as `TOOL_TYPE_ERASER` (not `BUTTON_STYLUS_PRIMARY`). In pen/eraser mode the Onyx SDK intercepts this at the hardware level and fires `onBeginRawErasing` → the existing erasing callbacks handle it. In modes where `setRawDrawingEnabled(false)` is active (lasso, lasso eraser, text placement), the SDK is silent and the button event arrives only via Android's `onTouchEvent`. Both views intercept `TOOL_TYPE_ERASER` (and `BUTTON_STYLUS_PRIMARY` for completeness) **before** the per-mode handler in `onTouchEvent`, routing to `handleBarrelButtonErase` which calls `eraseAtPath` → `finalizeEraseRedraw` → `handwritingRepaint`. This gives consistent erase-on-button behavior regardless of active toolbar tool.

## Performance Rules (Do Not Regress)

**Save path:** Wrap INSERT OR IGNORE loops in `db.withTransaction {}`; track `persistedStrokeIds` set and skip `toJson()` for already-persisted strokes.

**Load path:** deserialize objects + strokes off the main thread, then hand the in-memory lists to the view on the main thread (`loadStrokesWithBitmap(strokes, null, template)`) which records the committed `RenderNode` (see the render model below). No bitmap is rasterized at load. Stroke JSON parse is the dominant cost, so it is prefetched and cached (see "Neighbor prefetch cache").

**Erase path:** `LiveStroke.boundingBox: RectF` pre-computed at creation; `eraseAtPath` builds an AABB and rejects non-intersecting strokes in O(4 floats). `throttledEraseRedraw()` redraws at most once per 60ms; `finalizeEraseRedraw()` forces one clean redraw on gesture end before `handwritingRepaint`.

---

## Committed-Content Render Model (RenderNode)

Committed page content is drawn through a hardware `RenderNode` — the native equivalent of the
Flutter port's retained Skia layer (API 29+; our `minSdk` is 29). There is **no** on-screen render
bitmap and **no** on-load snapshot fast-path. Page load is: read objects + strokes from DB → record
the node → draw.

**The node (`committedNode = RenderNode("committed")`, both engines):**
- `redrawCanvas()` records it: `committedNode.setPosition(0,0,w,h)` → `beginRecording(w,h)` →
  `drawCommittedContent(rc)` → `endRecording()` → `invalidate()`. Sized in `onSizeChanged`.
  (`GenericNotebookView` calls the recording from `commitActiveStroke` / its own `redrawCanvas`.)
- `drawCommittedContent(canvas)` is the extracted draw routine: white → template → headings → text →
  lines → shapes → links → sticky → strokes. It is used **both** to record the node and as the
  software fallback below.
- `onDraw` branches by canvas type:
  `if (canvas.isHardwareAccelerated && committedNode.hasDisplayList()) canvas.drawRenderNode(committedNode) else drawCommittedContent(canvas)`.
  A `RenderNode` can only be drawn on a hardware canvas; the software branch keeps the Onyx
  `handwritingRepaint` panel-capture path correct (it re-draws the view through a software canvas).
- The node is re-recorded only when committed content actually changes (stroke commit, erase finalize,
  template change, load, undo/redo). During active writing only `invalidate()` fires — live ink is the
  Onyx overlay, and `onDraw` just re-blits the cached node (a GPU texture blit, no per-stroke path
  re-tessellation). `releaseResources()` calls `committedNode.discardDisplayList()`.

**Page-turn hold:** `clearForPageLoad()` (override) clears the in-memory lists and disables render but
does **not** re-record the node or refresh — the outgoing page stays visible until the incoming page's
`loadStrokesWithBitmap(...)` records the new node, so there is no blank flash between pages. Nav sites
call `clearForPageLoad()` instead of `eraseAll()`.

### Library-grid cover (index only — no per-page snapshot)

There is **no** per-page `data.snapshot` field anymore. The only snapshot is the **notebook's
library-grid cover**, stored in the global index (`NotebookObject.snapshot` in `notesprout.db`), not
in the `.soil`. `captureSnapshot()` (both engines) builds a self-contained bitmap of the current page
independent of any render layer; it is called **once, at notebook close** and written to the index via
`sealNotebook → cacheSnapshotIfAllowed(nbId, snapshot)` (unencrypted notebooks only — encrypted ones
never cache page content in the plaintext index).

**When captured:** close/back only (synchronously in `closeNotebook()` on the main thread, before
`sealNotebook()` dispatches to IO). `captureSnapshot()` returns `null` if content is empty or the view
isn't laid out (w=0/h=0). **NOT** on navigation, `eraseAll()`, or page delete.
**Critical:** `onWindowFocusChanged(false)` fires AFTER `finish()` (`soilDatabase` already null), so
`closeNotebook()` must capture the cover itself — never rely on a focus-loss hook.

**Page-index / link-picker thumbnails** are rendered **on demand** from page content
(`NotebookExporter.renderPageThumbnail`, only for the visible pagination page), not from any stored
snapshot. Three things keep this fast: (1) each thumbnail rasterizes **directly at card scale** via
`renderPageBitmap`'s `renderScale` (the canvas + template decode are scaled down, so a heavy page
draws ~scale² fewer pixels than a full render + downscale); (2) the visible grid page renders across a
**bounded worker pool** (up to `availableProcessors()`, capped at 4) sharing one Room/WAL connection,
each worker posting its bitmap to Main as it finishes so cards fill in progressively; (3) thumbnails
use the **lean stroke parse** (`LiveStroke.fromPointsJson`, points-only) since the thumbnail renderer
draws plain fixed-width black paths and never reads pressure/tilt/color/width. Results are held in a
per-screen `thumbnailCache` (keyed by page id) invalidated wholesale on content/order reload or a
card-size change; off-screen entries are evicted (recycled) so memory stays bounded to one grid page.

**Cleanup:** `NotebookCompactor` (runs on every seal, incl. encrypted, and the manual sweep) strips
any legacy `data.snapshot` from page rows and hard-deletes legacy `type='cover'` rows.

### Neighbor prefetch cache (`NotebookActivity`)

Stroke JSON parse (~260–480ms on heavy pages) is the dominant load cost, so it is cached and
prefetched:
- `strokeCache` — LRU `LinkedHashMap` (access-order, bounded to `MAX_CACHED_PAGES = 6`), keyed by
  `pageId`, guarded by `strokeCacheLock`. Each entry is `CachedStrokes(strokes, version)`.
- **Version = `NotebookDao.getMaxContentUpdatedAt(layerId)`** — `SELECT MAX(updatedAt)` with **no**
  `deletedAt IS NULL` filter, so soft-deleted (erased) strokes bump it. A ~10ms query, so the cache
  is self-invalidating: any edit changes the version and the stale entry is discarded.
- `deserializeStrokesFromDb(db)` — cache lookup validated inline against the current version; a hit
  skips the parse entirely.
- `parseStrokesForLayer(db, layerId)` — parses in parallel chunks across `Dispatchers.Default` when
  the row count ≥ `PARALLEL_PARSE_THRESHOLD = 300`. `parseStrokeRow` takes a lean JSON path
  (`LiveStroke.fromPointsJson`) when the row has no `pressure`/`tilt` fields.
- `prefetchNeighbors()` — after a page displays, parses N-1 / N+1 into `strokeCache` (stale-while-
  revalidate) so the next turn is a cache hit.

Navigation captures **no** snapshot — the library-grid cover is captured only at notebook close (see
above), so page turns do zero PNG-encode work.

---

## Undo/Redo System

- Session-scoped (not persisted across process death)
- `history/UndoRedoAction.kt` — sealed class: stroke add/erase, page add/delete/clear/copy/paste/move, lasso erase/cut/delete/paste/move, heading create/remove/text-edit, text insert/edit/remove/convert, **scribble erase**
- `history/UndoRedoManager.kt` — `undoStack` / `redoStack` as `ArrayDeque`. Redo stack cleared on any new user action.

**Cross-page actions:** Never call `saveAndSwitchPage()` — it calls `eraseAll()` which wipes in-memory strokes. Use the two-phase approach: capture the leaving page's cover snapshot inline (dirty-gated) → navigate → load from DB → apply the action → re-record the committed node.

**Same-page stroke path:** Never calls `eraseAll()`. Updates the in-memory stroke list directly and re-records the committed node via `loadStrokesWithBitmap(strokes, null, currentTemplateBitmap)` (`NotebookActivity` field set in `displayPage()`). Keep `persistedStrokeIds` in sync.

---

## Template System

Templates live in **two layers**:

1. **The library** — reusable templates organized in nestable folders, stored as first-class objects
   in the **global index** (`notesprout.db`). This is the user's permanent collection, independent of
   any notebook. See the Templates subsection of [`data-architecture.md`](data-architecture.md) for
   the object model (`template` / `template_folder` types, `TemplateObject` payload, base64-in-`data`).
2. **Applied templates** — when a library template is used, it is **copied into the notebook's `.soil`**
   as a `type = "template"` row. A page references its active template by UUID via `data.template`.
   `parseTemplateId(data)` reads `data.template` from a page row (empty = Blank). This layer is
   unchanged from before the global-index migration; deleting a library template never affects
   notebooks that already used it.

`.soil` template rows keep `data` JSON `{ "width", "height", "name", "image" (base64) }` (`TemplateData`).
The library `TemplateObject` differs: the **name lives in `ObjectEntity.name`**, not the JSON.

**TemplateBrowserActivity** — one full-screen Activity (`Theme.Notesprout`, no immersive mode) drives
all contexts via `EXTRA_MODE`:
- `MODE_MANAGE` — launched from the MainActivity toolbar (`btnTemplates`). Full management: browse,
  import PNGs, new folder, long-press action sheet (rename / copy / move / delete / pin-unpin /
  **export**, with self-descendant + conflict guards), search, sort. No selection result.
- `MODE_PICK` — selection. Returns `RESULT_TEMPLATE_ID` (`""` = Blank, else a library `TemplateObject`
  id). With `EXTRA_COLLECT_NAME=true` it also shows a name field + CREATE button (the New Notebook
  flow) and returns `RESULT_NOTEBOOK_NAME`. Long-press management is omitted in PICK.
- `MODE_SAVE_TARGET` — cross-launched from the page-index "Save as Template" export option. Behaves
  like the move/copy destination picker (folders only, breadcrumb nav, no template cards) with a
  "Choose a folder" confirm bar (**Save Here** + **Cancel**, reuses `pickerToolbar`). Save Here prompts
  for a name (default = `EXTRA_SAVE_DEFAULT_NAME`, dup-checked with ` (2)`… like Import), reads the PNG
  at `EXTRA_SAVE_SOURCE_PATH`, and calls `repository.createTemplate` itself; returns only `RESULT_OK` /
  cancel. New-Folder stays available; everything else is hidden. (See `mainactivity-and-recents.md`
  for the host export sheet. Creation ≠ use → no recents recording.)

**Top toolbar (S11):** Search / Sort / Pinned / Recents live in a right-aligned cluster in the
`breadcrumbBar` (mirrors MainActivity). New-Folder + Import stay in the bottom bar with pagination.
`btnRecents` is GONE in MANAGE (selector-only), visible in PICK; `btnPinned` is visible in both.

**Pinned & Recents views** — alternate flat, paginated card grids (templates only, no folders, no
breadcrumb nav) toggled by the top-bar buttons, reusing the existing grid + pagination:
- **Pinned** (`enterPinnedView`/`exitPinnedView`, `isPinnedView`) — `repository.getPinnedTemplates()`.
  Available in **MANAGE and PICK**. Long-press management (incl. Pin/Unpin) applies in MANAGE only.
- **Recents** (`enterRecentsView`/`exitRecentsView`, `isRecentsView`) — `TemplateRecentsManager.resolve`,
  newest-first. **PICK-only.**
- Pinned / Recents / Search are mutually exclusive (entering one exits the others). Tap behavior matches
  the root browse grid for the mode: `collectName` → set pending + mark + stay (CREATE confirms); plain
  PICK → select + `finish()`. The **Blank** card belongs to root browse only (never pinned/recents).
- `applyOverlayViewUI` is the single authority handling both overlay toolbars + the action-button
  cluster. See `data-architecture.md` for the pinned-templates list + recents store.

**Export Template (S13, MANAGE):** the template long-press menu has an **"Export Template"** action
(after Rename, before Pin) → `showExportTemplateChoice` → an "Export template" `AlertDialog` with
**Save to device** (`CreateDocument` launcher) and **Share** (`ACTION_SEND` chooser). `writeTemplatePng`
decodes `TemplateObject.image` and writes the **raw bytes** (no re-encode) to
`cacheDir/exported_pngs/<name>.png`; reuses the `${applicationId}.fileprovider` authority. Single action
opening a chooser (the app-wide export pattern) — not two menu items.

Thumbnails decode-sampled from the full base64 on `Dispatchers.IO`, cached in-memory keyed by
`"${id}:${updatedAt}"`. Adaptive grid — 4 columns ≥1500px, else 2. Template cells use `shape_bordered`
+ 1dp inset (never `clipToOutline` — it clips the border at rounded corners).

**In-notebook `TemplateDialog`** (slim, single-view — owned by `NotebookActivity`, which holds the live
`.soil` connection): **Blank** + a quick-pick grid of templates **already used in this notebook**
(`db.notebookDao().getTemplatesSorted()`) + a **"Browse Templates…"** button that launches the browser
in `MODE_PICK`. On a library pick, `NotebookActivity.insertLibraryTemplateIntoSoil(id, db)` copies the
`TemplateObject` into the open `.soil` as a new `type="template"` row, then
`applyTemplateToCurrentPage(soilRowId, bitmap)`. The browser Activity **never opens a `.soil`** —
avoids cross-Activity WAL/sidecar risk.

**Template inheritance on new page:** `addPage()` reads the current page **fresh from DB** via
`dao.getObjectById(currentPageId)`. Do NOT read from the stale in-memory `pages` list — it is not
refreshed after `applyTemplateToCurrentPage()` writes to DB.
