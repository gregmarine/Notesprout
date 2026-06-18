# Content Objects — Heading, Text, Line

> Referenced from `CLAUDE.md`. Covers the heading, text (+ markdown engine), and line object systems.
> All three are first-class lasso participants — see `lasso-and-gestures.md`.

## Heading Objects

- `type = "heading"` rows in `.soil`; `HeadingObject` serialized to `data`; `HeadingStroke` is the in-memory representation
- Headings render as grey-fill backgrounds with embedded strokes, or 20sp inkBlack canvas text when `recognizedText` is non-null
- `recognizedText: String?` — null = render strokes; non-null = canvas text (populated by ML Kit at creation)
- All lasso actions (move, copy, cut, paste, delete, eraser) treat headings as first-class participants
- `copyPageAfter()` and `copyPageAfterRaw()` copy all object types, not just strokes

### Top-heading-as-page-name rule

A page's display name is the text of its **topmost heading**: smallest `boundingBox.top`, ties broken
by smallest `left`; the label is `recognizedText` with a leading `"# "` markdown prefix stripped. When
no heading qualifies (none present, or topmost has null/empty `recognizedText`), callers fall back to
`Page {N}`.

This rule is implemented in **two independent places** that read pages via different paths:

- **`toc/TocRepository.kt`** — the notebook TOC, via the Room `NotebookDao`.
- **`data/PageHeadingNames.kt`** (`topHeadingNamesByPageId`) — the **page index grid**
  (`PageIndexActivity`) and the **link target picker** (`LinkTargetPickerActivity`), which read the
  `.soil` over raw read-only SQLite (not the DAO), so the rule is re-implemented there rather than reused.

⚠️ **Keep these two in sync.** If the page-name rule changes (tie-breaking, prefix stripping, fallback,
which heading wins), **both** `TocRepository` and `PageHeadingNames` must be updated together — otherwise
the TOC and the page-index/link labels will disagree.

> **Known gap (intentional, do not "fix" without discussion):** headings nested **inside link objects**
> are skipped by **both** the TOC and these page names — they never become a page's name. Noticed in
> testing 2026-06-18; left as-is for now.

---

## Text Object System

### Data Model

- `type = "text"` rows in `.soil`; `TextObject` (`data/TextObject.kt`) serialized to `data`; `TextRender` (`data/TextRender.kt`) is the in-memory representation
- `@Serializable data class TextObject(val text: String = "", val strokes: List<LiveStroke>? = null)` — raw Markdown source + optional embedded strokes (for lasso-converted objects where ML Kit failed or recognition was not run)
- `data class TextRender(val id: String, val boundingBox: RectF, val text: String, val strokes: List<LiveStroke>? = null)` — built at page load from `type = "text"` rows

**Bounding box width rule:** width = natural content width (max line width from `StaticLayout`), capped at page width only when content genuinely needs it. Never set `boundingBox.width = pageWidth` unconditionally. `TextObjectRenderer.measure()` returns this natural width; `availableWidthPx` is the layout constraint (wrapping ceiling), not the assigned width. Applies to all write paths: insert, edit, and lasso conversion. For unrecognized objects (blank text + strokes), bbox derives from the stroke union bounding box.

**`strokes` preservation rule:** All write paths (`updateTextObject`, `TextEdited` DB handler, `StrokesMoved` DB handler) preserve `strokes` in the serialized JSON — `TextObject(text = ..., strokes = target.strokes).toJson()`. Never construct `TextObject(text = ...)` alone for existing objects that may carry strokes.

### Render Dispatch

Text objects render after headings, before strokes — transparent background (no white fill, no template draw).

Dispatch is centralized in `drawTextObject(canvas, textRender, widthPx)` in each drawing view. All render sites (redrawCanvas, buildRenderBitmap, compositeTextObjects, drag layer) call this helper — never `TextObjectRenderer.draw()` directly:
- `text.isNotBlank()` → `TextObjectRenderer.draw()` (markdown engine)
- `text.isBlank()` AND `strokes` non-empty → render embedded strokes (unrecognized state)
- `text.isBlank()` AND `strokes` null/empty → render nothing

