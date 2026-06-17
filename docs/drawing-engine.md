# Drawing Engine Architecture

> Referenced from `CLAUDE.md`. Covers the two drawing engines, EPD/overlay rules, tool-state
> invariants, performance rules, and the page snapshot system.

## Files

- `notebook/NotebookView.kt` — interface for both engines; all drawing, lasso, heading, snapshot ops
- `notebook/OnyxNotebookView.kt` — BOOX: TouchHelper, RawInputCallback. `onPenLifted` fires on `onEndRawDrawing`. `onBeginRawDrawing` re-enables render guarded by `!isEraserMode`.
- `notebook/GenericNotebookView.kt` — standard Canvas: two-layer Bitmap, stylus-only (`TOOL_TYPE_STYLUS` + `TOOL_TYPE_ERASER`), historical point capture. `onPenLifted` fires on `ACTION_UP`.
- `NotebookActivity.kt` — fullscreen immersive, multi-page state, incremental save via `insertOrIgnore`. One-finger deliberate swipe for page navigation (three guards: distance ≥50% screen width, velocity ≥1.5× fling threshold, horizontal dominance). Two-finger swipe left/right inserts a page after/before current and navigates to it (same guards).
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

**Overlay lifetime:**
- The overlay ("writing mode") stays active indefinitely while the user writes. No idle-release timer.
- Legitimate handoff points: `setEraserMode(true)`, `eraseAll()`, `setTemplate()`, `loadStrokesWithBitmap()`, `onWindowFocusChanged(false)`, toolbar finger touch.
- `onPenLifted` is a DB-save trigger only — does NOT touch the overlay.

**Toolbar touch → overlay release:**
- Any finger `ACTION_DOWN` within `drawingToolbar.bottom` (intercepted in `NotebookActivity.dispatchTouchEvent`) calls `drawingView.releaseRender()` before the child button handles the event.
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

## Tool-State Invariants (OnyxNotebookView)

When a Dialog is shown over NotebookActivity, focus changes trigger `onWindowFocusChanged(false)` → `setRawDrawingEnabled(false)`. On return: `onWindowFocusChanged(true)` → `openRawDrawing()`. Also triggered by `onResume()` → `enableDrawing()`.

| Active tool | `setRawDrawingEnabled` | `setRawDrawingRenderEnabled` |
|---|---|---|
| Pen | `true` | `true` (SDK manages) |
| Eraser | `true` | `false` (prevents phantom pen strokes on overlay) |
| Lasso / Lasso Eraser | `false` | n/a |
| Text placement | `false` | n/a |

`openRawDrawing()` and `enableDrawing()` must guard `setRawDrawingEnabled(true)` with `!isLassoMode && !isLassoEraserMode && !isTextPlacementMode`. If the guard passes and `isEraserMode` is true, immediately follow with `setRawDrawingRenderEnabled(false)`. Failing this causes phantom pen strokes on the EPD overlay — they look real but vanish on the next EPD refresh.

## Performance Rules (Do Not Regress)

**Save path:** Wrap INSERT OR IGNORE loops in `db.withTransaction {}`; track `persistedStrokeIds` set and skip `toJson()` for already-persisted strokes.

**Load path:** `buildRenderBitmap()` on `Dispatchers.IO` — pre-builds white → template → strokes off the main thread; `loadStrokesWithBitmap()` on main thread swaps the pre-built bitmap (~12ms cost).

**Erase path:** `LiveStroke.boundingBox: RectF` pre-computed at creation; `eraseAtPath` builds an AABB and rejects non-intersecting strokes in O(4 floats). `throttledEraseRedraw()` redraws at most once per 60ms; `finalizeEraseRedraw()` forces one clean redraw on gesture end before `handwritingRepaint`.

---

## Page Snapshot System

Each page row's `data` JSON carries an optional `"snapshot"` field — a base64-encoded transparent-background PNG of all content (no schema change).

Rendering order: white → template → snapshot PNG → new content drawn this session.

**Snapshot rules:**
- Transparent background only — do NOT fill white or draw the template.
- `captureSnapshot()` returns `null` if strokes/headings/textObjects are all empty, or view isn't laid out (w=0/h=0).

