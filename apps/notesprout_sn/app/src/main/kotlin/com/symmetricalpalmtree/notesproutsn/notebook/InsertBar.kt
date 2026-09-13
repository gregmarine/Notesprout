package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import com.symmetricalpalmtree.notesproutsn.R

/**
 * The Insert button's sub-bar (arc 28 / H1, D4) — a small bordered bar hung under `ic_plus` in the
 * notebook's top bar, holding the things a page can be given that are not ink:
 *
 *  **Sticky · Text · Rectangle · Ellipse · Triangle · Line · Arrow · Star · Bible reference**
 *
 * left to right, in that order, always — arc 38 / R3 appended the ninth, and appended it rather
 * than placing it beside Text because the eight were measured against the Nomad's bar as a row and
 * a new kind joins the end of that row, never the middle of it.
 *
 * Placement, the button recipe and the rects are [AnchoredBar]'s — the arc-8 lasso popup's bar and the arc-21 tag bar's, and one shape has one
 * implementation.
 *
 * **Insert is a command, not a tool.** Picking one of these places an object at the page centre and
 * lands it selected; the armed tool is exactly what it was, and nothing here is remembered or
 * stays armed for the next tap.
 *
 * **Every button is [offer]ed by its own phase, and hidden until then** (J4: GONE, never disabled
 * — a control that does nothing does not exist, and on e-ink a greyed control is invisible anyway).
 * H2 offered [Kind.TEXT], H4 the six shapes ([shapeType] is the mapping) and H5 [Kind.STICKY], so
 * the arc-28 eight are offered in every build. **[Kind.BIBLE] is the one that still comes and goes**
 * (arc 38 / R3): it is offered only while a trusted Bible reader that understands references is
 * installed, re-offered from the entry's own discovery on every resume — the same rule the lasso
 * bar's Bible button follows, and the reason [offer] stayed a verb rather than becoming history.
 *
 * The screen owns *when* it closes — a pick, another bar button, a tool switch, a page swap, a
 * finger gesture, an outside tap — and unions [rects] into the exclusion rects and the
 * `overChrome` test, because a pen landing on a floating bar must never ink.
 */
class InsertBar(
    root: ViewGroup,
    bar: LinearLayout,
    /** The `ic_plus` top-bar button the sub-bar hangs under. */
    anchor: View,
    /** The free band's bottom edge in root coordinates (the bottom strip's top); null before layout. */
    bandBottom: () -> Int?,
    private val releaseRender: () -> Unit,
    private val onInsert: (Kind) -> Unit,
) {

    /** What the buttons insert — the order they sit in is this enum's order (D4), and a new kind
     *  is appended, never inserted. [BIBLE] is arc 38 / R3's. */
    enum class Kind { STICKY, TEXT, RECTANGLE, ELLIPSE, TRIANGLE, LINE, ARROW, STAR, BIBLE }

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)

    private val buttons: Map<Kind, AppCompatImageButton>

    val isShowing: Boolean get() = bar.isShowing

    init {
        val ctx = root.context
        buttons = Kind.entries.associateWith { kind ->
            this.bar.addButton(iconOf(kind), ctx.getString(hintOf(kind))) {
                releaseRender()
                onInsert(kind)
            }.also { it.visibility = View.GONE }
        }
    }

    /** Offer (or withdraw) one kind. Its phase turns it on; nothing else does. */
    fun offer(kind: Kind, offered: Boolean) {
        buttons[kind]?.visibility = if (offered) View.VISIBLE else View.GONE
    }

    /** Whether anything at all is on offer — `true` in every build since H2 offered Text. */
    val hasOffers: Boolean get() = buttons.values.any { it.visibility == View.VISIBLE }

    /** Open under the Insert button — or under [anchor] when named: the collapsed chrome's own
     *  Insert button (arc 36), because the bar's is inside a `GONE` bar and keeps stale edges. */
    fun show(anchor: View? = null): Boolean = if (anchor == null) bar.show() else bar.show(anchor)

    /** Idempotent — every dismiss path calls it without checking. */
    fun hide() = bar.hide()

    /** The visible bar's rect in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = bar.rects()

    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)

    companion object {

        /**
         * Which [ShapeType] a kind places, or null for the two that are not shapes — the mapping
         * the screen routes an insert through, here because the enum is this class's.
         */
        fun shapeType(kind: Kind): ShapeType? = when (kind) {
            Kind.STICKY, Kind.TEXT, Kind.BIBLE -> null
            Kind.RECTANGLE -> ShapeType.RECTANGLE
            Kind.ELLIPSE -> ShapeType.ELLIPSE
            Kind.TRIANGLE -> ShapeType.TRIANGLE
            Kind.LINE -> ShapeType.LINE
            Kind.ARROW -> ShapeType.ARROW
            Kind.STAR -> ShapeType.STAR
        }

        /** Tabler, all of them already in `drawable/` — checked before drawing anything (the
         *  arc's standing trap: og has these icons and SN copied them at H1). */
        private fun iconOf(kind: Kind): Int = when (kind) {
            Kind.STICKY -> R.drawable.ic_sticker_2
            Kind.TEXT -> R.drawable.ic_text_recognition
            Kind.RECTANGLE -> R.drawable.ic_shape_rectangle
            Kind.ELLIPSE -> R.drawable.ic_shape_ellipse
            Kind.TRIANGLE -> R.drawable.ic_shape_triangle
            Kind.LINE -> R.drawable.ic_shape_line
            Kind.ARROW -> R.drawable.ic_shape_arrow
            Kind.STAR -> R.drawable.ic_shape_star
            Kind.BIBLE -> R.drawable.ic_bible
        }

        /** Icon-only with a long-press hint, the recipe every floating bar in this screen follows. */
        private fun hintOf(kind: Kind): Int = when (kind) {
            Kind.STICKY -> R.string.insert_sticky
            Kind.TEXT -> R.string.insert_text
            Kind.RECTANGLE -> R.string.insert_rectangle
            Kind.ELLIPSE -> R.string.insert_ellipse
            Kind.TRIANGLE -> R.string.insert_triangle
            Kind.LINE -> R.string.insert_line
            Kind.ARROW -> R.string.insert_arrow
            Kind.STAR -> R.string.insert_star
            Kind.BIBLE -> R.string.insert_bible_reference
        }
    }
}