Rendering: `StaticLayout` + `TextPaint` at 16sp `Color.BLACK`. Entry point: `TextObjectRenderer.draw(canvas, textRender, widthPx, paint, density)`.

### Markdown Engine (`core/markdown/`)

- `MarkdownParser` — hand-rolled, no dependencies. Block types: `Heading(1–6)`, `Paragraph`, `ListItem`, `Blockquote`, `HorizontalRule`. Inline types: `Text`, `Bold`, `Italic`, `Strikethrough`, `Link(displayText, url)`.
- `MarkdownRenderer` — `List<Block>` → `SpannableStringBuilder` using Android text spans.
- `TextObjectRenderer` — wraps parser + renderer + `StaticLayout` for canvas drawing and measurement.

**Supported subset:**
- Headers h1–h6 (`#` … `######`)
- Bold (`**text**` / `__text__`), italic (`*text*` / `_text_`), strikethrough (`~~text~~`)
- Links (`[text](url)`) — underlined, not clickable
- Unordered lists, 3-level nesting, bullet glyphs: `• ◦ ▪`
- Ordered lists with auto-renumbering; nesting supported
- Task checkboxes (`- [ ]` → `☐`, `- [x]` → `☑`)
- Blockquotes (`>`) — left bar via `QuoteSpan`
- Horizontal rules (`---` / `***` / `___`) via `HorizontalRuleSpan : ReplacementSpan`

**Out of scope (do not add without discussion):** inline code, fenced code blocks, tables, embedded images, raw HTML.

**WYSIWYG regex safety:** inline patterns do NOT use `RegexOption.DOT_MATCHES_ALL` — use `[^*\n]` / `[^~\n]` exclusion classes to prevent cross-line matches.

### TextEditDialog (`notebook/TextEditDialog.kt`)

Markdown WYSIWYG/source editor. Two modes: **WYSIWYG** (live formatting spans) and **Markdown** (raw source). Mode toggle is two `AppCompatButton` (weight=1 each) with `bg_toolbar_button` — switching is instant (spans only, text unchanged).

Formatting toolbar (HorizontalScrollView): B, I, S̶, H▾ (H1–H6/Normal), •, 1., ☐ (task checkbox toggle), ❝, —, [⊞] (link). `shape_bordered` background, 36dp height, 10dp H padding.

Auto-renumbering: ordered list blocks renumbered on every `afterTextChanged`. Auto-continue: Enter at end of a numbered list line inserts the next number prefix.

AlertDialog pattern: `setSoftInputMode(SOFT_INPUT_STATE_VISIBLE | SOFT_INPUT_ADJUST_RESIZE)` before `show()`; `setElevation(0f)` + `shape_bordered` after `show()`. IME hidden in both Save and Cancel handlers via `editMarkdown.windowToken` (dialog's window token, not the activity's).

### Text Placement Mode

`btnInsertText` (`ic_text_recognition.xml`) — persistent toggle between EraseAll and Lasso in the NotebookActivity toolbar.

**Entering:** exits lasso/lasso-eraser; sets `isTextPlacementMode = true`; calls `drawingView.setTextPlacementMode(true)` → on Onyx: `setRawDrawingEnabled(false)`; calls `releaseRender()`. Sets `btnInsertText.isSelected = true`.

**Exiting:** sets `isTextPlacementMode = false`; calls `setTextPlacementMode(false)` + `enableDrawing()` to restore drawing state.

**Canvas tap:** `handleTextPlacementTouch` captures coordinates on `ACTION_DOWN` but does NOT fire the callback or exit until `ACTION_UP`. Exiting on DOWN would route subsequent MOVE/UP events to the normal drawing path, creating a phantom stroke persisted to DB. `onTextPlacementTap(tapX, tapY)` fires on `ACTION_UP`. The callback must NOT call `enableDrawing()` — drawing is restored by the dialog focus cycle (`onWindowFocusChanged(true)` → `openRawDrawing()`) after the stylus lifts. Calling `enableDrawing()` before the stylus lifts re-enables Onyx raw input mid-contact → `onBeginRawDrawing` → `onEndRawDrawing` → `onPenLifted` → phantom stroke persisted to DB. Stylus tool type only — finger touches ignored.