**When snapshots are captured:**
- `setEraserMode(true)` — BEFORE `isEraserActive = true`
- `setTemplate(bitmap)` — BEFORE `templateBitmap = bitmap`
- `onWindowFocusChanged(false)` — backgrounded or dialog overlay
- Page navigation — BEFORE `eraseAll()`
- Close/back — synchronously in `closeNotebook()` on the main thread BEFORE `sealNotebook()` is dispatched to IO

**Critical:** `onWindowFocusChanged(false)` fires AFTER `finish()` — `soilDatabase` is already null. Any path that calls `closeNotebook()` must capture the snapshot itself. Never rely on `onWindowFocusChanged` as the close-path snapshot trigger.

**NOT on:** user-initiated `eraseAll()` or page delete — content is being discarded.

**Stale detection:** `NotebookDao.getMaxStrokeUpdatedAt(layerId)` — `SELECT MAX(updatedAt)` with **no** `deletedAt IS NULL` filter. Soft-deleted strokes have `updatedAt = deletedAt`, so erasures are detected as changes. If `maxStroke > page.updatedAt`, snapshot is stale. `persistSnapshot()` bumps `page.updatedAt`.

**Two-phase page load (`NotebookActivity.loadCurrentPage`):**
1. `setupPageIds(db)` — resolves `currentPageId` / `currentLayerId`
2. `loadPageTemplateFromDb(db)` — decodes template bitmap (or null for blank)
3. `tryLoadSnapshotBitmap(db, templateBitmap)` — staleness check + composite. Returns null on miss.
4. **Fast path** (hit): display composite immediately; deserialize strokes in background via `setStrokeListSilently()`.
5. **Full path** (miss): deserialize + `buildRenderBitmap` off-thread; capture and persist snapshot for next load.

---

## Undo/Redo System

- Session-scoped (not persisted across process death)
- `history/UndoRedoAction.kt` — sealed class: stroke add/erase, page add/delete/clear/copy/paste/move, lasso erase/cut/delete/paste/move, heading create/remove/text-edit, text insert/edit/remove/convert, **scribble erase**
- `history/UndoRedoManager.kt` — `undoStack` / `redoStack` as `ArrayDeque`. Redo stack cleared on any new user action.

**Cross-page actions:** Never call `saveAndSwitchPage()` — it calls `eraseAll()` which wipes in-memory strokes. Use the two-phase approach: save/snapshot the leaving page inline → navigate → load from DB → apply the action → rebuild bitmap.

**Same-page stroke path:** Never calls `eraseAll()`. Updates in-memory stroke list directly, rebuilds bitmap off-thread with `currentTemplateBitmap` (`NotebookActivity` field set in `displayPage()`), swaps via `loadStrokesWithBitmap`. Keep `persistedStrokeIds` in sync.

---

## Template System

- Templates are `type = "template"` rows stored in the `.soil` notebook database
- Template PNG files stored in `getExternalFilesDir("Templates")` — imported via `ACTION_OPEN_DOCUMENT`
- `data` JSON: `{ "width": Int, "height": Int, "name": String, "image": String (base64) }`
- `parseTemplateId(data)` reads `data.template` from a page row to get the active template UUID (empty = Blank)

**TemplateDialog:** Two-tab (All / Notebook), adaptive grid — 4 columns on NA5C (≥1500px), 2 on P2P/GC7. `thumbFrame` uses `shape_bordered` + **1dp padding** to inset the `ImageView` so it cannot render over the border stroke. Do NOT use `clipToOutline` — it clips the border itself at rounded corners.

- **Title bar:** custom title view (`setCustomTitle`) — "Template" label (bold 18sp, weight=1) + "Import…" bordered button on the right. The import button lives here (not inside the scrollable content area) so it never causes clipping at the bottom of the dialog.
- **Delete from All tab:** each non-Blank file item shows an `ic_trash` (Tabler `trash`) button overlaid at the top-right corner of its cell (32dp, 6dp internal padding, 6dp margin from edge). Tapping it shows a confirmation AlertDialog; confirming deletes the PNG from `getExternalFilesDir("Templates")`, rescans, and repopulates the All tab without closing the dialog.

**Template inheritance on new page:** `addPage()` reads the current page **fresh from DB** via `dao.getObjectById(currentPageId)`. Do NOT read from the stale in-memory `pages` list — it is not refreshed after `applyTemplateToCurrentPage()` writes to DB.
