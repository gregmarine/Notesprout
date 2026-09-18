package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.RasterRubbing
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.notesproutsn.notebook.PaperToolbar
import com.symmetricalpalmtree.notesproutsn.notebook.PenIdle

/**
 * The sketch screen's chrome (arc 43 / K5, grown by arc 44 / T3): Back, the pencil, the gel pen and
 * the rubber, "Bring in ink" and "Show pages" on the top bar; the pager alone on the bottom one. The
 * tool half is `:sn-screen`'s [PaperToolbar] — with **no lasso button**, which is what `btnLasso`'s
 * nullability is for: a raster page has no objects to select, so a lasso there would arm a tool the
 * surface does not answer.
 *
 * ## The tools (decisions 3 and 4, as arc 44 amended them)
 *
 * - **A graphite pencil with six shades and twelve leads** ([SketchPalette] — six levels of the
 *   grey ladder, three of them previewing exactly as they bake, and leads from 1.2 to 96 px). Arc
 *   43's decision 3
 *   was "no tilt, no width choice, no colour", and the first two thirds of that stood on measurement
 *   rather than taste: the Supernote bakes upright whatever the grip (g-paper 0.1.35), so a sketch
 *   could not be shaded by leaning even in principle. The user's decision of 2026-09-17 opens the
 *   other route — choose the tone and the lead instead of leaning for them. **Its button wears the
 *   armed shade**, as a fill inside a glyph whose outline stays solid black ([reportShade]).
 * - **One gel pen**: `StrokeStyle.PEN`, black, 5 px — the width the hand settled on. One size, one
 *   tone, nothing to choose. It and the pencil are **both [Tool.PEN]** to the engine; what differs
 *   is only what the engine is armed with, which is what [SketchToolState] holds and this class
 *   assigns.
 * - The **rubbing** eraser at [ERASER_RADIUS_PX] px on g-paper's [RasterRubbing] defaults: within
 *   the radius the alpha is lifted a fraction per pass, so a light pass softens a line and a few
 *   firm passes take it out. It is not a stroke eraser and there is no sub-bar — there are no
 *   strokes to lasso. **It is never remembered**: a face that opened on the eraser would read as a
 *   broken pencil, and the seam says so too.
 * - **No pen gestures.** `smartLassoEnabled` and `scribbleEraseEnabled` are both off, set by the
 *   screen before the listener attaches: a hatch is not a scribble and a closed shading loop is not
 *   a selection.
 *
 * **No engine tuning is set here.** The engine's constants *are* the K1 measurements — the pencil
 * bakes at a constant pressure 0.5 and at tilt 0 whatever the pen's lean, the erase redraw runs at
 * 16 ms, the EMR hairline floor is 120 (g-paper 0.1.34 froze the first and the last two, 0.1.35
 * added the upright bake; the `RattaTuning` door of 0.1.32–0.1.33 is gone) — and a host that
 * re-stated any of them would be a second place for them to drift. **What the host does set is the
 * pen itself**: style, width and colour, which are the person's choices and were never the engine's.
 * g-paper 0.1.36 is what makes the shades honest on Ratta — the firmware paints one tone per pen,
 * so the live line cannot be the true grey, and `RattaInkMap.pencilPreviewFor` maps each shade to
 * the nearest of the three usable firmware tones while the bake stays the shade that was chosen.
 * The heaviest leads want one thing more: g-paper **0.1.37** lifts `RattaEmr.EMR_MAX` from 12 px
 * to 96, the top of [SketchPalette.SIZES_PX], so every lead previews at the width it bakes.
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
    /** The gel pen (arc 44 / T3) — [Tool.PEN]'s second **kind**, beside the pencil's. */
    btnPen: ImageButton,
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
    /** Any actual tool change — the screen takes down anything that belonged to the old tool. A
     *  pencil↔gel-pen switch is one of these, even though `paper.tool` never moves for it. */
    onToolTapped: () -> Unit,
    /** A tap on the **already-armed** pencil (arc 44 / T3) — the screen toggles its [PencilBar]
     *  under it. A re-tap on the armed gel pen is nothing: it has no options. */
    onPencilReTap: () -> Unit = {},
    /** A pen **kind** was tapped (arc 44 / T3): the screen applies the new state ([apply]) and
     *  remembers it on the device. It fires before the tool is armed — the firmware pen is re-armed
     *  from the colour and width, so the kind has to be in place first. */
    onPenKindPicked: (alt: Boolean) -> Unit = {},
    /** After every sync (arc 36) — the collapsed chrome's corner button repaints from here. */
    onSynced: () -> Unit = {},
    /**
     * The debug-only fill door (see [SketchActivity]): a long press on the page indicator composites
     * a test pattern, so an adb walk can produce a non-blank save with no pen in the room. Null in
     * release, where the listener is never attached at all.
     */
    onIndicatorLongPress: (() -> Unit)? = null,
) {

    /**
     * What the tools are set to. It lives here because the *engine* cannot hold it: both kinds are
     * [Tool.PEN], so "which pen is armed" has nowhere else to be, and the shade and the lead are
     * three properties of one pen rather than a tool each.
     */
    private var toolState: SketchToolState = SketchToolState.DEFAULT

    /** The armed tools, for the screen's own pushes and for the bars that paint from them. */
    val state: SketchToolState get() = toolState

    private val tools: PaperToolbar

    /**
     * The Pencil button's glyph: the outline over a body that carries the armed shade
     * ([PencilIcon]). Built here rather than left to the layout's `android:src` because it is a
     * two-layer drawable whose fill this class re-inks — the layout's own `ic_pen` is what the
     * button wears for the instant before this line runs.
     */
    private val pencilIcon = PencilIcon.filled(btnPencil.context, SketchToolState.DEFAULT.reportedShade)
        .also { btnPencil.setImageDrawable(it) }

    /** The shade the button is actually wearing — an ARGB, so the compare is the fill itself and
     *  two levels that render the same could never both repaint. Null until the first report. */
    private var reportedShade: Int? = null

    init {
        paper.tool = Tool.PEN
        paper.eraserRadius = ERASER_RADIUS_PX
        paper.rasterRubbing = RasterRubbing()
        // The defaults, until the host answers what this device remembers (`SketchActivity`
        // restores them before the first mark is possible). Assigned directly rather than through
        // [apply], which syncs a toolbar that does not exist yet.
        assign(toolState)

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
            // Arc 44 / T3: the pen's two kinds. `altPenArmed` is read at every sync, never cached
            // there — this field is the one copy of that answer.
            btnAltPen = btnPen,
            altPenArmed = { toolState.isPen },
            onPenKindPicked = onPenKindPicked,
            onPenReTap = onPencilReTap,
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

    /**
     * Arm [state] — the one door every tool choice comes through: the restore at open, a kind tap,
     * a shade or a lead from the [PencilBar].
     *
     * It **syncs the bar** as well as the engine, because which of the two pen buttons reads as
     * armed follows the *kind* and the tool may not have moved at all (a pencil↔pen switch under an
     * armed pen, a restore that opens on the gel pen). It deliberately does **not** remember
     * anything on the device: the screen owns that push, so a restore cannot echo straight back to
     * the host it just came from.
     */
    fun apply(state: SketchToolState) {
        assign(state)
        tools.sync(paper.tool)
    }

    /**
     * Put the armed pen back on the engine after something else has been drawn with it — the ink
     * bake and the debug fill door.
     *
     * Those two composite **strokes**, and a raster bake takes each stroke's own colour, width and
     * style (`addStrokes` in `PageMode.RASTER`), never the armed pen's — so today nothing here is
     * actually disturbed. This is the one line that keeps that true if it ever changes, and it costs
     * three property writes on a path the person has just spent a Binder round trip on.
     */
    fun restorePen() = assign(toolState)

    private fun assign(state: SketchToolState) {
        toolState = state
        paper.penStyle = state.penStyle
        paper.penWidth = state.penWidth
        paper.penColor = state.penColor
        reportShade(state.reportedShade)
    }

    /**
     * How the Pencil button itself **reports the armed shade** — arc 44 / T3's phase-start
     * question, answered by the user on 2026-09-17: **the pencil's body is filled with the shade,
     * and its outline stays solid black.** Black armed is a completely black pencil; every other
     * level is a black outline with that grey inside it ([PencilIcon] draws it and says why a fill
     * rather than a tint of the whole glyph).
     *
     * **Greys are ink**, and this is the one place outside [PencilBar]'s swatches where the armed
     * ink is *reported* rather than chosen — the root `CLAUDE.md`'s standing exception, "the pen
     * button's icon tinted with the armed ink", in its fill form. The gel pen's button never
     * changes: it has one tone and nothing to report.
     *
     * **The shade is the pencil's, not the armed kind's** ([SketchToolState.reportedShade]), so
     * while the gel pen or the rubber is armed the button still shows what a tap on it will bring
     * back.
     *
     * It is called from [assign] alone — every state change already passes through there, the
     * restore at open included, so no path can be left out — and it is never inside a stroke: the
     * calls are a deliberate tap, or the restore before the screen is even open. Unchanged is
     * silent, which is the frame-silence rule for a button nobody asked to repaint.
     */
    private fun reportShade(ink: Int) {
        if (ink == reportedShade) return
        reportedShade = ink
        PencilIcon.tint(pencilIcon, ink)
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

        /** The rubber's radius, in px — g-paper 0.1.30's rubbing eraser at its default lift. The
         *  eraser has no options and is not remembered, so this is the one number left here; the
         *  pencil's and the pen's live in [SketchPalette]. */
        const val ERASER_RADIUS_PX = 12f
    }
}
