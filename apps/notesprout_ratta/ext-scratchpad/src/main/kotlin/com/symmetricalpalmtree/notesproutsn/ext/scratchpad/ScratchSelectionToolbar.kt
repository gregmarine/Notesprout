package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.notesproutsn.notebook.FloatingSelectionBar

/**
 * The pad's floating selection bar (arc 11 / J4, grown in J5; thin on `:sn-screen`'s
 * [FloatingSelectionBar] since arc 23 / Y1): **Delete**, and **Send** when the pad was opened from
 * a notebook — the pad has no headings, no links, no clipboard and no snap, so the notebook's seven
 * buttons come down to the one that always applied plus the one that pays for the hop. Send is
 * absent, never disabled, when there is no notebook behind us.
 *
 * Send first, Delete last — the notebook's selection bar puts its one destructive verb on the far
 * edge and the pad's tools are the notebook's, so its bar reads the same way. Both release the
 * render before their row runs: the tap has to show its result, and the delete repaints the page
 * underneath.
 */
class ScratchSelectionToolbar(
    root: ViewGroup,
    paperView: View,
    bar: LinearLayout,
    /** The free band in root coordinates: the top bar's bottom edge .. the bottom bar's top. */
    band: () -> IntRange?,
    releaseRender: () -> Unit,
    onDelete: () -> Unit,
    /** Send the selected strokes to the notebook (J5). Never called when [sendEnabled] is false. */
    onSend: () -> Unit = {},
    /** Whether a notebook is behind us — false from the library, and then Send is never built. */
    sendEnabled: Boolean = false,
) {

    private val floating = FloatingSelectionBar(
        root = root,
        paperView = paperView,
        bar = bar,
        band = band,
        buttons = buildList {
            val ctx = bar.context
            if (sendEnabled) {
                add(FloatingSelectionBar.Button(R.drawable.ic_pencil_down, ctx.getString(R.string.cd_scratch_send_selection)) {
                    releaseRender(); onSend()
                })
            }
            add(FloatingSelectionBar.Button(R.drawable.ic_trash, ctx.getString(R.string.delete_selection_action)) {
                releaseRender(); onDelete()
            })
        },
    )

    val isShowing: Boolean get() = floating.isShowing
    fun show(bounds: Bounds) = floating.show(bounds)
    fun hide() = floating.hide()
    fun rects(): List<Rect> = floating.rects()
    fun contains(x: Int, y: Int): Boolean = floating.contains(x, y)
}
