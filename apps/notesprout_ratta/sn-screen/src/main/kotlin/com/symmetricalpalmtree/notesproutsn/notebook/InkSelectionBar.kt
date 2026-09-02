package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.notesproutsn.screen.R

/**
 * The floating selection bar an **ink-on-paper extension screen** puts next to a lasso selection
 * (arc 11 / J4 as the pad's own, the calendar's copy at arc 23 / Y1, **one class here** since the
 * arc-23 sweep): **Send to Notebook** when a notebook is behind us, **Delete** last.
 *
 * The shape is the shared decision, and it is why this is not simply a [FloatingSelectionBar] call
 * at each consumer: Move is the drag itself and neither screen has headings, links, clipboard or
 * snap, so the notebook's seven buttons come down to the one that always applied plus the one that
 * pays for the hop — **Send first, Delete last**, the notebook's order, with the one destructive
 * verb on the far edge. Both release the render before their row runs: the tap has to show its
 * result, and the delete repaints the page underneath.
 *
 * Send is **absent, never disabled**, when there is no notebook behind us — a greyed control is
 * invisible on e-ink. That is what a null [sendHint] means; the hint itself is the consumer's own
 * wording, which is all that ever differed between the two copies.
 */
class InkSelectionBar(
    root: ViewGroup,
    paperView: View,
    bar: LinearLayout,
    /** The free band in root coordinates: the top bar's bottom edge .. the bottom bar's top. */
    band: () -> IntRange?,
    releaseRender: () -> Unit,
    /** Delete's hint (tooltip + content description). */
    deleteHint: String,
    onDelete: () -> Unit,
    /** Send's hint, or **null** when there is no notebook behind us — then Send is never built. */
    sendHint: String? = null,
    onSend: () -> Unit = {},
) {

    private val floating = FloatingSelectionBar(
        root = root,
        paperView = paperView,
        bar = bar,
        band = band,
        buttons = buildList {
            if (sendHint != null) {
                add(FloatingSelectionBar.Button(R.drawable.ic_pencil_down, sendHint) {
                    releaseRender(); onSend()
                })
            }
            add(FloatingSelectionBar.Button(R.drawable.ic_trash, deleteHint) {
                releaseRender(); onDelete()
            })
        },
    )

    fun show(bounds: Bounds) = floating.show(bounds)
    fun hide() = floating.hide()
    fun rects(): List<Rect> = floating.rects()
    fun contains(x: Int, y: Int): Boolean = floating.contains(x, y)
}
