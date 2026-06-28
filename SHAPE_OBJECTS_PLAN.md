# Shape Objects — Implementation Plan

> Working plan for the **shape object** feature and its **smart-shape** creation gesture.
> Built to be executed by **Sonnet / medium effort**, one session at a time, with minimal
> independent decision-making. Every design choice has already been made — follow the spec.
>
> **Data-model name is `shape` / `ShapeObject` — never "smart shape".** "Smart" refers only to the
> draw-hold-lift *trigger* that converts a stroke into a shape object.

---

## 0. What we are building

A **shape object** is a first-class content object (like `line`, `text`, `heading`, `link`,
`sticky_note`) that renders a clean vector outline of a geometric primitive. It is:

- **Created** by the *smart-shape* gesture: draw a shape in one continuous stroke, hold the stylus
  still for ~1 s at the end of the stroke, then lift. The stroke is recognized and replaced by a
  shape object. (No toolbar button for creation in this effort.)
- **Resizable & rotatable** vector-style: the **outline stroke width stays constant** while resizing
  (it does not scale with the box). Editing happens in a dedicated **transform mode**.
- **A full participant** in lasso select / move / copy / cut / paste / delete, lasso-eraser,
  scribble-erase, erase-page, PDF export, and full undo/redo — **everywhere those already work**:
  the **notebook page**, the **scratch pad**, and the **sticky-note editor**.
- **Outline-only, inkBlack**, at the **drawn stroke's width**. No fill.

### Locked decisions (from planning Q&A — do not revisit)

| Topic | Decision |
|---|---|
| Hosts (create + transform) | Notebook page **and** scratch pad **and** sticky-note editor |
| Shape set | rectangle/square, ellipse/circle, triangle, diamond, trapezoid, pentagon, hexagon, star, arch (semicircle+base), line, arrow |
| Transform invocation | **Tap a lasso-selected shape** to enter transform mode (like tap-to-edit text) |
| Regularization on conversion | **Full**: normalize to upright (axis-aligned) + snap near-regular (near-square→square, near-circle→circle) |
| Appearance | Outline-only, inkBlack, **inherit drawn stroke width**, no fill |
| No-match behavior | Leave the stroke as normal ink, **no conversion**, no toast |
| Stroke count | **Single continuous stroke** only — only that stroke is analyzed/consumed |
| Conversion undo | The **original stroke is stored only in the `ShapeCreated` undo action** (never in the object). Undo → original stroke returns; redo → shape returns |

### Aspect-lock vs circle/oval toggle — unified design (please note)

There is **one** `aspectLocked: Boolean` field (persisted) and **one toggle button**:
- When **on**, any resize preserves the current width:height ratio (uniform scaling); when **off**,
  each handle resizes its side freely.
- For an **ellipse**, this same toggle *is* the circle/oval lock: turning it **on** snaps w=h
  (circle); turning it **off** allows w≠h (oval). The transform toolbar labels it **"Circle/Oval"**
  when the selected shape is an ellipse and **"Lock ratio"** otherwise — same field underneath.

A recognized square/circle/regular-polygon/star comes out with `aspectLocked = true`; a recognized
oblong rectangle/oval/triangle/etc. comes out with `aspectLocked = false`.

---

## 1. Conventions & global rules (read once, applies to every session)

### Package / file locations

- **Model** (`app/src/main/kotlin/com/notesprout/android/data/`):
  `ShapeType.kt`, `ShapeObject.kt`, `ShapeRender.kt`.
- **Logic** (`app/src/main/kotlin/com/notesprout/android/notebook/`):
  `ShapeGeometry.kt` (Path builder), `ShapeRecognizer.kt`, `ShapeTransformController.kt`.
- Constants → append to existing `notebook/NotebookConstants.kt`.
- Undo actions → append to `history/UndoRedoAction.kt`.

### The "mirror the line/sticky pattern" rule

`line` (`LineObject`/`LineRender`) and `sticky_note` (`StickyNoteObject`/`StickyNoteRender`) are the
two closest existing templates. **For every plumbing site, find how `LineRender` (and
`StickyNoteRender` for the host-activity callbacks) is handled and add a parallel `ShapeRender`
branch right next to it.** The integration sites are, exhaustively:

1. `data/NotebookDao.kt` — add `getShapeObjectsForLayer(layerId)` (`type = 'shape'`), mirror line 89-90.
2. `NotebookActivity.kt` — `loadShapeObjectsFromDb`, `drawingView.loadShapeObjects(...)`,
   `compositeShapeObjects(bitmap)`, include in `displayPage` (mirror lines ~3727, 3767, 3845, 3854).
3. `notebook/NotebookView.kt` — `loadShapeObjects/getShapeObjects/compositeShapeObjects`, the
   `shapeObjects` param on `buildRenderBitmap`, and an `onShapeErased` callback. Mirror the
   `lineObjects` / `onLineErased` declarations.
4. `notebook/OnyxNotebookView.kt` + `notebook/GenericNotebookView.kt` — `shapeObjects` field,
   `drawShapeObject(canvas, render)`, draw loop, lasso drag map, lasso-eraser hit, snapshot list,
   `buildRenderBitmap` param. Mirror every `lineObjects` / `drawLineObject` occurrence.
5. `data/ClipboardMappers.kt` + `data/ClipboardStore.kt` (`ClipboardContent`) — add
   `shapeObjects: List<ShapeRender>` and `TYPE_SHAPE` encode/decode (mirror `TYPE_LINE`, lines 32, 72, 83).
6. `history/UndoRedoAction.kt` — add `shapeIds`/`shapes` lists to `LassoErased`, `LassoPasted`,
   `LassoCut`, `LassoDeleted`, `ScribbleErased`, `PageEraseAll`, and `StrokesMoved`
   (`originalShapes`/`movedShapes`). Mirror the `lineIds`/`lines` fields.
