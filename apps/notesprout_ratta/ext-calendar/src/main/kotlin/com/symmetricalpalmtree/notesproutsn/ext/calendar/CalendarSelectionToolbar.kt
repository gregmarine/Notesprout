package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.notesproutsn.notebook.FloatingSelectionBar

/**
 * The calendar's floating selection bar (arc 23 / Y1), thin on `:sn-screen`'s
 * [FloatingSelectionBar]: **Move** is the drag itself, so the bar holds **Send to Notebook** when a
 * notebook is behind us and **Delete** last — the wizard's list, in the notebook's order (the one
 * destructive verb on the far edge). Send is absent, never disabled, when there is no notebook.
 */
class CalendarSelectionToolbar(
    root: ViewGroup,
    paperView: View,
    bar: LinearLayout,
    band: () -> IntRange?,
    releaseRender: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit = {},
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
                add(FloatingSelectionBar.Button(R.drawable.ic_pencil_down, ctx.getString(R.string.cd_calendar_send_selection)) {
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
