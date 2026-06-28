# Shape Objects

Shape objects are regularized geometric primitives drawn by the user with a stylus and recognized
automatically via a dwell-then-lift trigger. They are first-class `BaseObject` citizens: stored in
the `.soil` notebook database, fully lasso-selectable/moveable/eraseable, exported to PDF, copyable
across the clipboard (notebook ↔ scratch pad ↔ sticky note editor), and undoable at every step.

---

## Data Model

### `ShapeType` (`data/ShapeType.kt`)

Eleven recognized shapes:

| Value | Description |
|---|---|
| `RECTANGLE` | Axis-aligned rectangle; `aspectLocked=true + w==h` → square |
| `ELLIPSE` | Oval; `aspectLocked=true + w==h` → circle |
| `TRIANGLE` | Isosceles, apex up (unrotated); `rotationDeg` points the apex |
| `DIAMOND` | Rhombus; vertices at bbox edge midpoints |
| `TRAPEZOID` | Isosceles; narrow top (0.6×w), full-width bottom |
| `PENTAGON` | Regular, point up |
| `HEXAGON` | Regular, flat top |
| `STAR` | N-point (see `pointCount`); outer/inner radius ratio = `STAR_INNER_RATIO` (0.5) |
| `ARCH` | Semicircle top + straight sides + flat closed base |
| `LINE` | Open horizontal segment (possibly rotated) |
| `ARROW` | Open segment + V arrowhead at the far end |

### `ShapeObject` (`data/ShapeObject.kt`)

The persisted form — stored as a `type="shape"` row in the notebook table's `data` column (JSON via
`kotlinx.serialization`).

| Field | Notes |
|---|---|
| `type` | `ShapeType` enum value |
| `centerX`, `centerY` | Absolute page/canvas coordinates of the geometric center |
| `width`, `height` | Extents of the **oriented (unrotated) box** — the shape's natural local size |
| `rotationDeg` | Clockwise rotation in degrees around `(centerX, centerY)` |
| `strokeWidthDp` | Inherited from the drawn stroke; stored in dp for density independence |
| `aspectLocked` | Set by the recognizer when `w ≈ h`; preserved through transform mode |
| `pointCount` | `STAR` only: number of outer tips (typically 5–12); ignored for all other types |

### `ShapeRender` (`data/ShapeRender.kt`)

The render-time representation built at page-load from every `type="shape"` row. Not stored in the
database, but `@Serializable` so it travels in undo/redo actions and the clipboard.

Key difference from `ShapeObject`: `ShapeRender` carries a computed `boundingBox` — the **AABB** of
the rotated outline, inflated by `max(strokeWidthPx/2, 4dp)`. The AABB is what the lasso hit-test,
erase scan, and clipboard bounding logic operate against.

`ShapeRender.from(id, obj, density)` builds the render by calling `ShapeGeometry.pathFor()` on a
provisional render, computing the path bounds, inflating them, and returning the final render.

`ShapeRender.toShapeObject(density)` converts back for persistence.

---

## Two Coordinate Concepts

- **Oriented box** (`width`, `height`, `rotationDeg`) — the shape's local geometry before rotation.
  `ShapeGeometry.pathFor()` builds the path in these local coordinates and then rotates it around
  `(centerX, centerY)` by `rotationDeg`.
- **AABB** (`boundingBox` in `ShapeRender`) — the axis-aligned bounding box of the **rotated**
  outline, inflated by the stroke width. Used for hit-testing, lasso intersection, erase scanning,
  and export clipping. A rotated rectangle's AABB is larger than its oriented box.

The transform controller (`ShapeTransformController`) always works in the **oriented box** coordinate
space and recomputes the AABB via `ShapeRender.from()` after each drag/resize step.

---

## Geometry (`ShapeGeometry.kt`)

`ShapeGeometry.pathFor(r: ShapeRender): Path` is a pure function that returns an absolute-coordinate
`android.graphics.Path` for drawing or measurement. It has no view state and is safe to call from
any thread (including the PDF exporter).

Each type has a dedicated path construction:
- `RECTANGLE` — four `lineTo` corners
- `ELLIPSE` — `addOval`
- `TRIANGLE` — apex at `(cx, top)`, base at bottom; rotated by `rotationDeg`
- `DIAMOND` — four midpoint vertices
- `TRAPEZOID` — top inset by 20% of width on each side
- `PENTAGON` / `HEXAGON` — regular polygon loop; HEXAGON starts at `−π/2 + π/6` for flat-top
- `STAR` — skip-pattern `(i*2)%n` to produce the crossing-line pentagram style
- `ARCH` — semicircle top + straight sides + flat base; degenerates to pure semicircle when height < half-width
- `LINE` — horizontal segment from `(L, cy)` to `(R, cy)`; rotated by `rotationDeg`
- `ARROW` — like `LINE` but with a two-arm arrowhead at the right endpoint; arms at ±150°

