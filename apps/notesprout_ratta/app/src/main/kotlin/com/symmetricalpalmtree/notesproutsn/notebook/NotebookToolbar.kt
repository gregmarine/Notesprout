package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.InkColorCodec
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNotebookBinding

/**
 * The notebook's chrome: back, the three tool buttons, and the two slide-down panels that
 * configure the pen and the eraser. It owns every tool decision — the activity hands it the
 * binding, the surface and the prefs, and never touches `paper.penWidth` itself.
 *
 * Three rules shape the whole class:
 *  - **Release the render first — but pen-gated.** Every handler calls [releaseRenderIfIdle]
 *    before it does anything else: while the EPD writing overlay is armed the panel simply will
 *    not show a pressed state or a newly opened panel, and the tap reads as broken. The gate is
 *    the [PaperView.releaseRender] API contract — an ungated release inside the pen-active
 *    window can cost a live stroke.
 *  - **[sync] is the truth, not our taps.** g-paper changes tools by itself (smart lasso arms
 *    LASSO and restores PEN when the selection goes), so button state is driven from
 *    `PaperListener.onToolChanged` — never assumed from the tap that started it.
 *  - **Selected = the bordered `state_selected` look of `bg_toolbar_button`.** No colour anywhere
 *    in the chrome except the greyscale ink ladder, where the grey *is* the thing being chosen.
 *
 * Panel content is built in code because it is generated content — five widths, five styles,
 * sixteen ink levels, four eraser radii — and the layout only carries the empty rows. Every
 * tap-sized child reads `@dimen/toolbar_button_size`; nothing here hardcodes a tap target.
 *
 * Opening or closing a panel changes the top bar's height, which moves the pen-exclusion rect.
 * That is the activity's job — it watches the bar's layout and re-pushes
 * [PaperView.setExclusionRects]. This class only toggles visibility.
 */
