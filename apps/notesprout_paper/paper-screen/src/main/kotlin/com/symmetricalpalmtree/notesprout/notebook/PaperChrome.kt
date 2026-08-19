package com.symmetricalpalmtree.notesprout.notebook

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.symmetricalpalmtree.gpaper.core.PaperView

/**
 * A paper-hosting screen's chrome geometry (arc 5 / C1 — a pure move out of `NotebookActivity`;
 * arc 6 / S0 — moved to `:paper-screen` as `PaperChrome`, the selection toolbar replaced by two
 * suppliers so the Scratch Pad can pass its own): the exclusion rects the stylus must not ink
 * under, and the "is this touch over chrome" test the finger gestures and the EPD chrome-release use.
 *
 * Exclusion: while [blockAll] (the notebook not yet open — the "Opening…" popup — or the Contents
 * dialog showing) the **whole paper** is excluded; otherwise the top bar + bottom strip + the
 * [extraRects] (window coordinates — the host's floating selection toolbar), in paper coordinates.
 * Applied to the hardware pen layer and filtered model-side by g-paper. [extraContains] is the
 * matching hit test for [overChrome] (view-local x/y of the host's root).
 */
class PaperChrome(
    private val paper: PaperView,
    private val topBar: View,
    private val bottomStrip: View,
    private val extraRects: () -> List<Rect>,
    private val extraContains: (Int, Int) -> Boolean,
    private val blockAll: () -> Boolean,
) {
    fun pushExclusions() {
        val view = paper.asView()
        if (blockAll()) {
            paper.setExclusionRects(listOf(Rect(0, 0, maxOf(view.width, 1), maxOf(view.height, 1))))
            return
        }
        val paperLoc = IntArray(2).also { view.getLocationInWindow(it) }
        val rects = (listOfNotNull(PaperToolbar.rectOf(topBar), PaperToolbar.rectOf(bottomStrip)) + extraRects())
            .map { Rect(it.left - paperLoc[0], it.top - paperLoc[1], it.right - paperLoc[0], it.bottom - paperLoc[1]) }
        paper.setExclusionRects(rects)
    }

    fun overChrome(ev: MotionEvent): Boolean {
        val top = PaperToolbar.rectOf(topBar)
        val bottom = PaperToolbar.rectOf(bottomStrip)
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return (top?.contains(x, y) == true) || (bottom?.contains(x, y) == true) || extraContains(x, y)
    }
}
