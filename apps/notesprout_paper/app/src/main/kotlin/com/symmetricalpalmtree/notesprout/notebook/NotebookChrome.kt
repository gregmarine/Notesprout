package com.symmetricalpalmtree.notesprout.notebook

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.symmetricalpalmtree.gpaper.core.PaperView

/**
 * The notebook screen's chrome geometry (arc 5 / C1 — a pure move out of `NotebookActivity`, which
 * sits at the 800-line cap): the exclusion rects the stylus must not ink under, and the
 * "is this touch over chrome" test the finger gestures and the EPD chrome-release use.
 *
 * Exclusion: while [blockAll] (the notebook not yet open — the "Opening…" popup — or the Contents
 * dialog showing) the **whole paper** is excluded; otherwise the top bar + bottom strip + the
 * selection toolbar's rects, in paper coordinates. Applied to the hardware pen layer and filtered
 * model-side by g-paper.
 */
class NotebookChrome(
    private val paper: PaperView,
    private val topBar: View,
    private val bottomStrip: View,
    private val selectionToolbar: SelectionToolbar,
    private val blockAll: () -> Boolean,
) {
    fun pushExclusions() {
        val view = paper.asView()
        if (blockAll()) {
            paper.setExclusionRects(listOf(Rect(0, 0, maxOf(view.width, 1), maxOf(view.height, 1))))
            return
        }
        val paperLoc = IntArray(2).also { view.getLocationInWindow(it) }
        val rects = (listOfNotNull(NotebookToolbar.rectOf(topBar), NotebookToolbar.rectOf(bottomStrip)) + selectionToolbar.rects())
            .map { Rect(it.left - paperLoc[0], it.top - paperLoc[1], it.right - paperLoc[0], it.bottom - paperLoc[1]) }
        paper.setExclusionRects(rects)
    }

    fun overChrome(ev: MotionEvent): Boolean {
        val top = NotebookToolbar.rectOf(topBar)
        val bottom = NotebookToolbar.rectOf(bottomStrip)
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return (top?.contains(x, y) == true) || (bottom?.contains(x, y) == true) || selectionToolbar.contains(x, y)
    }
}
