package com.notesprout.android.notebook

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LinkRender
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TextRender

// Option B: buildRenderBitmap + loadStrokesWithBitmap — pre-build bitmap on IO thread

interface NotebookView {
    fun asView(): View

    /**
     * Set the toolbar exclusion zone, in drawing-view coordinates, that the BOOX pen layer must not
     * capture (so the stylus never draws under the bar). Null or empty ⇒ no exclusion. The toolbar
     * and the drawing view share the same origin/size inside the root FrameLayout, so the toolbar's
     * bounds *are* the exclusion rect; placement (top/bottom), an open overflow menu, etc. just pass
     * a different rect. Generic devices ignore this (the toolbar consumes its own touches).
     */
    fun setToolbarExclusion(rect: Rect?)

    /**
     * Re-arms the Onyx SDK pen layer with the current limit rect, forcing the new exclusion zone
     * to take effect immediately without waiting for a focus-change cycle. No-op on Generic devices.
     * Must only be called between strokes (not mid-contact). Respects all tool-state invariants.
     */
    fun reapplyDrawingBounds() {}

    fun enableDrawing()
    fun disableDrawing()
    fun resetOverlay() {}

    // Release the EPD writing overlay so the next screen refresh shows toolbar changes.
    // Called on any toolbar touch. BOOX: disables overlay render + invalidates.
    // Generic devices: no-op. The overlay is re-enabled by the next pen-down event.
    fun releaseRender() {}

    fun eraseAll()
    fun setEraserMode(active: Boolean) {}
    fun releaseResources()

    /**
     * Set the template bitmap to render as the page background, behind all stroke layers.
     * Pass null for a plain white background (no template).
     * The template is NOT erased by the eraser and is NOT saved as strokes.
     * Must be called on the main thread.
     */
    fun setTemplate(bitmap: Bitmap?) {}

    /**
     * Called by the activity when a stroke is erased in memory.
     * Receives the UUID of the erased stroke so the activity can soft-delete its DB row.
     * Set this before the first user interaction; null by default.
     */
    var onStrokeErased: ((strokeId: String) -> Unit)?

    /**
     * Called immediately after the pen lifts (onEndRawDrawing on Onyx; ACTION_UP on
     * Generic).  The activity uses this to incrementally save new strokes to the
     * database after each stroke without blocking the drawing thread.
     * The EPD overlay is NOT released here — it remains active until a non-writing
     * transition (tool change, page flip, page clear, window focus loss) triggers the
     * proper handoff.
     * Set this in onCreate; null by default.
     */
    var onPenLifted: (() -> Unit)?

    /**
     * Fired on the main thread when a snapshot has been captured at a non-writing
     * transition boundary (eraser mode, template change, window focus loss).
     * NotebookActivity wires this to [persistSnapshot] so the snapshot is written to
     * the page's `data` JSON in the DB.
     * Set this in onCreate; null by default.
     */
    var onSnapshotReady: ((snapshot: String) -> Unit)?

    // ── Lasso selection ───────────────────────────────────────────────────────

    /**
     * Activate or deactivate lasso mode.  In lasso mode the view ignores pen/eraser
     * input and instead tracks freehand stylus gestures for selection.
     * On Onyx devices this disables the EPD raw-drawing overlay so the lasso path can
     * be rendered through the normal Android Canvas.
     */
    fun setLassoMode(active: Boolean) {}

    /**
     * IDs of strokes currently selected by the lasso gesture.  Set by NotebookActivity
     * in [onLassoComplete] after the hit test, and cleared in [onLassoTapToDismiss].
     * The view uses this to identify which strokes participate in a lasso drag move.
     */
    var lassoSelectedIds: Set<String>
        get() = emptySet()
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    var isSnapEnabled: Boolean
        get() = false
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Enable or cancel an in-progress lasso drag move.  Passing false cancels any active
     * drag without committing the move (no DB write, no undo push, no stroke update).
     * Called by DrawingActivity when switching tools while a drag is in progress.
     */
    fun setDragMoveMode(enabled: Boolean) {}

