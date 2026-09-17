package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.RasterRubbing
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.notebook.PaperToolbar
import com.symmetricalpalmtree.notesproutsn.notebook.PenIdle

/**
 * The sketch screen's chrome (arc 43 / K5): Back, the pencil and the rubber, "Bring in ink" and
 * "Show pages" on the top bar; the pager alone on the bottom one. The tool half is `:sn-screen`'s
 * [PaperToolbar] — with **no lasso button**, which is what `btnLasso`'s nullability is for: a raster
 * page has no objects to select, so a lasso there would arm a tool the surface does not answer.
 *
 * **The tools are fixed, and they are the artist's** (decisions 3 and 4, as measured at K1):
 *
 * - One pencil: [StrokeStyle.PENCIL] at [PENCIL_WIDTH_PX] px in [PENCIL_COLOR] — no width, no
 *   colour, no style to choose, nothing remembered. Paintsprout's pencil, unchanged.
 * - The **rubbing** eraser at [ERASER_RADIUS_PX] px on g-paper's [RasterRubbing] defaults: within
 *   the radius the alpha is lifted a fraction per pass, so a light pass softens a line and a few
 *   firm passes take it out. It is not a stroke eraser and there is no sub-bar — there are no
 *   strokes to lasso.
 * - **No pen gestures.** `smartLassoEnabled` and `scribbleEraseEnabled` are both off, set by the
 *   screen before the listener attaches: a hatch is not a scribble and a closed shading loop is not
 *   a selection.
 *
 * **No engine tuning is set here.** The engine's constants *are* the K1 measurements — the
 * pencil bakes at a constant pressure 0.5 under a DARK_GRAY needle preview, the erase redraw runs at
 * 16 ms, the EMR hairline floor is 120, the bake is upright whatever the pen's lean (g-paper
 * 0.1.34 froze the first four, 0.1.35 added the last; the `RattaTuning` door of
 * 0.1.32–0.1.33 is gone) — and a host that re-stated any of them would be a second place for them
 * to drift.
 *
 * **The arrows no-op at a bound, never disable.** A greyed control is invisible on e-ink (the
 * standing rule), so the buttons always look the same and a turn at either edge simply stays put —
 * the host answers the same page and the screen compares its key ([PageTurn.isEdge]).
 *
 * **The indicator waits for the pen.** Never present an app frame while [PaperView.isPenActive] —
 * the rule is SN-wide, and this bar is the screen's only text that changes.
 */
class SketchToolbar(
    private val paper: PaperView,
    topBar: View,
    btnBack: ImageButton,
    btnPencil: ImageButton,
    btnEraser: ImageButton,
    private val btnBringInk: ImageButton,
    private val btnShowPages: ImageButton,
    private val btnPrevPage: ImageButton,
    private val btnNextPage: ImageButton,
    private val pageIndicator: TextView,
    onBack: () -> Unit,
    onBringInk: () -> Unit,
    onShowPages: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    /** Any actual tool change — the screen takes down anything that belonged to the old tool. */
    onToolTapped: () -> Unit,
    /** After every sync (arc 36) — the collapsed chrome's corner button repaints from here. */
    onSynced: () -> Unit = {},
    /**
     * The debug-only fill door (see [SketchActivity]): a long press on the page indicator composites
     * a test pattern, so an adb walk can produce a non-blank save with no pen in the room. Null in
     * release, where the listener is never attached at all.
     */
    onIndicatorLongPress: (() -> Unit)? = null,
) {

    private val tools: PaperToolbar

    init {
        paper.tool = Tool.PEN
        paper.penStyle = StrokeStyle.PENCIL
        paper.penWidth = PENCIL_WIDTH_PX
        paper.penColor = PENCIL_COLOR
        paper.eraserRadius = ERASER_RADIUS_PX
        paper.rasterRubbing = RasterRubbing()

        tools = PaperToolbar(
            bar = topBar,
            btnBack = btnBack,
            btnPen = btnPencil,
            btnEraser = btnEraser,
            // No lasso on a raster page — the whole reason `PaperToolbar` grew a nullable one.
            btnLasso = null,
            paper = paper,
            onBack = onBack,
            // A re-tap on the armed eraser opens the Point · Lasso sub-bar on every other paper
            // screen. Here there is no second eraser to reach, so it is honestly nothing.
            onEraserReTap = {},
            onToolTapped = onToolTapped,
            onSynced = onSynced,
        )

        listOf(btnBringInk, btnShowPages, btnPrevPage, btnNextPage).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnBringInk.setOnClickListener { releaseRenderIfIdle(); onBringInk() }
        btnShowPages.setOnClickListener { releaseRenderIfIdle(); onShowPages() }
        btnPrevPage.setOnClickListener { releaseRenderIfIdle(); onPrevPage() }
        btnNextPage.setOnClickListener { releaseRenderIfIdle(); onNextPage() }
        pageIndicator.text = ""
        if (onIndicatorLongPress != null) {
            pageIndicator.setOnLongClickListener { onIndicatorLongPress(); true }
        }
    }

    /** Make the tool buttons honest — driven from `PaperListener.onToolChanged`, never from a tap. */
    fun sync(tool: Tool) = tools.sync(tool)

    /** Arm [tool] from the host side and sync the buttons — what the mini toolbar's pick lands on. */
    fun arm(tool: Tool) = tools.arm(tool)

    /** `n / m`, presented only once the pen is idle (the frame-silence rule). */
    fun setPage(number: Int, total: Int) {
        val text = pageIndicator.context.getString(R.string.sketch_page_indicator, number, total)
        whenPenIdle { if (pageIndicator.text != text) pageIndicator.text = text }
    }

    private fun whenPenIdle(action: () -> Unit) = PenIdle.whenIdle(paper, pageIndicator, action)

    /** The [PaperView.releaseRender] contract: pen-gated, or a tap inside the pen-up tail can cost a
     *  live stroke. While the pen is active nobody is looking at a pressed state anyway. */
    private fun releaseRenderIfIdle() = PenIdle.releaseRenderIfIdle(paper)

    companion object {
        /** The one pencil width, in px — Paintsprout's, as the artist approved it. */
        const val PENCIL_WIDTH_PX = 1.2f

        /** The one graphite tone. */
        const val PENCIL_COLOR = 0xFF505050.toInt()

        /** The rubber's radius, in px — g-paper 0.1.30's rubbing eraser at its default lift. */
        const val ERASER_RADIUS_PX = 12f
    }
}