class NotebookToolbar(
    private val binding: ActivityNotebookBinding,
    private val paper: PaperView,
    private val prefs: ToolPrefs,
    private val onBack: () -> Unit,
) {

    private val ctx: Context = binding.root.context
    private val density = ctx.resources.displayMetrics.density
    private val ink = ContextCompat.getColor(ctx, R.color.inkBlack)
    private val buttonSize = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
    private val buttonPadding = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)

    /** The panel currently on screen, or null. Only ever one — they are mutually exclusive. */
    private var openPanel: View? = null

    /** True while a tool panel is showing — the activity dismisses it on a finger tap on the page. */
    val panelOpen: Boolean get() = openPanel != null

    private lateinit var widths: Group<Float>
    private lateinit var styles: Group<StrokeStyle>
    private lateinit var inks: Group<Int>
    private lateinit var radii: Group<Float>

    init {
        // Arm the surface with the remembered tool before anything is drawn or shown.
        paper.tool = Tool.PEN
        paper.penColor = armedInk()
        paper.penWidth = prefs.penWidthPx
        paper.penStyle = prefs.penStyle
        paper.eraserRadius = prefs.eraserRadiusPx

        with(binding) {
            listOf(btnBack, btnPen, btnEraser, btnLasso).forEach {
                TooltipCompat.setTooltipText(it, it.contentDescription)
            }
            btnBack.setOnClickListener {
                releaseRenderIfIdle()
                closePanels()
                onBack()
            }
            btnPen.setOnClickListener { onToolTap(Tool.PEN, penPanel) }
            btnEraser.setOnClickListener { onToolTap(Tool.ERASER, eraserPanel) }
            btnLasso.setOnClickListener { onToolTap(Tool.LASSO, null) }
        }

        buildWidthRow()
        buildStyleRow()
        buildInkRows()
        buildEraserRow()

        sync(paper.tool)
    }

    // ── Tool arming ──────────────────────────────────────────────────────────

    /**
     * Tapping an unarmed tool arms it; tapping the armed one opens (or closes) its panel. Lasso
     * has nothing to configure, so tapping it a second time is deliberately nothing at all —
     * better than a panel that exists only to be empty.
     */
    private fun onToolTap(tool: Tool, panel: View?) {
        releaseRenderIfIdle()
        if (paper.tool != tool) {
            paper.tool = tool
            closePanels()
            sync(tool)
            Slog.d(TAG) { "armed $tool" }
        } else if (panel != null) {
            togglePanel(panel)
        }
    }

    /**
     * Make the buttons honest about [tool]. Called from `PaperListener.onToolChanged` — the
     * component arms and restores tools on its own (smart lasso), so this runs for changes we
     * never initiated. A panel whose tool is no longer armed closes with it.
     */
    fun sync(tool: Tool) = with(binding) {
        btnPen.isSelected = tool == Tool.PEN
        btnEraser.isSelected = tool == Tool.ERASER
        btnLasso.isSelected = tool == Tool.LASSO
        val owner = when (openPanel) {
            penPanel -> Tool.PEN
            eraserPanel -> Tool.ERASER
            else -> null
        }
        if (owner != null && owner != tool) closePanels()
    }

    // ── Panels ───────────────────────────────────────────────────────────────

    private fun togglePanel(panel: View) {
        if (openPanel === panel) closePanels() else showPanel(panel)
    }

    private fun showPanel(panel: View) {
        val previous = openPanel
        if (previous != null && previous !== panel) previous.visibility = View.GONE
        panel.visibility = View.VISIBLE
        openPanel = panel
        Slog.d(TAG) { "panel open: ${if (panel === binding.penPanel) "pen" else "eraser"}" }
    }

    /** Close whatever is open. Public: the activity closes panels on a page tap and on page turns. */
    fun closePanels() {
        openPanel?.visibility = View.GONE
        openPanel = null
    }

    /**
     * The API contract for [PaperView.releaseRender]: guard with [PaperView.isPenActive] so a
     * resting palm (or a tap landing inside the pen-up tail) can never cost a live stroke. While
     * the pen is active the user is not looking at chrome pressed-states anyway — skipping the
     * release costs nothing.
     */
    private fun releaseRenderIfIdle() {
        if (!paper.isPenActive) paper.releaseRender()
    }

    // ── Pen width ────────────────────────────────────────────────────────────

    private fun buildWidthRow() {
        val entries = ToolPrefs.WIDTHS.map { width ->
            val view = AppCompatImageView(ctx).apply {
                background = toolbarButtonBackground()
                scaleType = ImageView.ScaleType.CENTER
                setImageDrawable(widthDot(width))
                contentDescription = ctx.getString(R.string.cd_pen_width_fmt, width.toInt())
                isClickable = true
                isFocusable = true
                stateListAnimator = null
                TooltipCompat.setTooltipText(this, contentDescription)
                setOnClickListener { applyWidth(width) }
            }
            binding.penWidthRow.addView(view, itemParams(buttonSize))
            width to view
        }
        widths = Group(entries, ToolPrefs.DEFAULT_WIDTH)
        widths.select(prefs.penWidthPx)
    }

    private fun applyWidth(width: Float) {
        releaseRenderIfIdle()
        paper.penWidth = width
        prefs.penWidthPx = width
        widths.select(width)
    }

    /**
     * A filled inkBlack dot whose diameter reads the width back to the user: `6 + 3 × width` dp,
     * capped at the button's content box so the widest dot still sits inside its border.
     */
    private fun widthDot(width: Float): Drawable {
        val cap = buttonSize - 2 * buttonPadding
        val size = dp(6f + 3f * width).coerceIn(dp(4f), cap)
        return ShapeDrawable(OvalShape()).apply {
            paint.color = ink
            setIntrinsicWidth(size)
            setIntrinsicHeight(size)
        }
    }

    // ── Pen style ────────────────────────────────────────────────────────────

    private fun buildStyleRow() {
        val entries = STYLES.map { (style, labelRes) ->
            val label = ctx.getString(labelRes)
            val view = textButton(label, label) { applyStyle(style) }
            binding.penStyleRow.addView(view, itemParams(ViewGroup.LayoutParams.WRAP_CONTENT))
            style to view
        }
        styles = Group(entries, StrokeStyle.PEN)
        styles.select(prefs.penStyle)
    }

    private fun applyStyle(style: StrokeStyle) {
        releaseRenderIfIdle()
        paper.penStyle = style
        prefs.penStyle = style
        styles.select(style)
    }

    // ── Ink ──────────────────────────────────────────────────────────────────

    /**
     * The sixteen-level greyscale ladder — the levels e-paper actually renders, and the one
     * sanctioned appearance of "colour" in this app's chrome: the grey *is* what is being chosen.
     * Row 1 is levels 0–7 (black up), row 2 is 8–15 (up to white).
     */
    private fun buildInkRows() {
        val armed = armedInk()
        val entries = (0..15).map { level ->
            val color = inkForLevel(level)
            val cd = ctx.getString(R.string.cd_ink_fmt, level)
            val view = View(ctx).apply {
                background = toolbarButtonBackground()
                foreground = swatchFace(color)
                contentDescription = cd
                isClickable = true
                isFocusable = true
                stateListAnimator = null
                TooltipCompat.setTooltipText(this, cd)
                setOnClickListener { applyInk(color) }
            }
            val row = if (level < 8) binding.inkRow1 else binding.inkRow2
            row.addView(view, itemParams(buttonSize))
            color to view
        }
        inks = Group(entries, InkColorCodec.BLACK)
        inks.select(armed)
    }

    private fun applyInk(color: Int) {
        releaseRenderIfIdle()
        paper.penColor = color
        prefs.penInk = color
        inks.select(color)
    }

    /**
     * The remembered ink, snapped onto the ladder. In R3 the panel is the only writer of
     * `penInk`, so an off-ladder value can only be a stale or hand-edited pref — black is the
     * default, and snapping keeps the armed ink and the selected swatch telling the same story.
     */
    private fun armedInk(): Int {
        val stored = prefs.penInk
        if ((0..15).any { inkForLevel(it) == stored }) return stored
        Slog.d(TAG) { "ink pref off-ladder — falling back to black" }
        prefs.penInk = InkColorCodec.BLACK
        return InkColorCodec.BLACK
    }

    /** Level 0 = black, 15 = white; `level × 17` walks 0…255 in even steps. */
    private fun inkForLevel(level: Int): Int {
        val grey = level * 17
        return InkColorCodec.BLACK or (grey shl 16) or (grey shl 8) or grey
    }

    /**
     * The swatch face: the grey fill, ringed by an always-visible 1 dp inkBlack outline. Without
     * the ring the white swatch is simply not there on paper — an invisible tap target.
     */
    private fun swatchFace(color: Int): Drawable {
        val hairline = dp(1f).coerceAtLeast(1)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(hairline, ink)
            cornerRadius = dp(2f).toFloat()
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(2f).toFloat()
        }
        val inset = (buttonSize - swatchFaceSize()) / 2
        return LayerDrawable(arrayOf(ring, fill)).apply {
            setLayerInset(0, inset, inset, inset, inset)
            // The fill sits a hairline inside the ring, so the outline survives on every level.
            setLayerInset(1, inset + hairline, inset + hairline, inset + hairline, inset + hairline)
        }
    }

    /** Small enough that `bg_toolbar_button`'s selected border reads around the swatch. */
    private fun swatchFaceSize(): Int = (buttonSize - 2 * dp(8f)).coerceAtLeast(dp(12f))

    // ── Eraser ───────────────────────────────────────────────────────────────

    private fun buildEraserRow() {
        val entries = ToolPrefs.ERASER_RADII.map { radius ->
            val cd = ctx.getString(R.string.cd_eraser_radius_fmt, radius.toInt())
            val view = textButton(radius.toInt().toString(), cd) { applyEraser(radius) }
            binding.eraserRadiusRow.addView(view, itemParams(ViewGroup.LayoutParams.WRAP_CONTENT))
            radius to view
        }
        radii = Group(entries, ToolPrefs.DEFAULT_ERASER)
        radii.select(prefs.eraserRadiusPx)
    }

    private fun applyEraser(radius: Float) {
        releaseRenderIfIdle()
        paper.eraserRadius = radius
        prefs.eraserRadiusPx = radius
        radii.select(radius)
    }

    // ── Shared view construction ─────────────────────────────────────────────

    /**
     * A word, not a glyph — the panel labels read better than icons on e-ink. Same bordered
     * `state_selected` look as every other selectable item, same tap height.
     */
    private fun textButton(label: String, cd: String, onClick: () -> Unit): AppCompatButton =
        AppCompatButton(ctx).apply {
            text = label
            contentDescription = cd
            textSize = 13f
            setTextColor(ink)
            setAllCaps(false)
            gravity = Gravity.CENTER
            background = toolbarButtonBackground()
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10f), 0, dp(10f), 0)
            TooltipCompat.setTooltipText(this, cd)
            setOnClickListener { onClick() }
        }

    /** A fresh drawable per view — a shared instance would share its selected state too. */
    private fun toolbarButtonBackground(): Drawable? =
        ContextCompat.getDrawable(ctx, R.drawable.bg_toolbar_button)

    private fun itemParams(width: Int) =
        LinearLayout.LayoutParams(width, buttonSize).apply { marginEnd = dp(4f) }

    private fun dp(value: Float): Int = (value * density).toInt()

    /**
     * One selection per group. The panel stays open while the user composes width + style + ink,
     * so the groups have to keep their own view references to repaint the selected state.
     */
    private class Group<T>(
        private val entries: List<Pair<T, View>>,
        private val default: T,
    ) {
        fun select(value: T) {
            // An out-of-set value selects the group's default instead — no group is ever blank.
            val target = if (entries.any { it.first == value }) value else default
            entries.forEach { (candidate, view) -> view.isSelected = candidate == target }
        }
    }

    private companion object {
        const val TAG = "NotebookToolbar"

        /** The five pen types R3 offers, in panel order. */
        val STYLES = listOf(
            StrokeStyle.PEN to R.string.style_pen,
            StrokeStyle.FOUNTAIN to R.string.style_fountain,
            StrokeStyle.MARKER to R.string.style_marker,
            StrokeStyle.PENCIL to R.string.style_pencil,
            StrokeStyle.BRUSH to R.string.style_brush,
        )
    }
}