7. `NotebookExporter.kt` — **covers BOTH PDF and PNG export** (they share one render path):
   - `renderPageBitmap()` must **load** shape rows (`dao.getShapeObjectsForLayer(layer.id)` →
     `List<ShapeRender>`, mirror the `lineObjects` block) and pass them to `renderPage()`.
   - `renderPage()` takes a `shapeObjects` param and draws them via a `drawShapeList` that uses the
     **same `ShapeGeometry.pathFor`** helper (after lines, before top-level strokes — mirror
     `drawLineList`).
   - All export entry points (`export`, `exportPagesPdf`, `exportPagesPng`, `exportPage`) flow through
     `renderPageBitmap` → `renderPage`, so this one change covers PDF **and** PNG with no per-format work.
8. `data/PageCopier.kt` — ensure `type = 'shape'` rows are carried by page copy/move (mirror line copy).
9. **Embedded shapes** (a shape can be inside a selection that becomes a link or a sticky note):
   - `data/LinkObject.kt` + `data/LinkRender.kt` — add a `shapes` field; the link draw loops in both
     drawing views **and** in `NotebookExporter.renderPage()` must `drawShapeList(link.shapes)`
     alongside the existing `headings/text/lines/strokes`.
   - `data/StickyNoteObject.kt` + `data/StickyNoteRender.kt` — add a `shapes` field so shapes drawn
     inside a sticky note round-trip and render on the sticky-note endnote/export pages (wired in S6).

> Whenever a list of object types appears (e.g. `if (strokes.isEmpty() && headings.isEmpty() && …)`,
> emptiness guards, union-bbox loops, `selectedObjectIds` partitioning), **add `shapes` to it**.

### Cross-cutting gotchas — single shared sites the "mirror" rule will NOT surface

These are **not** per-type branches, so grepping for `line` won't reveal them. Handle each explicitly:

- **A. Snapshot-staleness query (`data/NotebookDao.kt`, `getMaxContentUpdatedAt`, ~line 293).** It is a
  single hardcoded SQL list: `type IN ('stroke','heading','text','line','link','sticky_note')`.
  **Add `'shape'`** → `… 'sticky_note', 'shape')`. Without this, a page whose only change is
  adding/moving/transforming/deleting a shape will **not** invalidate its snapshot, so on reload the
  page shows a stale image (missing the shape, or still showing a deleted one). **Do this in S1.**
- **B. The `boundingBox` column is separate from `data`.** Every row stores its AABB in the
  `boundingBox` **column** as `BoundingBox(left, top, width, height).toJson()` (helper
  `RectF.toBoundingBoxJson()`); loaders read it via `parseBoundingBox(row.boundingBox)`. So:
  - On **insert** of a `type="shape"` row, set the `boundingBox` column to the shape's AABB (not just
    write `data`).
  - On **transform/move**, update **both** the `boundingBox` column **and** the `data` JSON — use the
    existing update query `NotebookDao` ~line 211 (`UPDATE notebook SET boundingBox=…, data=…,
    updatedAt=… WHERE id=…`), mirroring how line/heading edits persist.
- **C. Shapes are composited FRESH at load, not baked into the saved snapshot** — exactly like lines,
  text, links, sticky notes. `compositeShapeObjects(bitmap)` paints them on top of the snapshot at
  load. **Do NOT add shapes to `captureSnapshot()`** (that would double-render after the composite).