After construction a rotation matrix is applied around `(cx, cy)` if `rotationDeg ≠ 0`.

---

## Dwell Trigger and Gate Order

Shape recognition fires at **pen lift** via the gesture-detection pipeline in
`OnyxNotebookView` and `GenericNotebookView`. Gate order (first match wins):

```
Gate 0 — Shape dwell:    single stroke held still ≥ SHAPE_DWELL_MS (700 ms)
                         within SHAPE_DWELL_RADIUS_DP (6 dp) of the lift point
                         → feed stroke points to ShapeRecognizer on a background thread
                         → on success: emit onShapeRecognized, erase stroke, insert ShapeRender
Gate 1 — Smart lasso:    fast closed stroke (velocity + area gate)
Gate 2 — Scribble-erase: high density + direction reversals
Gate 3 — Normal stroke:  committed to the EPD layer as a drawn stroke
```

The dwell window is tracked per-point using raw stylus timestamps so that the batch-delivery
pattern on BOOX (all points arrive at once at pen-lift) doesn't collapse the dwell window to ~0 ms.
The stillness radius uses squared-distance comparison to avoid a `sqrt()` per point.

Only a **single-stroke** selection is fed to the recognizer. Multi-stroke shape assembly is a
deferred backlog item.

---

## Recognizer Pipeline (`ShapeRecognizer.kt`)

`ShapeRecognizer.recognize(points, density): Result?` — returns `null` when confidence is below
`SHAPE_MIN_CONFIDENCE` (0.55).

### Pre-flight
1. Reject strokes with fewer than 4 points.
2. Compute bounding box diagonal. Reject if `diag < SHAPE_MIN_DIAGONAL_DP * density` (24 dp).
3. Resample to 64 evenly-spaced points along path length.
4. Compute closedness: `dist(first, last) / diag`. Closed if `≤ SHAPE_CLOSURE_RATIO` (0.35).

### Open shape path
Shaft (first 80%) + tail (last 20%) split:
- Shaft straight (`maxPerpDev/len ≤ SHAPE_LINE_STRAIGHTNESS = 0.10`):
  - Tail has arrowhead signature → **ARROW** (confidence 0.75)
  - Overall stroke also straight → **LINE** (confidence 0.85)
  - Otherwise → **LINE** (confidence 0.62, covers bent shafts from pen wobble)
- Not straight: check for **ARCH** (angular sweep in range 0.75π–1.4π)
- RDP-based star detection (`tryRecognizeOpenStar`): N all-sharp corners drawn in skip order
- RDP-based arrow detection (`tryRdpArrow`): longest segment dominates (≥1.5×), sharp corner exists,
  backward segments adjacent to shaft endpoint

### Closed shape path
1. **Radial star** (`tryRecognizeStar`): alternating radial peaks and troughs → **STAR**
2. RDP simplification at `eps = SHAPE_RDP_EPS_RATIO * diag`; close-point deduplication; `pruneLinearCorners` (removes vertices with interior angle > 150°)
3. **Closed skip-pattern star** (`tryRecognizeClosedStar`): all RDP corners sharp → **STAR**
4. **Polygon by corner count** (`classifyByCorners`):
   - 3 corners → `classifyTriangle` (apex direction from smallest angle, snaps to cardinal)
   - 4 corners → `classifyQuad` (diamond → trapezoid → rectangle; trapezoid collapse if ratio < 0.15)
   - 5–9 corners → `classifyConvexPolygon` (angle average: < 116° → PENTAGON, ≥ 116° → HEXAGON)
5. **Fine-RDP confirmation for 5–9-corner polygons**: rerun at `eps/2`; if fine count ≤ coarse × 1.9 → polygon (flat sides); else fall through to ellipse
6. **Ellipse / circle**: multi-tier CV gate + ellipse-fit quality fallback (AABB major-axis projection)
7. Fall back to `polyResult` if ellipse tiers fail; `null` if all fail

### As-built constant deviations from original spec