**`dispatchTouchEvent`** cancels placement mode on any toolbar touch, UNLESS the touch is on `btnInsertText` itself.

### Insert Flow (`insertTextObject`)

1. Measure markdown via `TextObjectRenderer.measure()` on `Dispatchers.Default`
2. Compute bounding box centered on tap, clamped to page bounds
3. Insert `type="text"` row + `invalidatePageSnapshot(db, pageId)` on `Dispatchers.IO`
4. Append `TextRender` to `drawingView.getTextObjects()` + `loadTextObjects(...)`
5. Rebuild render bitmap off-thread → swap via `loadStrokesWithBitmap`
6. Enter lasso mode, select new object, show floating toolbar
7. Push `UndoRedoAction.TextInserted(textId, pageId, layerId, textRender)`

### Tap-to-Edit

While a text object is selected (single selection in lasso mode), a stylus tap within `boundingBox` opens `TextEditDialog` pre-filled with raw Markdown. **Gated on `text.isNotBlank()`** — unrecognized objects (blank text + embedded strokes) ignore the tap.

- **Non-empty confirm → `updateTextObject`:** remeasures, keeps top-left fixed, clamps resized box to page bounds, persists, rebuilds bitmap, refreshes dashed selection overlay. Pushes `UndoRedoAction.TextEdited(textId, pageId, oldTextRender, newTextRender)`.
- **Empty confirm → `deleteTextObjectFromEdit`:** soft-deletes row, removes from in-memory list, clears selection. Pushes `UndoRedoAction.TextRemoved`. Embedded strokes are intentionally discarded — the user explicitly cleared the text.

### Lasso stroke-to-text Conversion (`convertLassoToText`)

"Text" button (`btnConvertText`, `ic_text_recognition`) in the floating lasso toolbar — visible only when `selectionIsPureStrokes`.

1. Run ML Kit recognition on selected strokes
2. **Success:** measure text, resize bbox (left/top anchored); `data.text = recognizedText`
3. **Failure:** keep original bbox; `data.text = ""`
4. Both cases: `data.strokes = embeddedStrokes` (fresh-UUID copies of selected stroke data)
5. Soft-delete original stroke rows + insert `type="text"` row in one transaction; invalidate snapshot; select new text object

Undo: `UndoRedoAction.TextConverted(textId, pageId, layerId, deletedAt, originalStrokeIds, textRender)` — undo restores original strokes and soft-deletes text row; redo reverses.

### Lasso Actions (text objects are full first-class participants)

All lasso actions work for text objects alongside strokes and headings with full undo/redo support:

- **Selection:** center-point containment hit test
- **Drag to move:** translates bbox; persisted via `updateHeadingData`; `StrokesMoved` undo; `strokes` field preserved
- **Delete:** `performLassoDelete` — soft-delete + `LassoDeleted` undo
- **Copy/Cut/Paste:** `NotesproutClipboard.ClipboardContent.textObjects: List<TextRender>` carries both `text` + `strokes`. Paste: new UUID, translated bbox. `LassoCut`/`LassoPasted` undo actions carry `textIds`/`textObjects`. Cross-page paste survives the round trip via the undo action.
- **Lasso eraser:** `runLassoHitTest` — center-point containment; `LassoErased.strokeIds` contains ALL erased IDs; `textIds` is the text-object subset stored separately for in-memory partitioning on undo.

### Canvas Integration

- `NotebookDao.getTextObjectsForLayer(layerId)` — `WHERE type = 'text'`; included in `getMaxContentUpdatedAt` staleness check
- `buildRenderBitmap` default parameter `textObjects: List<TextRender>? = null` — null = use stored field; non-null overrides (page load path, undo/redo call sites pass null)
- Snapshot fast-path: `compositeTextObjects(bitmap)` paints text objects onto the snapshot bitmap after `loadTextObjects()`, before `loadStrokesWithBitmap()`
- PDF export: `NotebookExporter.renderPage()` loads text objects via `getTextObjectsForLayer()` and renders after headings, before strokes