- **D. `PageEraseAll` / page delete restoration is timestamp-based and type-agnostic.** The forward
  erase soft-deletes by parent (`softDeleteByParentId(layerId, …)` — already covers shape rows); undo
  restores via `restoreChildrenDeletedSince(…)` (already covers shape rows). **Do NOT add a `shapeIds`
  field to `PageEraseAll`** (lines aren't in it either). The only change needed: add shapes to the
  erase-all **eligibility guard** (NotebookActivity ~line 922-928: `shouldErase = strokes.isNotEmpty()
  || headings… || sticky…`) so a shapes-only page is still erasable, and clear/rebuild the in-memory
  shape list on erase-all + its undo (mirror how the page reload handles lines). **In S5.**

### Rendering specifics (all draw sites + exporter)

- One shared helper `drawShapeObject(canvas, render)` per drawing view + one in `NotebookExporter`.
- Paint: `Paint(ANTI_ALIAS_FLAG)`, `style = STROKE`, `color = inkBlack` (`Color.BLACK`),
  `strokeWidth = render.strokeWidthPx`, `strokeJoin = ROUND`, `strokeCap = ROUND`.
- Path comes from `ShapeGeometry.pathFor(render)` (absolute coords, already rotated). Never re-derive
  geometry at a draw site.
- **Z-order (identical in both drawing views AND `NotebookExporter.renderPage`):** render shapes
  **immediately after lines and before top-level strokes** — i.e. headings → text → **lines →
  shapes** → links → sticky icons → strokes. Keep this order consistent everywhere or the on-screen
  composite, the snapshot fast-path, and the PDF/PNG will disagree.

### Per-session protocol (MANDATORY at the end of every session)

1. Set the session **Status** in this file to `IN PROGRESS` when you start it.
2. Do the work exactly as specified. Keep all unrelated behavior unchanged.
3. **Clean build + install on G102** (BOOX Go 10.3 Gen 2, serial **`b7a46e13`**), debug variant:
   ```sh
   cd apps/notesprout_android && ./gradlew clean assembleDebug
   adb -s b7a46e13 install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. In your reply, present the **manual test steps** for Greg to run (numbered, concrete, with the
   expected result for each). Then **stop and wait** — do not proceed.
5. Greg reports back. Fix any issues and re-test until all pass.
6. When all tests pass: set this session's **Status** to `DONE`, then **commit** (do **not** push):
   ```
   git commit -m "<session message>" 
   ```
   Co-author footer:
   ```
   Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
   ```
7. Move to the next session only after the commit.

### Standard constraints (from CLAUDE.md — never violate)

- Kotlin, Java 17. `kotlinx.serialization` only (`toJson`/`fromJson`), never `org.json`.
- No new Gradle dependencies. No Material Components. No `Log.d` (use `Slog.d(TAG){…}`).
- Never `runBlocking` on the UI thread. E-ink: no color, no shadow/elevation/animation; borders
  1dp solid inkBlack, radius 4dp; `borderGray` is invisible on e-ink — use inkBlack for visible lines.
- Soft deletes only; stable UUIDs; every write invalidates the page snapshot
  (`invalidatePageSnapshot`) and bumps `updatedAt`.

---

## 2. Data model (created in S1, referenced everywhere)

### `data/ShapeType.kt`
```kotlin
enum class ShapeType {
    RECTANGLE,   // square = RECTANGLE with aspectLocked + w==h
    ELLIPSE,     // circle  = ELLIPSE  with aspectLocked + w==h
    TRIANGLE,    // isosceles, apex up
    DIAMOND,     // rhombus (vertices at box edge midpoints)
    TRAPEZOID,   // isosceles, narrow top (0.6×w), full-width bottom
    PENTAGON,    // regular, point up
    HEXAGON,     // regular, flat top
    STAR,        // n-point (pointCount), outer/inner radius ratio = STAR_INNER_RATIO
    ARCH,        // semicircle top + straight sides + flat closed base
    LINE,        // open segment
    ARROW,       // open segment + arrowhead at the end
}
```

### `data/ShapeObject.kt` (serialized into the row `data` column; `type = "shape"`)
```kotlin
@Serializable
data class ShapeObject(
    val type: ShapeType,
    val centerX: Float,        // absolute page/canvas coords
    val centerY: Float,
    val width: Float,          // un-rotated local extents (the oriented box)
    val height: Float,
    val rotationDeg: Float = 0f,
    val strokeWidthDp: Float = 1f,
    val aspectLocked: Boolean = false,
    val pointCount: Int = 5,   // STAR only; ignored otherwise
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)
    companion object { fun fromJson(json: String): ShapeObject = Json.decodeFromString(serializer(), json) }
}
```

### `data/ShapeRender.kt` (in-memory; `@Serializable` so undo/clipboard can carry it)
```kotlin
@Serializable
data class ShapeRender(
    val id: String,
    @Serializable(with = RectFSerializer::class) val boundingBox: RectF, // AABB of the rotated outline,
                                                                          // inflated by max(strokeW/2, 4dp)
    val type: ShapeType,
    val centerX: Float, val centerY: Float,
    val width: Float, val height: Float,
    val rotationDeg: Float,
    val strokeWidthPx: Float,   // px (strokeWidthDp × density), inherited from the drawn stroke
    val aspectLocked: Boolean,
    val pointCount: Int = 5,
)
```
- `boundingBox` is computed from the generated path (`ShapeGeometry.pathFor(...).computeBounds`),
  inflated by `max(strokeWidthPx/2, 4dp)`. Recompute it whenever geometry changes (load, transform).
- A `ShapeRender.from(id, ShapeObject, density): ShapeRender` factory builds it (mirrors
  `parseLineRender`). `strokeWidthPx = shapeObject.strokeWidthDp * density`.
- A `ShapeRender.toShapeObject(): ShapeObject` (strokeWidthDp = strokeWidthPx / density) for persistence.

---

## SESSION 1 — Data model, geometry & rendering

**Status: DONE**

**Goal:** Shape objects exist, persist, render correctly on screen and in PDF + PNG export — verified
via a **temporary debug insertion menu** (no recognizer yet).

### Files to create
- `data/ShapeType.kt`, `data/ShapeObject.kt`, `data/ShapeRender.kt` — per §2.
- `notebook/ShapeGeometry.kt` — `object ShapeGeometry { fun pathFor(r: ShapeRender): Path }`.

### `ShapeGeometry.pathFor` spec (build in local space, then rotate)
Build the outline in absolute coords using the oriented box `(cx, cy, w, h)`, then apply
`Matrix().apply { setRotate(r.rotationDeg, cx, cy) }` to the whole Path (or rotate each computed
point). Half extents `hw = w/2`, `hh = h/2`. Let `L=cx-hw, T=cy-hh, R=cx+hw, B=cy+hh`.

- **RECTANGLE:** path through `(L,T)(R,T)(R,B)(L,B)` closed.
- **ELLIPSE:** `addOval(RectF(L,T,R,B), CW)`.
- **TRIANGLE:** `(cx,T)(R,B)(L,B)` closed (apex up, isosceles).
- **DIAMOND:** `(cx,T)(R,cy)(cx,B)(L,cy)` closed.
- **TRAPEZOID:** topInset = `0.2f*w`; `(L+topInset,T)(R-topInset,T)(R,B)(L,B)` closed.
- **PENTAGON / HEXAGON / STAR:** parametric around center. For `n` vertices, angle of vertex `i` =
  `startAngle + i*2π/n`. Point = `(cx + hw*cos θ, cy + hh*sin θ)` (so a non-square box yields an
  inscribed-ellipse fit). `startAngle = -π/2` (point up) for pentagon & star; `0` (flat-ish) is fine
  for hexagon but use `-π/2 + π/6` so it has a flat top. **STAR:** 2×`pointCount` vertices alternating
  outer radius (`hw,hh`) and inner radius (`hw*STAR_INNER_RATIO, hh*STAR_INNER_RATIO`); `n` outer
  points = `pointCount`.
- **ARCH:** flat closed base + semicircular top. Path: `moveTo(L,B) → lineTo(L,cy) → arcTo(RectF(L,T,R, T+h), 180°, 180°)` (top semicircle spanning the upper half) `→ lineTo(R,B) → close`. Tune so the
  dome sits in the top portion and straight sides drop to the base. (Keep it simple: semicircle whose
  diameter = w sits on top of a rectangle of height `h - hw`; if `h < hw`, degenerate to pure
  semicircle.)
- **LINE:** `moveTo(L,cy) lineTo(R,cy)` (a horizontal segment; rotation gives any angle). For LINE/ARROW
  the "height" is nominal; store a small `h` (e.g. strokeWidth) — the box is effectively the segment.
- **ARROW:** the LINE segment plus an arrowhead at `(R,cy)`: two short segments back from the tip at
  ±150° of the shaft direction, length = `min(hw*0.5f, 24dp)`.

> `pathFor` must be **pure** (no view state) so the exporter and both views share it.

### Files to edit (mirror the `line` pattern at each site — see §1 rule)
- `data/NotebookDao.kt` — (1) `getShapeObjectsForLayer(layerId)`; (2) **add `'shape'` to the
  `getMaxContentUpdatedAt` type list (~line 293)** — gotcha **A**, required for snapshot staleness.
- `notebook/NotebookView.kt` — `loadShapeObjects`, `getShapeObjects`, `compositeShapeObjects`,
  `shapeObjects` param on `buildRenderBitmap`.
- `notebook/OnyxNotebookView.kt` + `GenericNotebookView.kt` — `shapeObjects` field + accessors,
  `drawShapeObject`, draw loop, `buildRenderBitmap`, `compositeShapeObjects`. (No lasso/erase wiring
  yet — that's S5; but **do** include shapes in the draw loop + composite + buildRenderBitmap.)
- `NotebookActivity.kt` — `loadShapeObjectsFromDb`, call `loadShapeObjects` + `compositeShapeObjects`
  in `displayPage` (mirror lines ~3727/3845/3854).
- `NotebookExporter.kt` — load shapes in `renderPageBitmap()` and render them in `renderPage()` via
  `ShapeGeometry.pathFor` (covers **both PDF and PNG** export — they share this single render path).
- `NotebookConstants.kt` — add: `STAR_INNER_RATIO = 0.5f`, `SHAPE_MIN_STROKE_WIDTH_DP` (not needed
  yet but reserve), regularization tolerances are added in S2.

### Temporary debug insertion (throwaway — removed in S3)
Gated on `BuildConfig.DEBUG`. Add a small dev affordance in `NotebookActivity` to insert one of each
shape type centered on the page (e.g. a long-press on an existing, unobtrusive toolbar button, or a
hidden overflow item "DEBUG: insert shapes"). It inserts `type="shape"` rows (varied types, a couple
rotated, one star) via the normal insert+`invalidatePageSnapshot`+rebuild path, so rendering and
persistence are exercised. The insert must set **both** the `data` JSON **and** the `boundingBox`
column (gotcha **B**). **Mark every debug addition with `// TODO(S3): remove debug shape insert`.**