    /**
     * Fired on the main thread when the stylus lifts after a lasso drag move that
     * exceeded the distance threshold.
     * [originalStrokes]/[originalHeadings] — deep copies at positions before drag.
     * [movedStrokes]/[movedHeadings]       — same objects with all coordinates translated.
     * NotebookActivity wires this to persist the new coordinates and push a [StrokesMoved]
     * undo action.
     */
    var onStrokesMoved: ((
        originalStrokes: List<LiveStroke>,
        movedStrokes: List<LiveStroke>,
        originalHeadings: List<HeadingStroke>,
        movedHeadings: List<HeadingStroke>,
        originalTextObjects: List<TextRender>,
        movedTextObjects: List<TextRender>,
        originalLineObjects: List<LineRender>,
        movedLineObjects: List<LineRender>,
        originalLinks: List<LinkRender>,
        movedLinks: List<LinkRender>,
    ) -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    // ── Lasso eraser ──────────────────────────────────────────────────────────

    /**
     * Activate or deactivate lasso eraser mode.  Gesture capture is identical to lasso
     * selection, but on gesture end the view runs the two-phase hit test internally and
     * fires [onLassoEraseComplete] with the IDs of hit strokes instead of handing the
     * raw path back to the activity.
     * On Onyx devices this disables the EPD raw-drawing overlay identically to [setLassoMode].
     */
    fun setLassoEraserMode(active: Boolean) {}

    /**
     * Fired on the main thread when the lasso eraser gesture completes and at least one
     * stroke was hit.  [erasedIds] is the list of stroke IDs that intersect the closed
     * lasso path.  NotebookActivity wires this to soft-delete those rows, update the
     * in-memory stroke list, and push a [UndoRedoAction.LassoErased] action.
     */
    var onLassoEraseComplete: ((erasedIds: List<String>) -> Unit)?

    /**
     * Set the visual lasso overlay: [path] is the live dashed outline drawn while the
     * stylus is down; [selectionBox] is the dashed bounding rect shown after lift.
     * Pass null for either (or both) to clear that element.
     */
    fun setLassoOverlay(path: Path?, selectionBox: RectF?) {}

    /**
     * Fired on the main thread when the stylus lifts after a lasso gesture.
     * [path] is the raw drawn path (not yet closed); [startPoint] is the first touch
     * coordinate.  DrawingActivity closes the path, runs the hit test, and calls
     * [setLassoOverlay] with the resulting selection box.
     */
    var onLassoComplete: ((path: Path, startPoint: PointF) -> Unit)?

    /**
     * Fired on the main thread on any touch (finger or stylus) when a selection box
     * is currently displayed.  DrawingActivity uses this to dismiss the selection and
     * restore the previous drawing tool.
     */
    var onLassoTapToDismiss: (() -> Unit)?

    /**
     * Fired on the main thread when a stylus tap (below [DRAG_THRESHOLD_DP])
     * occurs in lasso mode, regardless of whether a selection is active.
     * DrawingActivity uses this to trigger lasso paste when the clipboard has content
     * and no selection is currently active.
     */
    var onLassoTap: ((tapX: Float, tapY: Float) -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Activate or cancel text placement mode.  While active, the view intercepts the
     * next stylus ACTION_DOWN on the canvas, fires [onTextPlacementTap] with the tap
     * coordinates (in view/page space), and consumes the event so no stroke begins.
     * On Onyx devices, [setRawDrawingEnabled(false)] is called immediately so the EPD
     * overlay does not capture the next pen contact.
     */
    fun setTextPlacementMode(active: Boolean) {}

    /**
     * Fired on the main thread when a stylus ACTION_DOWN occurs while text placement
     * mode is active.  The coordinates are in view/page space.  The view exits placement
     * mode internally before firing — the caller is responsible for opening the editor
     * dialog and, on confirm, inserting the text object.
     */
    var onTextPlacementTap: ((tapX: Float, tapY: Float) -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Fired on the main thread when the lasso drag threshold is crossed for the first
     * time during an active drag move.  DrawingActivity uses this to hide the floating
     * selection toolbar during the drag, reshowing it via [onStrokesMoved] on completion.
     */
    var onDragStarted: (() -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Fired on the main thread when an ACTION_DOWN in lasso mode starts a fresh gesture
     * (clearing any existing selection box).  DrawingActivity uses this to hide the
     * floating selection toolbar immediately rather than waiting for gesture completion.
     */
    var onLassoSelectionCleared: (() -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Inject a selection into the drawing view from outside (e.g., after paste).
     * Sets [lassoSelectedIds] and calls [setLassoOverlay] with [box] in one step,
     * triggering a full EPD handoff on Onyx devices so the selection box is visible.
     */
    fun setLassoSelectedIds(ids: Set<String>, box: RectF) {}

    /**
     * Replace the in-memory heading list with [headings] loaded from the database.
     * Call before [loadStrokes] or [loadStrokesWithBitmap] so the heading backgrounds
     * are included in the next canvas redraw.  Must be called on the main thread.
     */
    fun loadHeadings(headings: List<HeadingStroke>) {}

    /**
     * Return a snapshot of the current in-memory heading list.
     */
    fun getHeadings(): List<HeadingStroke> = emptyList()

    /**
     * Replace the in-memory text object list with [textObjects] loaded from the database.
     * Call before [loadStrokes] or [loadStrokesWithBitmap] so text objects are included
     * in the next canvas redraw.  Must be called on the main thread.
     */
    fun loadTextObjects(textObjects: List<TextRender>) {}

    /**
     * Return the current in-memory text object list.
     * Safe to call from any thread — text objects are replaced atomically and the list
     * itself is immutable, so a stale reference is still safe to read.
     */
    fun getTextObjects(): List<TextRender> = emptyList()

    /**
     * Paint the current [textObjects] onto [bitmap] using the view's text paint.
     * Called from displayPage on the snapshot fast-path: the snapshot bitmap contains
     * strokes and headings but NOT text objects (which are always loaded fresh from DB).
     * Must be called on the main thread after [loadTextObjects].
     */
    fun compositeTextObjects(bitmap: Bitmap) {}

    /**
     * Replace the in-memory line object list with [lineObjects] loaded from the database.
     * Call before [loadStrokes] or [loadStrokesWithBitmap] so lines are included in the
     * next canvas redraw.  Must be called on the main thread.
     */
    fun loadLineObjects(lineObjects: List<LineRender>) {}

    /**
     * Return the current in-memory line object list.
     * Safe to call from any thread — line objects are replaced atomically.
     */
    fun getLineObjects(): List<LineRender> = emptyList()

    /**
     * Paint the current [lineObjects] onto [bitmap].
     * Called from displayPage on the snapshot fast-path: lines are always loaded fresh from DB
     * (identical reason to text objects).  Must be called on the main thread after [loadLineObjects].
     */
    fun compositeLineObjects(bitmap: Bitmap) {}

    /**
     * Replace the in-memory link object list with [links] loaded from the database.
     * Call before [loadStrokes] or [loadStrokesWithBitmap] so links (embedded content + chrome)
     * are included in the next canvas redraw.  Must be called on the main thread.
     */
    fun loadLinks(links: List<LinkRender>) {}

    /**
     * Return the current in-memory link object list.
     * Safe to call from any thread — links are replaced atomically.
     */
    fun getLinks(): List<LinkRender> = emptyList()

    /**
     * Paint the current links onto [bitmap].
     * Called from displayPage on the snapshot fast-path: links are always loaded fresh from DB
     * (identical reason to text/line objects).  Must be called on the main thread after [loadLinks].
     */
    fun compositeLinks(bitmap: Bitmap) {}

    /**
     * Fired on the main thread when a fast, closed pen gesture in pen mode encloses or
     * crosses at least one object on the current layer (smart-lasso detection).
     * Fires INSTEAD OF [onPenLifted] or [onScribbleEraseComplete] — it is the first gate
     * in the pen-lift detection chain (smart-lasso → scribble-to-erase → normal stroke).
     *
     * The gesture stroke itself is NOT saved to the DB — it is removed from the in-memory
     * stroke list by the view before this fires and is never persisted.
     *
     * [hitIds] — IDs of all objects enclosed by the gesture path.
     * [unionBounds] — union of the bounding boxes of all hit objects (un-padded).
     *
     * On Onyx devices the EPD overlay render is already released before this fires.
     * The activity must enter lasso mode, set [lassoSelectedIds], show the selection
     * overlay, and display the floating selection toolbar — do NOT call [onPenLifted].
     */
    var onSmartLassoComplete: ((hitIds: List<String>, unionBounds: android.graphics.RectF) -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Fired on the main thread when a pen stroke is judged a scribble AND its path
     * crosses at least one existing object (stroke, heading, or text object) on the page.
     * Fires INSTEAD OF [onPenLifted] for confirmed scribble-erase gestures.
     *
     * The scribble stroke itself is a pure gesture — it is removed from the in-memory
     * stroke list by the view before this fires and is NEVER saved to the DB.
     *
     * [erasedObjectIds] — IDs of all content objects the scribble path touched.
     * [erasedHeadings] — full [HeadingStroke] data for the heading subset of [erasedObjectIds],
     *   captured at gesture time for undo restoration without a DB round-trip.
     * [erasedTextObjects] — full [TextRender] data for the text-object subset, likewise.
     *
     * On Onyx devices the EPD overlay render is already released before this fires.
     * The activity must rebuild the bitmap via [buildRenderBitmap] + [loadStrokesWithBitmap]
     * to commit the erasure to the screen.
     */
    var onScribbleEraseComplete: ((
        erasedObjectIds: List<String>,
        erasedHeadings: List<HeadingStroke>,
        erasedTextObjects: List<TextRender>,
        erasedLineObjects: List<LineRender>,
        erasedLinks: List<LinkRender>,
    ) -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Fired on the main thread when the eraser path intersects a heading's bounding box.
     * The heading has already been removed from the view's in-memory list before this fires.
     * NotebookActivity wires this to soft-delete the heading row from the DB and push an
     * undo action. The full [HeadingStroke] is passed so the caller has its data for undo.
     */
    var onHeadingErased: ((heading: HeadingStroke) -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Fired on the main thread when the eraser path intersects a text object's bounding box.
     * The text object has already been removed from the view's in-memory list before this fires.
     * NotebookActivity wires this to soft-delete the row from the DB and push an undo action.
     * The full [TextRender] is passed so the caller has its data for undo restoration.
     */
    var onTextErased: ((textObject: TextRender) -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Fired on the main thread when the eraser path intersects a line object's bounding box.
     * The line has already been removed from the view's in-memory list before this fires.
     * NotebookActivity wires this to soft-delete the row from the DB and push an undo action.
     * The full [LineRender] is passed so the caller has its data for undo restoration.
     */
    var onLineErased: ((lineObject: LineRender) -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Fired on the main thread when the eraser path intersects a link object's bounding box.
     * The link has already been removed from the view's in-memory list before this fires.
     * NotebookActivity wires this to soft-delete the link row from the DB and push an undo action.
     * The full [LinkRender] is passed so the caller has its data for undo restoration.
     */
    var onLinkErased: ((linkObject: LinkRender) -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Replace the in-memory stroke list with [strokes] loaded from the database,
     * then redraw the canvas bitmap immediately.
     * Must be called on the main thread (triggers invalidate).
     */
    fun loadStrokes(strokes: List<LiveStroke>)

    /**
     * Return a snapshot of the current in-memory stroke list.
     * Safe to call from any thread — implementations return a copy.
     */
    fun getStrokes(): List<LiveStroke>

    /**
     * Update the in-memory stroke list without triggering a canvas redraw or EPD repaint.
     * Used after a snapshot fast-load to silently populate stroke data in the background
     * while the snapshot composite bitmap is already displayed on screen.
     * Must be called on the main thread.
     */
    fun setStrokeListSilently(strokes: List<LiveStroke>)

    /**
     * Capture the current strokes as a base64-encoded PNG with a transparent background.
     * The template is NOT included — the rendering stack is: template → snapshot → new strokes.
     * Returns null if there are no strokes or the view is not yet laid out.
     * Safe to call from the main thread.
     */
    fun captureSnapshot(): String?

    // ── Option B: off-thread bitmap pre-build ─────────────────────────────────

    /**
     * Build the full render bitmap (white → [templateBitmap] → [headings] embedded strokes →
     * [strokes]) on a background thread and return it, or null if
     * the view isn't laid out yet.
     *
     * [headings] defaults to empty — existing call sites that don't yet pass headings
     * continue to work correctly.  Pass [getHeadings()] for calls that should include
     * the currently loaded heading objects.
     *
     * Thread-safe: reads [View.width]/[View.height] only (stable after layout);
     * does NOT call invalidate or modify any view state.  The returned Bitmap is
     * not yet attached to the view — hand it to [loadStrokesWithBitmap] on the
     * main thread to swap it in.
     */
    /**
     * Build the full render bitmap on a background thread.
     *
     * [textObjects]: when non-null, render these text objects. When null (the default),
     * implementations fall back to the stored field set by [loadTextObjects] — this lets
     * existing undo/redo call sites omit the argument and still render text objects
     * correctly (the field is set once by [displayPage] and doesn't change during a
     * stroke-only undo/redo operation).
     */
    fun buildRenderBitmap(
        strokes: List<LiveStroke>,
        templateBitmap: Bitmap?,
        headings: List<HeadingStroke> = emptyList(),
        textObjects: List<TextRender>? = null,
        lineObjects: List<LineRender>? = null,
        links: List<LinkRender>? = null,
    ): Bitmap?

    /**
     * Swap in a [bitmap] pre-built by [buildRenderBitmap], update the in-memory
     * stroke list, and store [templateBitmap] for future canvas redraws (e.g. after
     * erasing).  Replaces the main-thread O(N) redraw that [loadStrokes] would do.
     *
     * Must be called on the main thread — triggers invalidate (and EPD handoff on
     * Onyx devices).
     */
    fun loadStrokesWithBitmap(strokes: List<LiveStroke>, bitmap: Bitmap, templateBitmap: Bitmap?)
}
