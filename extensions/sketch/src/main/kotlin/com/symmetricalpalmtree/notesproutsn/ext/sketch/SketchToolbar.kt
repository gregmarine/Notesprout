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
 * The sketch screen's chrome (arc 43 / K5, grown by arc 44 / T3 and arc 46 "Palette"): Back, the
 * pencil, the gel pen, the rubber and the shade panel's door, "Bring in ink" and "Show pages" on
 * the top bar; the pager alone on the bottom one. The
 * tool half is `:sn-screen`'s [PaperToolbar] — with **no lasso button**, which is what `btnLasso`'s
 * nullability is for: a raster page has no objects to select, so a lasso there would arm a tool the
 * surface does not answer.
 *
 * ## The tools (decisions 3 and 4, as arcs 44 and 46 amended them)
 *
 * - **A graphite pencil of one width and sixteen shades** ([SketchPalette] — the whole grey
 *   ladder, every level previewing as it bakes on the direct panel path, at 4 px). Arc 43's
 *   decision 3 was "no tilt, no width choice, no colour", and the first two thirds of that stood
 *   on measurement rather than taste: the Supernote bakes upright whatever the grip (g-paper
 *   0.1.35), so a sketch could not be shaded by leaning even in principle. Arc 44 opened the other
 *   route — choose the tone instead of leaning for it — and arc 46 closed the width choice again
 *   at a real pencil's size. **Its button wears its shade**, as a fill inside a glyph whose
 *   outline stays solid black ([reportShades]).
 * - **One gel pen**: `StrokeStyle.PEN`, 5 px — the width the hand settled on — in **any of the
 *   same sixteen shades** (arc 46, decision 3); its button wears its own shade the same way. It
 *   and the pencil are **both [Tool.PEN]** to the engine; what differs is only what the engine is
 *   armed with, which is what [SketchToolState] holds and this class assigns.
 * - **The Palette button** opens the shade panel ([PaletteBar]) under itself, editing the armed
 *   kind's shade; a second tap closes it.
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
 * g-paper 0.1.41–0.1.43 is what makes the shades honest on Ratta: the pencil and the pen go direct
 * to the panel and the glass shows a blue-noise dither of the true grey, so every level previews as
 * it bakes; `RattaInkMap.pencilPreviewFor`'s four-tone ladder is the needle fallback only.
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
    /** The shade panel's door (arc 46 "Palette"). */
    private val btnPalette: ImageButton,
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
    /** A tap on the Palette button (arc 46) — the screen toggles its [PaletteBar] under it. */
    onPalette: () -> Unit = {},
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
     * The two pen buttons' glyphs: each an outline over a body that carries that kind's shade
     * ([ShadeIcon]). Built here rather than left to the layout's `android:src` because they are
     * two-layer drawables whose fill this class re-inks — the layout's own `ic_pen` / `ic_ballpen`
     * is what each button wears for the instant before these lines run.
     */
    private val pencilIcon = ShadeIcon.pencil(btnPencil.context, SketchToolState.DEFAULT.pencilReport)
        .also { btnPencil.setImageDrawable(it) }
    private val penIcon = ShadeIcon.pen(btnPen.context, SketchToolState.DEFAULT.penReport)
        .also { btnPen.setImageDrawable(it) }

    /** The shades the two buttons are actually wearing — ARGBs, so the compare is the fill itself
     *  and two levels that render the same could never both repaint. Null until the first report. */
    private var reportedPencil: Int? = null
    private var reportedPen: Int? = null

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
            // Arc 46: a re-tap on the armed pencil is nothing now — the shade panel has its own door.
        )

        listOf(btnPalette, btnBringInk, btnShowPages, btnPrevPage, btnNextPage).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnPalette.setOnClickListener { releaseRenderIfIdle(); onPalette() }
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
     * a shade from the [PaletteBar].
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
        reportShades(state)
    }

    /**
     * How the two pen buttons **report their shades** — arc 44 / T3's phase-start question,
     * answered by the user on 2026-09-17 for the Pencil and extended to the gel pen by arc 46's
     * decision 6: **each glyph's body is filled with that kind's shade, and its outline stays solid
     * black.** Black is a completely black glyph; every other level is a black outline with that
     * grey inside it ([ShadeIcon] draws it and says why a fill rather than a tint of the whole
     * glyph).
     *
     * **Greys are ink**, and this is the one place outside [PaletteBar]'s swatches where an ink is
     * *reported* rather than chosen — the root `CLAUDE.md`'s standing exception, "the pen button's
     * icon tinted with the armed ink", in its fill form, twice.
     *
     * **Each shade is its kind's, not the armed kind's** ([SketchToolState.pencilReport] /
     * [SketchToolState.penReport]), so while the other kind or the rubber is armed a button still
     * shows what a tap on it will bring back.
     *
     * It is called from [assign] alone — every state change already passes through there, the
     * restore at open included, so no path can be left out — and it is never inside a stroke: the
     * calls are a deliberate tap, or the restore before the screen is even open. Unchanged is
     * silent, per button, which is the frame-silence rule for a button nobody asked to repaint.
     */
    private fun reportShades(state: SketchToolState) {
        val pencil = state.pencilReport
        if (pencil != reportedPencil) {
            reportedPencil = pencil
            ShadeIcon.tint(pencilIcon, pencil)
        }
        val pen = state.penReport
        if (pen != reportedPen) {
            reportedPen = pen
            ShadeIcon.tint(penIcon, pen)
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

        /** The rubber's radius, in px — g-paper 0.1.30's rubbing eraser at its default lift. The
         *  eraser has no options and is not remembered, so this is the one number left here; the
         *  pencil's and the pen's widths live in [SketchPalette]. */
        const val ERASER_RADIUS_PX = 12f
    }
}