### Test steps (G102)
1. Open a notebook. Trigger **DEBUG: insert shapes**. → A rectangle, square, ellipse, circle,
   triangle, diamond, trapezoid, pentagon, hexagon, 5-point star, arch, line, and arrow appear,
   outline-only in black, including the rotated samples.
2. Close and reopen the notebook. → All shapes persist and re-render identically (snapshot + DB load).
3. Export the page to **PDF** and to **PNG** (existing export options). → Shapes appear in both,
   matching the screen (both formats share `renderPage`, so verify each once).
4. Confirm shapes render at the **drawn stroke width** look (thin outline), no fill, no color.

**Commit message:** `feat: shape objects — data model, geometry, rendering (S1)`

---

## SESSION 2 — Shape recognizer + conversion + ShapeCreated undo

**Status: DONE**

**Goal:** A single stroke can be converted into a regularized shape object, with full undo/redo —
verified via a **temporary debug "Convert to shape"** action on a single-stroke lasso selection.
(The real dwell gesture comes in S3.)

> **As-built deviations from spec (record for S7 tuning pass):**
> - `SHAPE_RDP_EPS_RATIO = 0.08f` (spec 0.045) — looser epsilon needed to reliably reduce regular
>   polygon outlines to the correct corner count without over-segmenting pen wobble.
> - `SHAPE_ELLIPSE_CV = 0.18f` (spec 0.12) — raised to tolerate hand-drawn ellipses; regular polygons
>   (pentagon CV ≈ 0.08, hexagon ≈ 0.06) are still well below the threshold.
> - `SHAPE_CLOSURE_RATIO = 0.35f` (spec 0.20) — raised to catch closed shapes where the pen lifts
>   slightly away from the start point.
> - **Fine-RDP flatness check added** (not in spec): polygon candidates with 5–9 corners are
>   confirmed by re-running RDP at `eps/2`. Polygon flat sides add ≈0 new corners; curved ellipse
>   arcs roughly double. Threshold 1.9×. This replaced a radial-CV guard that failed because regular
>   polygon CVs are indistinguishable from smooth ellipses.
> - **`classifyConvexPolygon` corner range 5–9** (spec said 5/6 only) — extended to absorb ±2 noise
>   corners from a hand-drawn pentagon or hexagon.
> - **Trapezoid `widthRatio` threshold 0.65f** (spec implied 0.50) — raised so typical hand-drawn
>   trapezoids (narrow edge at 55–75% of wide edge) are caught; clean rectangles rarely dip below 0.70.

### Files to create
- `notebook/ShapeRecognizer.kt` —
  `object ShapeRecognizer { fun recognize(points: List<PointF>, density: Float): Result? }`
  where `Result(type, centerX, centerY, width, height, rotationDeg, aspectLocked, pointCount)` is the
  **regularized** geometry (already upright + snapped). Returns `null` when confidence <
  `SHAPE_MIN_CONFIDENCE`.

