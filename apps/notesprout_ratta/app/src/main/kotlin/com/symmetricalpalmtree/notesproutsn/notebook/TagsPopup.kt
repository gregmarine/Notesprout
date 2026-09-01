package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.symmetricalpalmtree.notesproutsn.R

/**
 * The tag button's secondary toolbar (arc 21 / W2) — a small bordered bar hung under `ic_tag` in
 * the top bar's right cluster, holding the notebook's three tag doors:
 *
 *  - **Tag notebook** — the tag screen in ADD mode on this notebook, field focused;
 *  - **Tag page** — the same, on the page whose ink is on the paper;
 *  - **Manage tags** — the notebook and every one of its pages, for add and remove.
 *
 * There are three because a tag has to land on something, and which of the two things it lands on
 * is the only question the button cannot answer for you. The first two are the quick doors — one
 * tap to a focused field — and Manage is the one that shows the whole notebook at once.
 *
 * **Icon-only with long-press hints** (the user's W2 call): the bar is the notebook's, and every
 * floating bar in this screen speaks in glyphs at [R.dimen.toolbar_button_size].
 *
 * Placement, the button recipe and the rects are [AnchoredBar]'s — the same bar the arc-8 lasso
 * popup hangs, and one shape has one implementation. The screen owns *when* it closes (a tool
 * switch, a page swap, a finger gesture, an outside tap, a door taken) and unions [rects] into the
 * exclusion rects and `overChrome`.
 */
class TagsPopup(
    root: ViewGroup,
    bar: LinearLayout,
    /** The `ic_tag` top-bar button the popup hangs under. */
    anchor: View,
    /** The free band's bottom edge in root coordinates (the bottom strip's top); null before layout. */
    bandBottom: () -> Int?,
    private val releaseRender: () -> Unit,
    private val onTagNotebook: () -> Unit,
    private val onTagPage: () -> Unit,
    private val onManage: () -> Unit,
) {

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)

    val isShowing: Boolean get() = bar.isShowing

    init {
        val ctx = root.context
        this.bar.addButton(R.drawable.ic_notebook, ctx.getString(R.string.tag_notebook_action)) {
            releaseRender()
            onTagNotebook()
        }
        this.bar.addButton(R.drawable.ic_page, ctx.getString(R.string.tag_page_action)) {
            releaseRender()
            onTagPage()
        }
        this.bar.addButton(R.drawable.ic_list, ctx.getString(R.string.tag_manage_action)) {
            releaseRender()
            onManage()
        }
    }

    fun show(): Boolean = bar.show()

    /** Idempotent — every dismiss path calls it without checking. */
    fun hide() = bar.hide()

    /** The visible popup's rect in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = bar.rects()

    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)
}
