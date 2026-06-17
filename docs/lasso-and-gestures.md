# Lasso & Pen Gestures

> Referenced from `CLAUDE.md`. Covers scribble-to-erase, smart lasso, snap-to-guide, and
> align & distribute. All are always-active in pen mode (no toggle) unless noted.

## Scribble to Erase

Always-active in pen mode (no toggle). A rapid scribble across existing content erases it silently — no confirmation.

### Detection heuristic (both drawing engines)

A completed stroke is a **scribble candidate** when BOTH hold:
1. **High ink density:** `pathLength / boundingBoxDiagonal ≥ SCRIBBLE_DENSITY_RATIO` (4.0). Both values are computed from the stroke's point array.
2. **Zigzag tightness:** at least `SCRIBBLE_MIN_DIRECTION_REVERSALS` (3) direction reversals on the noise-filtered path (consecutive movement vectors whose dot product is negative). Points < 2 px apart are collapsed before counting.

Detection runs at **pen lift** (`onEndRawDrawing` on Onyx, `ACTION_UP` on Generic), after the full stroke is captured. On Onyx, all points from the gesture are accumulated in `currentGesturePoints / currentGestureStrokeIds` (cleared on `onBeginRawDrawing`). On Generic, `commitActiveStroke()` supplies the last stroke.

### Hit-testing

For a scribble candidate, a background thread runs `scribbleHitTest` against all non-deleted content on the layer:

- **Strokes:** AABB pre-filter (`SCRIBBLE_STROKE_TOUCH_RADIUS_DP` expansion), then per-stroke-point to nearest scribble segment distance ≤ `SCRIBBLE_STROKE_TOUCH_RADIUS_DP` px. Whole-stroke erase on any touch (matches eraser tool behavior).
- **Headings / text objects:** `scribblePathPenetration` sums the length of all scribble segments with at least one endpoint inside the object's bounding box. The object is hit only when this sum ≥ `SCRIBBLE_BBOX_PENETRATION_DP` px, preventing corner-grazes from triggering an erase.

If the hit test returns **empty** the scribble is treated as a normal stroke and `onPenLifted` fires. If any objects are hit, `onScribbleEraseComplete` fires instead.

### Constants (`notebook/NotebookConstants.kt`)

| Constant | Default | Purpose |
|---|---|---|
| `SCRIBBLE_DENSITY_RATIO` | `4.0f` | Minimum pathLength / diagonal ratio |
| `SCRIBBLE_MIN_DIRECTION_REVERSALS` | `3` | Minimum zigzag reversals |
| `SCRIBBLE_BBOX_PENETRATION_DP` | `14f` | Minimum travel inside heading/text bbox (dp) |
| `SCRIBBLE_STROKE_TOUCH_RADIUS_DP` | `8f` | Proximity radius for stroke-to-stroke touch (dp) |

### Undo action: `ScribbleErased`

`history/UndoRedoAction.ScribbleErased(scribbleStrokeIds, erasedObjectIds, pageId, layerId, deletedAt, headingIds, headings, textIds, textObjects)`.

- `scribbleStrokeIds` — IDs of the gesture stroke row(s); saved to DB then immediately soft-deleted.
- `erasedObjectIds` — IDs of all erased content (strokes, headings, text objects); does NOT include scribble strokes.
- `headings / textObjects` — full render data for in-memory restoration on undo without a DB round-trip.
- **Undo:** restore all `scribbleStrokeIds` + `erasedObjectIds` (clear `deletedAt`); rebuild canvas. Scribble stroke reappears along with erased content.
- **Redo:** re-soft-delete all `scribbleStrokeIds` + `erasedObjectIds`.

Cross-page undo/redo uses the same two-phase approach as `LassoErased`.

### EPD / overlay handoff (OnyxNotebookView)

When hit test confirms a scribble erase (fires on main thread from `post {}`):
1. `touchHelper.setRawDrawingRenderEnabled(false)` + `invalidate()` — releases hardware buffer before DB/bitmap work.
2. Activity (`onScribbleEraseComplete`): calls `saveStrokes(db)` to insert scribble stroke rows, then soft-deletes scribble + erased objects in a transaction, invalidates page snapshot.
3. Rebuilds render bitmap off-thread (`buildRenderBitmap`) without scribble or erased objects.
4. `loadStrokesWithBitmap` swaps bitmap, runs `handwritingRepaint`, re-enables raw drawing — identical to the `eraseAll` / `setTemplate` handoff sequence.

---

## Smart Lasso

Always-active in pen mode (no toggle). A quick, closed pen gesture (circle or loop) around existing content enters lasso selection mode with all enclosed objects selected — exactly as if the user had switched to the lasso tool and drawn that path manually.