### Recognizer algorithm (implement exactly)
Work in a copy of `points`. Compute `bbox`, `diag = bbox diagonal`, `centroid`.
1. **Reject tiny:** if `diag < SHAPE_MIN_DIAGONAL_DP*density` → `null`.
2. **Resample** to 64 evenly-spaced points along the path length (`resampled`).
3. **Closedness** `c = dist(first,last)/diag`. Closed if `c ≤ SHAPE_CLOSURE_RATIO` (0.20).
4. **Open shapes (not closed):**
   - Fit a straight line (total least squares or endpoint line); `maxPerpDev/length`.
     If `≤ SHAPE_LINE_STRAIGHTNESS` (0.10) → **LINE** (score high). Then check for an **ARROW**:
     near one endpoint, look for 2 short back-segments forming a ≤90° head (direction reversals
     within the last ~15% of path). If found → **ARROW**.
   - Else if the open path is a smooth low-curvature **arc** spanning roughly a half-turn → **ARCH**
     (treat as open semicircle; geometry's base closes it). Otherwise → `null`.
5. **Closed shapes:** simplify with **Ramer–Douglas–Peucker**, `epsilon = SHAPE_RDP_EPS_RATIO*diag`
   (0.045). Let `corners = simplified vertices` (drop the duplicate closing point). Also compute the
   **radial profile**: distance of each resampled point from centroid, normalized.
   - **Circle/Ellipse test:** radial coefficient of variation `cv = stddev/mean`. If
     `cv ≤ SHAPE_ELLIPSE_CV` (0.12) → **ELLIPSE**.
   - **Star test:** count strong alternations in the radial profile (peaks above mean+, troughs below
     mean−). If `≥ 5` peaks with regular spacing → **STAR**, `pointCount = peaks`.
   - Else classify by `corners.size`:
     - `3` → **TRIANGLE**
     - `4` → quad sub-classify (see below)
     - `5` → **PENTAGON**
     - `6` → **HEXAGON**
     - other → take the closest of {ellipse, triangle, rectangle} by residual, or `null` if poor.
   - **Quad sub-classify (4 corners):** order corners by angle around centroid. Compute the 4 edge
     vectors and the oriented bbox angle.
     - If both diagonals are near axis-aligned and corners sit near box-edge **midpoints** → **DIAMOND**.
     - Else if one pair of opposite edges is parallel and horizontal with **different lengths** (top
       shorter) → **TRAPEZOID**.
     - Else → **RECTANGLE**.
6. **Confidence:** each branch yields a score in `[0,1]` from its residual fit; the chosen type's
   score must be `≥ SHAPE_MIN_CONFIDENCE` (0.55) or return `null`.
7. **Regularize (full):** produce upright geometry —
   - `rotationDeg = 0` unless the shape is intrinsically tilted; snap any detected orientation within
     `SHAPE_UPRIGHT_SNAP_DEG` (8°) of an axis to `0`. (For v1, **always emit `rotationDeg = 0`** — the
     user rotates later in transform mode. Simpler and matches "upright".)
   - `width/height` = the recognized bbox extents. If `|w-h|/max(w,h) ≤ SHAPE_SQUARE_SNAP` (0.15) for
     RECTANGLE/ELLIPSE/PENTAGON/HEXAGON/STAR/TRIANGLE → set `w = h = max(w,h)` and `aspectLocked = true`;
     else `aspectLocked = false`.
   - `centerX/centerY` = bbox center.

### Constants to add (`NotebookConstants.kt`)
`SHAPE_MIN_DIAGONAL_DP = 24f`, `SHAPE_CLOSURE_RATIO = 0.20f`, `SHAPE_LINE_STRAIGHTNESS = 0.10f`,
`SHAPE_RDP_EPS_RATIO = 0.045f`, `SHAPE_ELLIPSE_CV = 0.12f`, `SHAPE_SQUARE_SNAP = 0.15f`,
`SHAPE_UPRIGHT_SNAP_DEG = 8f`, `SHAPE_MIN_CONFIDENCE = 0.55f`.

### Conversion + undo (the real code path, reused by S3)
In `NotebookActivity.kt` add `convertStrokeToShape(stroke: LiveStroke, result: ShapeRecognizer.Result)`:
1. Build a `ShapeObject` (strokeWidthDp = `stroke.strokeWidth / density`) and `ShapeRender`.
2. In one `db.withTransaction {}`: **soft-delete** the original stroke row (it may not be persisted
   yet — if not in DB, just skip the delete) and **insert** the `type="shape"` row, setting **both**
   the `data` JSON and the `boundingBox` column to the shape's AABB (gotcha **B**);
   `invalidatePageSnapshot`.
3. Remove the stroke from the in-memory list / `persistedStrokeIds`; add the `ShapeRender` to the
   shape list; rebuild bitmap off-thread; swap via `loadStrokesWithBitmap`.
4. Push **`UndoRedoAction.ShapeCreated`** (new):
   ```kotlin
   @Serializable data class ShapeCreated(
       val shapeId: String, val pageId: String, val layerId: String,
       val deletedAt: Long,                 // soft-delete ts of the original stroke (0 if it was never persisted)
       val originalStroke: LiveStroke,      // full points — the ONLY place the raw stroke is kept
       val shape: ShapeRender,
   ) : UndoRedoAction()
   ```
   - **Undo:** soft-delete the shape row; restore the original stroke (re-insert if `deletedAt==0`,
     else clear `deletedAt`); rebuild. Result: the user's original hand-drawn stroke is back.
   - **Redo:** soft-delete the stroke; restore the shape row; rebuild.
   - Wire into all four undo dispatch tiers exactly like `TextConverted` (`pageIdFor`, cross-page DB,
     same-page DB, same-page in-memory).

### Temporary debug trigger (throwaway — removed in S3)
When a lasso selection is **exactly one stroke** (pure-stroke, count 1), show a debug-only
"DEBUG: →shape" button in the floating selection toolbar (`BuildConfig.DEBUG`). Tapping it runs
`ShapeRecognizer.recognize` on that stroke; on success calls `convertStrokeToShape`; on `null` shows a
debug toast "no shape". Mark `// TODO(S3): remove debug convert`.

### Test steps (G102)
1. Draw a rough square, lasso-select it, tap **DEBUG: →shape**. → Becomes a clean upright square.
2. Repeat for circle, ellipse, triangle, diamond, trapezoid, pentagon, hexagon, 5-point star, a
   straight line, an arrow, and an arch. → Each recognizes to the right shape (note any that fail).
3. Draw a scribble / a letter, select, tap. → Debug toast "no shape"; stroke unchanged.
4. Convert a square, then **Undo**. → Original hand-drawn stroke returns. **Redo** → square returns.
5. Convert, close & reopen the notebook. → Shape persists; the original stroke is gone from the DB.

**Commit message:** `feat: shape recognizer + stroke→shape conversion with undo (S2)`

---

## SESSION 3 — Smart-shape dwell trigger (draw → hold → lift)

**Status: DONE**

**Goal:** Drawing a single shape, holding the stylus still ~1 s, then lifting auto-converts it — in
the **notebook** (scratch pad / sticky come in S6). Remove all S1/S2 debug affordances.

> **Revisit note (post-S3):** The dwell trigger fires intermittently (timing feels unreliable on
> BOOX devices) and shape recognition accuracy has degraded from S2 baselines. Both warrant a
> dedicated tuning pass before S4 (or as a standalone S3-polish session): tighten the recognizer
> scoring, re-evaluate `SHAPE_DWELL_MS` / `SHAPE_DWELL_RADIUS_DP` constants, and add more robust
> fallback behavior when recognition returns a low-confidence result.

### Dwell detection (both drawing views)
Track the stylus "settling" during a stroke:
- New fields: `dwellAnchor: PointF?`, `lastMoveTimeMs: Long`.
- On `onBeginRawDrawing` (Onyx) / `ACTION_DOWN` (Generic): `dwellAnchor = null`.
- For each incoming point (Onyx `renderStroke` loop using `TouchPoint.timestamp` when available, else
  `System.currentTimeMillis()`; Generic `ACTION_MOVE`): if `dwellAnchor == null` or
  `dist(point, dwellAnchor) > SHAPE_DWELL_RADIUS_DP*density` → `dwellAnchor = point; lastMoveTimeMs = now`.
- At pen lift, `dwellMs = liftTime - lastMoveTimeMs`. This is robust whether or not the SDK keeps
  emitting points while stationary (no points ⇒ `lastMoveTimeMs` stays old ⇒ large `dwellMs`; jitter
  within the radius ⇒ anchor unchanged ⇒ large `dwellMs`).

### New gate: shape detection runs FIRST in `checkAndDispatchGesture`
Insert before the smart-lasso gate (it cannot collide: dwell requires a slow settle; smart-lasso
requires high velocity; scribble requires zig-zag density):
1. Gate condition: `currentGestureStrokeIds.size == 1` **and** `dwellMs ≥ SHAPE_DWELL_MS`.
2. If met, run `ShapeRecognizer.recognize(gesturePoints, density)` (on the existing background thread).
3. On a non-null result: `post {}` → remove the gesture stroke from in-memory (never persisted),
   release the EPD overlay render (Onyx: `setRawDrawingRenderEnabled(false)` + `invalidate()` — mirror
   the smart-lasso handoff), and fire a new callback
   `onShapeRecognized(originalStroke: LiveStroke, result: ShapeRecognizer.Result)`.
4. On `null`: **fall through** to the existing smart-lasso → scribble → normal-stroke chain unchanged
   (so a held pause that isn't a shape still saves as ink).

> `originalStroke` = the in-memory `LiveStroke` for the single gesture stroke (full points), passed so
> the activity's `ShapeCreated` undo can store it.

### Wiring
- `NotebookView.kt` — declare `var onShapeRecognized: ((LiveStroke, ShapeRecognizer.Result) -> Unit)?`.
- `NotebookActivity.kt` — wire it to `convertStrokeToShape(stroke, result)` (from S2).
- **Remove** the S1 debug-insert menu and the S2 debug "→shape" button and their `TODO(S3)` markers.

### Constants (`NotebookConstants.kt`)
`SHAPE_DWELL_MS = 700L`, `SHAPE_DWELL_RADIUS_DP = 6f`.

### Test steps (G102)
1. Draw a square in one stroke, **hold still ~1 s**, lift. → Converts to a clean square.
2. Draw a square and lift **immediately** (no hold). → Stays as ink (no conversion).
3. Draw circle / triangle / star / line / arrow with the hold-then-lift. → Each converts.
4. Draw a fast loop around existing text (no hold). → Still triggers **smart-lasso** (unchanged).
5. Draw a scribble over content (no hold). → Still **scribble-erases** (unchanged).
6. Convert via gesture, **Undo** → original stroke; **Redo** → shape. (Conversion undo still works.)
7. Confirm the debug menu/button from S1/S2 are gone.

**Commit message:** `feat: smart-shape dwell-then-lift trigger in notebook (S3)`

---

## SESSION 4 — Transform mode (resize, rotate, toggles)

**Status: DONE**

**Goal:** Tapping a lasso-selected single shape enters **transform mode**: 8 resize handles, a rotate
knob, aspect-lock / circle-oval toggle, with vector-constant stroke width and full undo. Notebook only
(scratch/sticky reuse it in S6).

### Shared controller — `notebook/ShapeTransformController.kt`
A view-agnostic helper both drawing views delegate to. Holds the active `ShapeRender` (working copy)
and:
- `draw(canvas)` — draws the oriented box outline (1dp inkBlack dashed), 8 solid square handles
  (4 corners + 4 edge midpoints, `SHAPE_HANDLE_SIZE_DP`), and a **rotate knob** (small circle) at
  `SHAPE_ROTATE_OFFSET_DP` beyond the top-center handle with a short connector line. All handle
  positions are computed in the shape's rotated frame.
- `hitTest(x,y): Grab` → `NONE | BODY | ROTATE | one of 8 RESIZE handles`. Hit radius
  `SHAPE_HANDLE_TOUCH_DP`.
- `onDown/onMove/onUp` — mutate the working `ShapeRender`:
  - **BODY drag** → translate center.
  - **RESIZE drag** → convert the pointer into the shape's local (un-rotated) frame via inverse
    rotation about center; move the grabbed edge/corner; the **opposite edge/corner stays anchored**;
    recompute `centerX/centerY/width/height`. If `aspectLocked`, constrain to the current ratio
    (corner handles scale uniformly; edge handles scale uniformly too when locked). Enforce a minimum
    size `SHAPE_MIN_SIZE_DP`.
  - **ROTATE drag** → `rotationDeg = angle(center → pointer) − grabOffset`; snap to 0/90/180/270 within
    `SHAPE_ROTATE_SNAP_DEG` (5°).
  - **Stroke width never changes** during any of this (vector style).
  - Recompute `boundingBox` after each change.
- Callbacks out: `onTransformCommitted(before: ShapeRender, after: ShapeRender)`.

### View integration (both drawing views)
- New mode `isShapeTransformMode` (treated like lasso for EPD: `setRawDrawingEnabled(false)`; obey the
  tool-state invariants in `docs/drawing-engine.md`).
- `enterShapeTransform(render)` / `exitShapeTransform()`; route touch events to the controller while
  active; `onDraw` calls `controller.draw(canvas)` in the overlay layer.
- On commit, fire `onShapeTransformed(before, after)` to the activity.

### Host (NotebookActivity) wiring
- **Enter:** when a lasso selection is exactly one shape and the user taps **inside that shape's
  bbox** (mirror text tap-to-edit: a stylus tap below `DRAG_THRESHOLD_DP` while a single shape is
  selected), call `enterShapeTransform(shape)`. Show a compact **transform toolbar** (reuse the
  floating-selection-toolbar infra) with:
  - **Lock-ratio / Circle-Oval** toggle (label depends on type; flips `aspectLocked`, and for ELLIPSE
    snaps w=h on lock). Use `isSelected` state styling (selected = 1dp inkBlack inset — the
    disabled-look is invisible on e-ink, so use selected/!selected, never enabled/disabled).
  - **Done** button → commit + `exitShapeTransform()` back to lasso selection.
  - Toggling a value applies live to the working shape and redraws.
- **Exit/commit:** Done, or tap outside the shape, commits. Tap outside also clears selection
  (back to pen) consistent with lasso.
- **Persist + undo:** on `onShapeTransformed(before, after)` → write the `after` `ShapeObject` JSON
  **and the recomputed `boundingBox` column** to the row (single UPDATE per gotcha **B**),
  `invalidatePageSnapshot`, update in-memory shape list, rebuild bitmap. Push new
  **`UndoRedoAction.ShapeTransformed(shapeId, pageId, before, after)`** (carries full `ShapeRender`
  both directions). Undo writes `before`, redo writes `after`; wire into all four dispatch tiers like
  `TextEdited`.

### Constants (`NotebookConstants.kt`)
`SHAPE_HANDLE_SIZE_DP = 10f`, `SHAPE_HANDLE_TOUCH_DP = 22f`, `SHAPE_ROTATE_OFFSET_DP = 36f`,
`SHAPE_ROTATE_SNAP_DEG = 5f`, `SHAPE_MIN_SIZE_DP = 24f`.

### Test steps (G102)
1. Create a square (gesture). Lasso-select it, then tap it. → Transform handles + rotate knob +
   transform toolbar appear.
2. Drag a corner with **Lock-ratio ON** → scales uniformly; the outline thickness stays constant.
3. Toggle **Lock-ratio OFF**, drag an edge → that side stretches into a rectangle.
4. Drag the rotate knob → shape rotates; near 90° it snaps. Release.
5. On an ellipse: toggle shows **Circle/Oval**; turning it on snaps to a circle, off allows an oval.
6. Tap **Done** → handles disappear, shape selected with normal dashed box.
7. **Undo** → shape returns to pre-transform geometry; **Redo** → re-applies.
8. Reopen the notebook → transformed geometry (incl. rotation) persists.

**Commit message:** `feat: shape transform mode — resize/rotate/aspect toggle with undo (S4)`

---

## SESSION 5 — Lasso, clipboard, erase & export integration (notebook)

**Status: DONE**

**Goal:** Shapes are full citizens of every selection/erase/transfer action with undo — in the
notebook. (This is mostly the mechanical "mirror the line/sticky pattern" rule from §1.)

### Edit each site (mirror `lineIds`/`lines` and `stickyNoteIds`/`stickyNotes`)
- **Lasso select / hit-test:** include shapes (region-intersects bbox, touch semantics via
  `LassoGeometry.regionIntersectsBox`) in both drawing views' lasso + smart-lasso hit tests and in
  `selectedObjectIds` partitioning in `NotebookActivity`.
- **Move (drag):** include shapes in the lasso drag map in both views; translate `centerX/centerY` +
  `boundingBox`. Extend `onStrokesMoved` + `StrokesMoved` with `originalShapes`/`movedShapes`.
- **Copy / Cut / Paste:** `ClipboardContent.shapeObjects`, `ClipboardMappers` `TYPE_SHAPE`
  encode/decode, paste assigns fresh UUIDs + translated geometry. Extend `LassoPasted` / `LassoCut`
  with `shapeIds`/`shapes`.
- **Delete:** include shapes in `performLassoDelete`; extend `LassoDeleted`.
- **Lasso-eraser:** hit-test shape bbox; `onShapeErased` callback; extend `LassoErased`.
- **Scribble-erase:** include shapes in `scribbleHitTest` (bbox penetration) + `onScribbleEraseComplete`
  payload; extend `ScribbleErased`.
- **Erase page (`PageEraseAll`)** and **page delete/copy/move:** **Do NOT add a `shapeIds` field**
  (gotcha **D**) — the forward erase already soft-deletes shape rows via `softDeleteByParentId`, and
  undo restores them via `restoreChildrenDeletedSince` (both type-agnostic). The only changes:
  (1) add shapes to the erase-all **eligibility guard** (~line 922-928) so a shapes-only page is
  erasable; (2) clear the in-memory shape list on erase-all and rebuild it on undo (mirror lines);
  (3) ensure `PageCopier` carries `type='shape'` rows on page copy/move.
- **Link-embedded shapes:** a selection wrapped into a link that includes a shape carries it
  (`LinkObject.shapes` per §1.9); the link renders the embedded shape on screen and in export.
- **PDF + PNG export:** the render path was added in S1; verify multi-shape pages and a
  link-embedded shape export correctly to **both** formats.

> Update every emptiness guard / union-bbox loop / `selectedObjectIds` set to include shapes
> (search for `stickyNote` and add a `shape` sibling at each hit).

### Floating selection toolbar
- A single-shape selection shows the standard selection box (and, on tap, enters transform mode per
  S4). Multi-selection including shapes behaves like any mixed selection (move/copy/cut/delete/erase).
- Shapes are **not** snap *targets* for now (mirror lines: strokes/shapes excluded from
  `snapObjectTargets`) but a selection containing a shape still snaps as a whole during drag.

### Test steps (G102) — for each, verify undo + redo
1. Lasso-select a shape alongside a stroke + a text object; drag to move. → All move together;
   undo/redo restore.
2. Copy the shape, paste (same page) → duplicate with new identity; paste on another page → appears.
3. Cut the shape → removed + on clipboard; paste → returns; undo the cut → returns.
4. Delete a selected shape (floating toolbar) → gone; undo → back.
5. Lasso-erase across a shape → erased; undo → back.
6. Scribble across a shape → erased; undo → back.
7. Erase-page with shapes present → cleared; undo → all return.
8. Copy a page containing shapes (page index) → copy has the shapes. Export to **PDF and PNG** →
   shapes present in both.
9. Wrap a selection that includes a shape into a link → the link renders the shape; export shows it.

**Commit message:** `feat: shapes are full lasso/clipboard/erase/export citizens with undo (S5)`

---

## SESSION 6 — Scratch pad & sticky-note editor hosts

**Status: DONE**

**Goal:** Smart-shape creation, transform mode, and the lasso/clipboard/erase wiring all work inside
the **scratch pad** (`ScratchpadActivity`) and the **sticky-note editor** (`StickyNoteEditorActivity`),
which already host the shared drawing views.

### Per host (both activities)
Both already wire pen / eraser / lasso / lasso-eraser / scribble / paste and mirror the notebook's
object callbacks. Add the **shape** parallels to each:
- Load/persist `type='shape'` rows for the host's canvas (scratch pad pages; sticky-note content).
  Mirror how they load lines/strokes.
- Wire `onShapeRecognized` → a host `convertStrokeToShape` (same logic as NotebookActivity; factor
  shared parts if practical, else replicate the small method).
- Wire `onShapeTransformed`, `onShapeErased`, and the shape branches of the lasso/clipboard/erase
  callbacks + their undo actions (these activities maintain their own undo stacks / managers — mirror
  the line/sticky handling already present).
- Transform toolbar: reuse the host's floating-selection-toolbar; the sticky-note editor canvas is
  small — verify the rotate knob/handles remain reachable (if the knob would fall off-canvas, clamp the
  offset inward; add `SHAPE_ROTATE_OFFSET_DP` clamping logic if needed).
- Confirm the **clipboard is shared** (`NotesproutClipboard`) so a shape copied in the notebook pastes
  into the scratch pad / sticky editor and vice-versa.
- **Sticky-note endnote export:** `StickyNoteObject`/`StickyNoteRender` gain a `shapes` field (§1.9);
  the endnote render in `NotebookExporter` (~line 345, `renderPage(… note.headings, note.textObjects,
  note.lines, note.strokes …)`) must also pass `note.shapes`, so shapes drawn inside a sticky note
  appear on its PDF endnote page. `ScratchpadRepository` / the sticky persistence paths must serialize
  the `shapes` field (mirror how they serialize `lines`).

### Encryption note
Shapes carry no plaintext beyond geometry; they live in the same `.soil`, so the existing per-notebook
encryption covers them automatically (no special handling — same as lines). Verify nothing logs shape
data and nothing crosses into the global index.

### Test steps (G102)
**Scratch pad:**
1. Open the scratch pad. Draw a shape with hold-then-lift → converts.
2. Tap-select → transform; resize/rotate; Done; undo/redo.
3. Copy a shape in the notebook, open scratch pad, paste → appears.
4. Lasso-erase / scribble-erase / delete a shape → works + undo.
5. Close & reopen scratch pad → shapes persist.

**Sticky-note editor:**
6. Open a sticky note. Draw a shape with hold-then-lift → converts.
7. Transform it (handles reachable on the small canvas); Done; undo/redo.
8. Paste a shape copied elsewhere → appears; erase variants + undo work.
9. Close the editor and reopen → shapes persist. The page icon still renders (shapes don't leak onto
   the page, per sticky-note rules).

**Commit message:** `feat: smart shapes in scratch pad & sticky-note editor (S6)`

---

## SESSION 7 — Polish, docs & cleanup

**Status: NOT STARTED**

**Goal:** Final hardening, documentation, and backlog capture.

### Tasks
- **Recognizer tuning pass:** run the full shape set on G102; adjust the §2 constants if specific
  shapes misfire (record final values in `NotebookConstants.kt` with a short comment). Note any shape
  with persistently low accuracy in BACKLOG rather than over-fitting.
- **Edge cases:** zero-area / degenerate strokes; transform min-size clamp; rotated-shape lasso
  over-selection (AABB) is acceptable — document it. Verify no EPD residue/flash on conversion and
  transform handoffs (follow the `eraseAll`/smart-lasso handoff sequence on Onyx).
- **Cross-host clipboard** round-trips (notebook ↔ scratch pad ↔ sticky) once more.
- **Docs:** create `docs/shape-objects.md` (data model, two coordinate concepts: oriented box vs AABB,
  recognizer pipeline + constants, dwell trigger + gate order, transform mode + controller, the
  unified aspect/circle-oval toggle, lasso/clipboard/erase/export parity, host coverage, encryption
  note, undo actions). Add a row to the docs table in `CLAUDE.md`.
- **BACKLOG.md:** add deferred items — multi-stroke shape assembly, a toolbar "insert shape" button,
  fill option, snap-target support for shapes, any low-accuracy shapes, per-shape style options.
- Remove any remaining dead/debug code; ensure `Slog` (not `Log.d`); no new dependencies.

### Test steps (G102)
1. Full regression of S2–S6 happy paths in all three hosts.
2. Confirm `docs/shape-objects.md` and the `CLAUDE.md` table row exist and read correctly.
3. Clean build installs and runs with no shape-related logcat warnings.

**Commit message:** `docs: shape objects complete — docs, BACKLOG, tuning (S7)`

---

## Appendix A — New undo actions summary

| Action | Added in | Carries | Undo / Redo |
|---|---|---|---|
| `ShapeCreated` | S2 | `originalStroke` (full), `shape`, `deletedAt` | undo→stroke back / redo→shape back |
| `ShapeTransformed` | S4 | `before`, `after` (`ShapeRender`) | write before / write after |
| (extend) `StrokesMoved` | S5 | `originalShapes`/`movedShapes` | reposition |
| (extend) `LassoErased/Pasted/Cut/Deleted`, `ScribbleErased` | S5 | `shapeIds`/`shapes` | mirror lines |
| `PageEraseAll` / `PageDeleted` | — | **no field** (gotcha **D**) | restored by timestamp, type-agnostic |

## Appendix B — Status legend
`NOT STARTED` → `IN PROGRESS` → `DONE`. Keep each session header's **Status** current; the per-session
protocol in §1 governs build/install/test/commit.