| Constant | Spec | Actual | Reason |
|---|---|---|---|
| `SHAPE_RDP_EPS_RATIO` | 0.045 | **0.08** | Looser epsilon needed to reliably reduce regular polygon outlines to the correct corner count without over-segmenting pen wobble |
| `SHAPE_ELLIPSE_CV` | 0.12 | **0.18** | Raised to tolerate hand-drawn ellipses; regular pentagons/hexagons (CV ≈ 0.06–0.08) remain well below this threshold |
| `SHAPE_CLOSURE_RATIO` | 0.20 | **0.35** | Catches closed shapes where the pen lifts slightly away from the start point |
| `sidesAreStraight` polygon gate | not in spec | **added** | All polygon candidates (3–9 corners) pass through `sidesAreStraight()`: for each side, any resampled point inside the segment must have perpendicular deviation ≤ `SHAPE_LINE_STRAIGHTNESS` × side length. Circle arcs score 0.13–0.21 at the 4–7-corner counts RDP produces; true polygon sides score < 0.05. Replaced a fine-RDP corner-count ratio check (fine/coarse ≤ 1.9×) that failed because the circle ratio at `eps/2` is only ~1.4×, below any safe threshold |
| `classifyConvexPolygon` corner range | 5–6 only | **5–9** | Extended to absorb ±2 noise corners from a hand-drawn pentagon or hexagon |
| Trapezoid `widthRatio` threshold | ~0.50 | **0.65** | Raised so typical hand-drawn trapezoids (narrow edge at 55–75% of wide) are caught; rectangles rarely dip below 0.70 |

---

## Transform Mode (`ShapeTransformController.kt`)

Entered via `enterShapeTransformMode(shapeRender)` in the host activity; exited by tapping "Done"
(`btnShapeTransformDone`) or tapping outside the shape.

The controller renders an **oriented bounding box** (dashed, 1dp thick) around the shape plus:
- 8 resize handles at corners and edge midpoints (10 dp squares, 22 dp touch radius)
- A rotate knob 36 dp above the top-center handle

Touch routing in the drawing view:
- Hit-test on `onDown` classifies the grab: `NONE`, `BODY`, `ROTATE`, or one of 8 resize handles.
- `BODY` drag: translate `(centerX, centerY)`.
- `ROTATE` drag: compute angle from `(cx, cy)` to touch point; subtract grab-offset; snap within
  `SHAPE_ROTATE_SNAP_DEG` (5°) of 0/90/180/270.
- Resize handles: anchor at the opposite handle; compute new `width/height` from touch displacement;
  clamp to `SHAPE_MIN_SIZE_DP` (24 dp); respect `aspectLocked` if set.

The aspect-lock toggle (`btnShapeAspectLock`) cycles through shape-specific labels:
- `ELLIPSE`: "Circle" / "Oval"
- `RECTANGLE`: "Square" / "Rect"
- All others: "1:1" / "Free"

On transform end, `getBeforeRender()` + `getWorkingRender()` carry the before/after snapshots into
a `ShapeTransformed` undo action.

---

## Insert Shape Toolbar

The toolbar button `btnInsertShape` (icon `ic_convert_shape`) opens a secondary shape picker
(`shapeInsertToolbar`) anchored to the button. The picker contains one button per shape type and
stays on screen alongside the main toolbar and overflow (all three surfaces coexist). Tapping a
shape button inserts the shape at page centre, selects it in lasso mode, and shows the floating
selection toolbar so the user can act on it immediately. The overflow closes and the picker
dismisses in one step; tapping any other toolbar button or outside all toolbar surfaces also
dismisses the picker.

### Default sizes on insert

| Shape class | Width | Height | `aspectLocked` |
|---|---|---|---|
| `LINE`, `ARROW` | 50% of page width | 1 px (degenerate — rotate to use) | `false` |
| `ARCH` | 50% of page width | 25% of page width (half the width) | `false` |
| All closed shapes | `STICKY_NOTE_ICON_SIZE_DP` (72 dp × density) | same | `true` for ELLIPSE, RECTANGLE, STAR; `false` otherwise |

### Toolbar interaction rules

- **Three-toolbar coexistence**: when `btnInsertShape` is in the overflow, the `overflowKeepOpenForShapePicker`
  flag is set at `ACTION_DOWN` time. On `ACTION_UP` the flag is read before dispatching (avoiding the
  async `post(mPerformClick)` race) and `closeOverflowMenu()` is skipped.
- **Dismiss**: picker closes when any of these fires — a shape button is tapped, any other toolbar
  button is tapped, or a touch lands outside all toolbar surfaces. Only `btnInsertShape` itself is
  exempted (its click listener toggles the picker).
- **BOOX EPD exclusion**: `computeToolbarExclusionRect()` unions the picker's bounds when visible, preventing
  the Onyx EPD layer from capturing stylus events over it. Called in `showShapeInsertToolbar().post{}`
  after positioning and again in `hideShapeInsertToolbar()`.

### Undo / redo

Insert is recorded as a `ShapeInserted` undo action. Undo soft-deletes the shape and clears the
selection; redo re-inserts the shape and re-enters lasso mode with the shape selected.

---

## Host Coverage

Shape objects are fully supported in all three drawing hosts:

| Host | Dwell trigger | Insert toolbar | Lasso/erase | Lasso convert | Transform | Clipboard | Export |
|---|---|---|---|---|---|---|---|
| `NotebookActivity` (Onyx + Generic) | Yes | Yes | Yes | Yes | Yes | Yes | PDF |
| `ScratchpadActivity` | Yes | No | Yes | Yes | Yes | Yes | PNG/PDF |
| `StickyNoteEditorActivity` | Yes | No | Yes | Yes | Yes | Yes | (in sticky note) |

Cross-host clipboard (notebook ↔ scratch pad ↔ sticky) carries `ShapeRender` objects; the
receiving host reconstructs them via `ShapeRender.from()`.

---

## Lasso, Clipboard, and Erase Parity

Shape objects participate in every lasso/selection action on equal footing with strokes, headings,
text objects, lines, links, and sticky notes:

- **Lasso copy/cut/paste/delete** — `shapeIds`/`shapes` fields in `LassoErased`, `LassoDeleted`,
  `LassoCopied`, `LassoCut`, `LassoPasted` undo actions
- **Lasso move** — `originalShapes`/`movedShapes` in `StrokesMoved`
- **Scribble erase** — `shapeIds` in `ScribbleErased`
- **Erase all / page delete** — type-agnostic soft-delete; restored by `deletedAt` timestamp
- **Send to Scratch Pad** — shapes travel with the lasso selection
- **"Convert to Shape" lasso button** — appears in the floating selection toolbar when exactly one
  stroke is selected and no other objects share the selection. Runs `ShapeRecognizer.recognize()` on
  a background thread; the button (`btnConvertShape`, icon `ic_convert_shape`) is revealed only if
  the recognizer returns a confident result (confidence ≥ `SHAPE_MIN_CONFIDENCE`). Tapping it calls
  `convertStrokeToShape()` using the cached result — same path as the dwell trigger. Available in
  all three drawing hosts: `NotebookActivity`, `ScratchpadActivity`, `StickyNoteEditorActivity`.
- **AABB hit-test caveat**: lasso intersection uses the shape's AABB, not the rotated outline. A
  rotated rectangle's AABB is larger than the shape, so the lasso over-selects slightly. This is
  acceptable behavior; document it to future contributors.

---

## PDF Export

`ShapeGeometry.pathFor()` is called from the PDF rendering path. The path is drawn using the
shape's `strokeWidthPx` via `android.graphics.Canvas.drawPath()` on the PDF canvas, the same way it
is drawn on-screen. No fill; stroke-only rendering.

---

## Encryption Note

Shape objects are stored as `type="shape"` rows in the `.soil` SQLite database, which may be
encrypted with SQLCipher. No special handling is needed beyond the standard `SoilCrypto` open/close
path. Shape data never travels in Intent extras or unencrypted channels. See
[`docs/encryption.md`](encryption.md) for the full encryption architecture.

---

## Undo / Redo Actions

| Action | Session | Carries | Undo / Redo |
|---|---|---|---|
| `ShapeCreated` | S2 | `originalStroke` (full stroke), `shape` (ShapeRender), `deletedAt` | undo → restore stroke, soft-delete shape; redo → re-insert shape, soft-delete stroke |
| `ShapeInserted` | S9 | `shapeId`, `pageId`, `layerId`, `shape` (ShapeRender) | undo → soft-delete shape, clear selection; redo → restore shape, enter lasso + select |
| `ShapeTransformed` | S4 | `before`, `after` (ShapeRender) | undo → write `before`; redo → write `after` |
| `StrokesMoved` (extended) | S5 | `originalShapes`/`movedShapes` | undo/redo repositions shapes alongside strokes |
| `LassoErased/Pasted/Cut/Deleted/ScribbleErased` (extended) | S5 | `shapeIds`/`shapes` | mirror the existing stroke/heading/text fields |
| `PageEraseAll` / `PageDeleted` | — | no shape-specific field; type-agnostic | restored by `deletedAt` timestamp |

---

## Key Files

| File | Role |
|---|---|
| `data/ShapeType.kt` | Enum of all recognized types |
| `data/ShapeObject.kt` | Persisted form (JSON in `.soil`) |
| `data/ShapeRender.kt` | Render-time form; carries computed AABB |
| `notebook/ShapeGeometry.kt` | Pure path builder; used by both canvas draw and PDF export |
| `notebook/ShapeRecognizer.kt` | Full recognizer pipeline; `recognize(points, density)` |
| `notebook/ShapeTransformController.kt` | Transform mode: resize, rotate, aspect toggle |
| `notebook/NotebookConstants.kt` | All `SHAPE_*` and `STAR_*` tuning constants |
| `notebook/OnyxNotebookView.kt` | Onyx EPD host; dwell tracking, gate 0 dispatch |
| `notebook/GenericNotebookView.kt` | Generic host (Wacom, Supernote); same gate order |
| `res/drawable/ic_shape_*.xml` | Per-type icons for the insert toolbar (Tabler stroke style, 24×24 dp) |