### Detection gate order at pen lift

All three checks run in the same background thread, in priority order:
1. **Smart lasso** — fast + closed + encloses ≥1 object → enters lasso mode with selection.
2. **Scribble-to-erase** — dense + zigzag + crosses ≥1 object → erases hit objects.
3. **Normal stroke** — fire `onPenLifted`; activity saves to DB.

### Detection heuristics (both drawing engines)

A completed stroke is a **smart-lasso candidate** when ALL THREE hold:
1. **Velocity:** `pathLength / durationMs ≥ SMART_LASSO_MIN_VELOCITY` (0.5 px/ms). `durationMs` is from `beginRawDrawingTimeMs` on Onyx; from `strokeStartTimeMs` (set on `ACTION_DOWN`) on Generic.
2. **Closure:** distance from first to last point of the gesture ≤ `SMART_LASSO_CLOSURE_DISTANCE_DP` (50 dp).
3. **Winding:** the path accumulates ≥ `SMART_LASSO_MIN_WINDING_DEGREES` (270°) of signed angular change around the gesture's centroid. This is the circularity gate — letters and open arcs never wind 270°+ around a central point; loops always do. Computed by summing per-step `atan2` deltas (unwrapped to [−π, π]) over all gesture points.

If all three conditions hold, `runLassoHitTest` is called against all non-deleted content on the layer (strokes, headings, text objects — same function used by the lasso eraser). If ≥1 object is hit, smart lasso triggers. If 0 objects are hit (e.g. writing the letter "o" or "0" over empty space), falls through to the scribble check as a normal stroke.

### Constants (`notebook/NotebookConstants.kt`)

| Constant | Default | Purpose |
|---|---|---|
| `SMART_LASSO_MIN_VELOCITY` | `0.5f` | Minimum pathLength / durationMs (px/ms) |
| `SMART_LASSO_CLOSURE_DISTANCE_DP` | `50f` | Maximum first-to-last distance for "closed" path (dp) |
| `SMART_LASSO_MIN_WINDING_DEGREES` | `270f` | Minimum angular sweep around centroid for circularity (degrees) |

### On trigger

1. **Discard gesture stroke:** removed from in-memory stroke list before the callback fires. Never saved to DB, never added to `persistedStrokeIds`.
2. **EPD overlay released** (Onyx only): `setRawDrawingRenderEnabled(false)` + `invalidate()` before the callback.
3. **Activity enters lasso mode:** `enterLassoMode()` is called first, making the mode switch persistent. `drawingView.isLassoMode = true`, `btnLasso.isSelected = true`, `btnPen.isSelected = false`.
4. **Selection state set:** `selectedObjectIds` and `drawingView.lassoSelectedIds` populated with hit IDs.
5. **Bitmap rebuilt off-thread** via `buildRenderBitmap` + `loadStrokesWithBitmap` to drop the gesture circle from the render bitmap before the EPD repaint.
6. **Selection overlay + floating toolbar shown** via `setLassoOverlay(null, paddedBounds)` + `updateFloatingSelectionToolbar(paddedBounds)`.

### Persistent mode switch

The user remains in lasso mode after any lasso action (move, copy, delete, etc.) until the pen tool is explicitly tapped — the same behavior as a toolbar-initiated lasso selection. Smart lasso does NOT auto-return to pen mode.

### Implementation files

- `notebook/NotebookConstants.kt` — constants
- `notebook/OnyxNotebookView.kt` — `checkAndDispatchGesture()`, `isSmartLassoCandidate()`; replaces the former `checkAndRunScribble()`
- `notebook/GenericNotebookView.kt` — same pattern; `strokeStartTimeMs` tracked on `ACTION_DOWN`
- `notebook/NotebookView.kt` — `onSmartLassoComplete` callback declaration
- `NotebookActivity.kt` — `drawingView.onSmartLassoComplete` wiring

---

## Snap-to-Guide System

User-toggleable during lasso drag. A snap toggle button (`btnSnapToggle`) sits at the end of the floating selection toolbar, always visible. Default: **off**. State persists across app loads via `SnapPreferences` (`notesprout_snap_prefs` / `snap_enabled`).

- Off → icon: `ic_snap_on` (Tabler `template`)
- On → icon: `ic_snap_off` (Tabler `template-off`)

When enabled, a dashed guideline appears and the object snaps to the nearest snap region during drag. Dragging past the threshold releases the snap — no hard clamping. When disabled, drag moves freely with no guidelines.

### Snap Regions

Two guide types — vertical (dashed vertical line, snaps X) and horizontal (dashed horizontal line, snaps Y):

**Page guides:**

