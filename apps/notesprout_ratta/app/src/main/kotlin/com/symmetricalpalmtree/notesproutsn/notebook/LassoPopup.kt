package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.symmetricalpalmtree.notesproutsn.R

/**
 * The lasso button's popup (arc 8): a small bordered bar hung under the **already-armed** lasso
 * button, holding **Paste** and **Clear** for the object clipboard.
 *
 * It exists because tap-to-place is invisible. A pen tap on bare paper pastes, and the only other
 * hint is the button's own clipboard-marked icon — so the two acts that have no gesture (paste at
 * the *source* coordinates, and throw the clipboard away) need somewhere to live, and the armed
 * lasso button is the one control that already means "the clipboard is in play".
 *
 * **It opens only while the clipboard holds objects.** With a page on the clipboard — or nothing —
 * a second tap on the armed lasso stays P1's silent no-op: the popup is absent rather than open and
 * half-empty, which is the same rule as the page sheet's absent Paste row (a control that cannot
 * work is not shown greyed; on e-ink a greyed control is invisible anyway).
 *
 * Placement, the button recipe and the rects are [AnchoredBar]'s — arc 21's tag popup needed the
 * same bar, and one shape has one implementation.
 *
 * The screen owns *when* it closes: a tool switch, a page swap, a finger gesture, an outside tap,
 * a paste, a clear. It also unions [rects] into the exclusion rects and counts it as chrome for the
 * finger paths — a pen landing on the popup must never ink, and a finger tapping it must not be
 * read as a page gesture.
 */
class LassoPopup(
    root: ViewGroup,
    bar: LinearLayout,
    /** The armed lasso button the popup hangs under. */
    anchor: View,
    /** The free band's bottom edge in root coordinates (the bottom strip's top); null before layout. */
    bandBottom: () -> Int?,
    private val releaseRender: () -> Unit,
    private val onPaste: () -> Unit,
    private val onClear: () -> Unit,
) {

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)

    val isShowing: Boolean get() = bar.isShowing

    init {
        val ctx = root.context
        this.bar.addButton(R.drawable.ic_clipboard, ctx.getString(R.string.paste_objects_action)) {
            releaseRender()
            onPaste()
        }
        this.bar.addButton(R.drawable.ic_trash, ctx.getString(R.string.clear_clipboard_action)) {
            releaseRender()
            onClear()
        }
    }

    fun show(): Boolean = bar.show()

    /** Idempotent — every dismiss path calls it without checking. */
    fun hide() = bar.hide()

    /** The visible popup's rect in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = bar.rects()

    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)
}