---

## Line Object System

### Data Model

- `type = "line"` rows in `.soil`; `LineObject` (`data/LineObject.kt`) serialized to `data`; `LineRender` (`data/LineRender.kt`) is the in-memory representation
- `@Serializable data class LineObject(val style: LineStyle, val orientation: LineOrientation, val strokeWidthDp: Float = 1f, val dotSpacingDp: Float = 0f)`
- `data class LineRender(val id, val boundingBox, val startX, startY, endX, endY, val style, val orientation, val strokeWidthDp, val dotSpacingPx)` — built at page load from `type = "line"` rows
- `LineStyle`: `SOLID`, `DASHED`, `DOTTED`. `LineOrientation`: `HORIZONTAL`, `VERTICAL`.
- `dotSpacingDp` persisted in `LineObject`; converted to `dotSpacingPx` at load time via `lo.dotSpacingDp * density` in `parseLineRender`.

**Bounding box:** exact line extent, inflated by `max(strokeWidth/2, 4dp)` on the perpendicular axis so center-point lasso hit tests work correctly.

### Insertion (`LineObjectDialog`)

`btnInsertLines` (`ic_density_small`) in the NotebookActivity toolbar. Opens `LineObjectDialog` — style toggle (Solid / Dashed / Dotted), orientation toggle (Horizontal / Vertical), editable fields for stroke width, line count, and margins (top/bottom/left/right, defaulting to `SNAP_MARGIN_DP`).

`buildLines()` computes all `LineRender` positions and dot spacing, then calls `onInsert(lines)` on confirm.

**Dot spacing rule:** spacing = line-to-line gap so the dot grid is square (equal gaps in both axes):
- Horizontal lines: `dotSpacingPx = usableHeight / (count - 1)`
- Vertical lines: `dotSpacingPx = usableWidth / (count - 1)`

This means the count of dots per row is determined by how many fit at that spacing, not forced to equal the line count.

### Rendering (`drawLineObject`)

Centralized in `drawLineObject(canvas, lineObj)` in each drawing view and `NotebookExporter.renderPage()`.

- `SOLID`: `canvas.drawLine(...)` with no path effect
- `DASHED`: `canvas.drawLine(...)` with `DashPathEffect([12dp, 8dp])`
- `DOTTED`: individual `canvas.drawCircle()` calls at `dotSpacingPx` intervals along the line. Dot radius = `strokeWidth / 2`. Fallback spacing `sw * 4f` when `dotSpacingPx` is zero (legacy rows).

Line color: `R.color.inkLight` (`#888888`) — intentionally lighter than strokes to feel like a page guide, not content.

### Lasso Integration

Lines participate in lasso selection (center-point containment hit test), move (via `updateHeadingData`), copy/cut/paste, delete, and lasso-eraser — identical to headings and text objects. All lasso actions carry lines through `StrokesMoved`, `LassoDeleted`, `LassoCut`, `LassoPasted`, `LassoErased` undo actions alongside strokes, headings, and text objects.

`NotesproutClipboard.ClipboardContent` carries `lineObjects: List<LineRender>`. Paste assigns new UUIDs and translates the bounding box.

### Undo/Redo

`UndoRedoAction.LineInserted(lineIds, pageId, layerId, lineObjects)` — undo soft-deletes all inserted line rows; redo re-inserts them. Cross-page undo/redo uses the same two-phase approach as other object types.

### Implementation Files

- `data/LineObject.kt` — `LineStyle`, `LineOrientation`, `LineObject` (DB/serialized)
- `data/LineRender.kt` — `LineRender` (in-memory render-time)
- `notebook/LineObjectDialog.kt` — insertion dialog
- `res/drawable/ic_density_small.xml` — toolbar icon (Tabler `density-small`)
- `res/layout/dialog_insert_lines.xml` — dialog layout
- Both drawing views + `NotebookExporter` — `drawLineObject` / `renderPage` line rendering