| Guide | Position |
|---|---|
| Left / Right edge | x = 0 / x = pageWidth |
| Left / Right margin | x = SNAP_MARGIN_DP / x = pageWidth − SNAP_MARGIN_DP |
| Vertical center | x = pageWidth / 2 |
| Top / Bottom edge | y = 0 / y = pageHeight |
| Top / Bottom margin | y = SNAP_MARGIN_DP / y = pageHeight − SNAP_MARGIN_DP |
| Horizontal center | y = pageHeight / 2 |

**Object guides** (per non-selected heading or text object on the layer — strokes are never snap targets):

| Guide | Position |
|---|---|
| Left proximity | x = target.left − SNAP_MARGIN_DP |
| Left edge | x = target.left |
| Center X | x = target.centerX() |
| Right edge | x = target.right |
| Right proximity | x = target.right + SNAP_MARGIN_DP |
| Top proximity | y = target.top − SNAP_MARGIN_DP |
| Top edge | y = target.top |
| Center Y | y = target.centerY() |
| Bottom edge | y = target.bottom |
| Bottom proximity | y = target.bottom + SNAP_MARGIN_DP |

`SNAP_MARGIN_DP = 44f` — matches the standard toolbar button size. The proximity guides enable equal-spacing alignment: dragging one text object below another snaps it to sit exactly one margin-width from the neighbor's edge.

### Snap Logic

Each selection bbox has 3 anchors per axis (left/center-x/right for X; top/center-y/bottom for Y). During drag, the nearest (anchor, guide) pair within `SNAP_THRESHOLD_DP` (20dp) wins per axis. X and Y snap independently. The raw drag offset is adjusted by `guide − anchor` to pull the anchor flush to the guide.

Object snap targets are captured into `snapObjectTargets: List<RectF>` at drag start (non-selected headings + text bounding boxes) and cleared on commit/cancel. Strokes are excluded from target collection.

### Constants (`notebook/NotebookConstants.kt`)

| Constant | Default | Purpose |
|---|---|---|
| `SNAP_MARGIN_DP` | `44f` | Margin guide inset from each page edge; also the object proximity gap (dp) |
| `SNAP_THRESHOLD_DP` | `20f` | Max distance for snap to engage (dp) |

### Implementation Files

- `notebook/SnapGuide.kt` — `sealed class SnapGuide { Vertical(x), Horizontal(y) }` + `SnapResult(snappedDx, snappedDy, activeGuides)`
- `notebook/SnapEngine.kt` — `computeSnap(originalBox, rawDx, rawDy, pageWidth, pageHeight, marginPx, thresholdPx, objectTargets): SnapResult`
- Both drawing views — `activeSnapGuides: List<SnapGuide>` + `snapObjectTargets: List<RectF>` fields; `snapGuidePaint` (1dp, black, `DashPathEffect([12dp, 6dp])`); `drawSnapGuides(canvas)` called in the drag layer of `onDraw` after the selection box, before `return`; both cleared on all drag commit/cancel/mode-exit paths alongside `dragDx = 0f`

---

## Align & Distribute

Available in the floating selection toolbar when ≥2 non-stroke objects (headings and/or text objects) are selected and no strokes are in the selection. Two buttons appear at the end of the toolbar:

| Button | Icon | Behavior |
|---|---|---|
| Align left + distribute vertically | `ic_box_align_left` | Snaps all left edges to the selection bbox left; redistributes objects top-to-bottom with equal vertical gaps. |
| Align top + distribute horizontally | `ic_box_align_top` | Snaps all top edges to the selection bbox top; redistributes objects left-to-right with equal horizontal gaps. |

**"Left" and "top" are relative to the current selection bounding box, not the page.**

### Implementation (`NotebookActivity.performAlign`)

1. Capture original `HeadingStroke` and `TextRender` lists from the selection.
2. Sort by center-Y (vertical) or center-X (horizontal).
3. Compute new bounding boxes: first object anchored to `selBbox.left`/`selBbox.top`; last object's far edge at `selBbox.right`/`selBbox.bottom`; equal gaps between.
4. Persist via `updateHeadingData` in a single `db.withTransaction {}`; call `invalidatePageSnapshot` after.
5. Update in-memory heading/text lists, rebuild render bitmap off-thread, swap via `loadStrokesWithBitmap`.
6. Refresh lasso overlay and floating toolbar position.
7. Push `UndoRedoAction.StrokesMoved` with empty stroke lists and the before/after heading + text object snapshots — undo/redo is handled by the existing `StrokesMoved` path at no extra cost.

**Visibility condition:** `selStrokes.isEmpty() && (selHeadings.size + selTextObjects.size) >= 2` — computed fresh in `updateFloatingSelectionToolbar` alongside the existing heading/stroke visibility checks.
