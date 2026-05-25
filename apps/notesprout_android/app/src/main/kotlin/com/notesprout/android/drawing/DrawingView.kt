package com.notesprout.android.drawing

import android.graphics.Bitmap
import android.view.View
import com.notesprout.android.data.LiveStroke

// Option B: buildRenderBitmap + loadStrokesWithBitmap — pre-build bitmap on IO thread

interface DrawingView {
    fun asView(): View
    fun setToolbarHeight(heightPx: Int)
    fun enableDrawing()
    fun disableDrawing()
    fun resetOverlay() {}
    fun clearCanvas()
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
     * DrawingActivity wires this to [persistSnapshot] so the snapshot is written to
     * the page's `data` JSON in the DB.
     * Set this in onCreate; null by default.
     */
    var onSnapshotReady: ((snapshot: String) -> Unit)?

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
     * Build the full render bitmap (white → [templateBitmap] → all [strokes]) on
     * a background thread and return it, or null if the view isn't laid out yet.
     *
     * Thread-safe: reads [View.width]/[View.height] only (stable after layout);
     * does NOT call invalidate or modify any view state.  The returned Bitmap is
     * not yet attached to the view — hand it to [loadStrokesWithBitmap] on the
     * main thread to swap it in.
     */
    fun buildRenderBitmap(strokes: List<LiveStroke>, templateBitmap: Bitmap?): Bitmap?

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
