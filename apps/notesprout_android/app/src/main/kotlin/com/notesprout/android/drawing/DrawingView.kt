package com.notesprout.android.drawing

import android.graphics.Bitmap
import android.view.View
import com.notesprout.android.data.LiveStroke

// Option A: stroke cache key — see DrawingActivity.strokeCache
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
