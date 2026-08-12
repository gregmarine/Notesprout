package com.notesprout.android.notebook

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import com.notesprout.android.R
import com.notesprout.android.core.InkColor

/**
 * The pen-colour panel: two palettes of swatches, docked to whichever pen button opens it.
 *
 * Written once and shared by all five drawing hosts (notebook, scratch pad, calendar, day detail,
 * sticky-note editor). The hosts differ in four ways, so those are constructor inputs rather than
 * branches in here:
 *
 * - **Which side to open on** ([sideProvider]) — the notebook's toolbar is user-placeable, so it
 *   reports a side derived from its current placement; the fixed-toolbar hosts return a constant.
 * - **What to clamp against** ([boundsProvider]) — full-screen hosts clamp to the root, but the
 *   scratch pad and sticky editor are 75%×75% windows, and a panel clamped to the screen there would
 *   float outside the window's border.
 * - **The top guard** ([topGuardProvider]) — a popover is tappable chrome, so it must never enter the
 *   guard band (see `core/TopGuard`).
 * - **Reacting to visibility** ([onVisibilityChanged]) — every host maintains its own pen-exclusion
 *   rect and must recompute it whenever the panel appears or disappears.
 *
 * The panel is parented to the host's **root**, not to the window/toolbar, because those clip their
 * children (`clipToOutline`) and a popover has to overhang.
 */
class PenColorPanelController(
    private val root: View,
    private val panel: View,
    private val anchor: View,
    private val paletteGreyButton: View,
    private val paletteColorButton: View,
    private val swatchRow1: LinearLayout,
    private val swatchRow2: LinearLayout,
    private val customDivider: View,
    private val customRow: LinearLayout,
    private val sideProvider: () -> Side,
    private val boundsProvider: () -> Rect,
    private val topGuardProvider: () -> Int,
    private val onColorChosen: (String) -> Unit,
    /** Open the mixer for custom slot [index], pre-loaded with its current ink (or the pen's). */
    private val onSlotEditRequested: (index: Int, initial: String) -> Unit,
    private val onPaletteChanged: (PenPalette.Kind) -> Unit,
    /**
     * Invoked on **every** visibility change, so the host can re-push its BOOX pen-exclusion rect.
     *
     * Required rather than optional because forgetting it leaves a **dead zone the user cannot write
     * in**: the exclusion keeps covering ground the panel no longer occupies. The controller hides
     * itself from several places (a swatch tap, a slot tap, [toggle]), and any of them that did not
     * tell the host would strand the rect.
     */
    private val onVisibilityChanged: () -> Unit,
) {

    enum class Side { BELOW, ABOVE, RIGHT_OF, LEFT_OF }

    private val density = root.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun dpi(v: Float): Int = (v * density).toInt()

    /** Ink currently armed — drives which swatch is drawn as selected. */
    private var selected: String = InkColor.DEFAULT

    /** Which palette is on screen. */
    private var kind: PenPalette.Kind = PenPalette.Kind.GREYSCALE

    /** The user's custom slots, `null` where empty. */
    private var slots: List<String?> = List(PenPalette.CUSTOM_SLOTS) { null }

    /**
     * "Open" from the caller's point of view — deliberately `!= GONE`, so the one INVISIBLE frame
     * [show] uses to measure still counts as open. Otherwise a fast second tap on the pen button
     * during that frame would open a second time instead of closing.
     */
    val isVisible: Boolean get() = panel.visibility != View.GONE

    init {
        paletteGreyButton.setOnClickListener { switchPalette(PenPalette.Kind.GREYSCALE) }
        paletteColorButton.setOnClickListener { switchPalette(PenPalette.Kind.COLOR) }
    }

    private fun switchPalette(next: PenPalette.Kind) {
        if (kind == next) return
        kind = next
        onPaletteChanged(next)
        bind(selected, kind, slots)
        // The palettes are different heights (the colour one carries a third row), so the panel has
        // to be re-placed and its exclusion re-pushed after it re-measures.
        panel.post {
            position()
            onVisibilityChanged()
        }
    }

    // ── Content ──────────────────────────────────────────────────────────────

    /**
     * Rebuild every row for [current] ink, [palette] and [customSlots]. Cheap enough to run on every
     * open and every palette switch, which keeps the panel correct with no invalidation bookkeeping.
     */
    fun bind(current: String, palette: PenPalette.Kind, customSlots: List<String?>) {
        selected = current
        kind = palette
        slots = customSlots

        paletteGreyButton.isSelected = palette == PenPalette.Kind.GREYSCALE
        paletteColorButton.isSelected = palette == PenPalette.Kind.COLOR

        val entries = PenPalette.swatches(palette)
        fillRow(swatchRow1, entries.take(PenPalette.COLUMNS))
        fillRow(swatchRow2, entries.drop(PenPalette.COLUMNS))

        // Custom slots belong to the colour palette only — mixing a custom grey is what the
        // sixteen-step ladder is already for.
        val showCustom = palette == PenPalette.Kind.COLOR
        customDivider.visibility = if (showCustom) View.VISIBLE else View.GONE
        customRow.visibility = if (showCustom) View.VISIBLE else View.GONE
        if (showCustom) fillCustomRow()
    }

    private fun fillRow(row: LinearLayout, entries: List<PenPalette.Swatch>) {
        row.removeAllViews()
        for (entry in entries) row.addView(swatchCell(entry.name, entry.hex))
    }

    private fun fillCustomRow() {
        customRow.removeAllViews()
        for (i in 0 until PenPalette.CUSTOM_SLOTS) {
            customRow.addView(slots[i]?.let { hex -> customCell(i, hex) } ?: emptySlotCell(i))
        }
    }

    // ── Cells ────────────────────────────────────────────────────────────────

    /**
     * A swatch and its name, stacked.
     *
     * The name sits **below** the colour rather than on top of it. BOOX prints letters inside the
     * ambiguous swatches; putting the text underneath instead names every colour without ever
     * obscuring the thing being chosen — which matters most on the greyscale panels, where several
     * colours render alike and the label is the only thing telling them apart.
     */
    private fun swatchCell(name: String, hex: String): View =
        cell(name) { swatchTile(hex, isSelected = hex.equals(selected, ignoreCase = true)) }
            .apply {
                contentDescription = name
                ViewCompat.setTooltipText(this, name)
                setOnClickListener {
                    selected = hex
                    hide()          // hide first — the host recomputes its exclusion rect below
                    onColorChosen(hex)
                }
            }

    /** A filled custom slot: tap to use it, long-press to remix it in place. */
    private fun customCell(index: Int, hex: String): View =
        cell(PenPalette.labelFor(hex)) { swatchTile(hex, isSelected = hex.equals(selected, ignoreCase = true)) }
            .apply {
                val label = "Custom ${index + 1}, $hex"
                contentDescription = label
                ViewCompat.setTooltipText(this, "$label — long-press to change")
                setOnClickListener {
                    selected = hex
                    hide()
                    onColorChosen(hex)
                }
                setOnLongClickListener {
                    hide()
                    onSlotEditRequested(index, hex)
                    true
                }
            }

    /**
     * An empty slot: a `+` on an outlined tile.
     *
     * Not a blank or a greyed-out cell — a disabled-looking control is visually silent on e-ink and
     * reads as broken rather than available. The `+` says "free slot, tap to fill" without any state
     * the panel has to explain.
     */
    private fun emptySlotCell(index: Int): View =
        cell(label = "Add") { plusTile() }
            .apply {
                contentDescription = "Empty custom slot ${index + 1}"
                ViewCompat.setTooltipText(this, "Add a custom colour")
                setOnClickListener {
                    hide()
                    onSlotEditRequested(index, selected)
                }
            }

    /**
     * Cell width, derived from the space actually available rather than a fixed dp.
     *
     * Eight columns is a lot for the narrow end of the fleet: on a Palma2 Pro the fixed 46dp cell
     * overflowed the screen and clipped the eighth column clean off. Sizing from
     * [boundsProvider] instead means the panel fits **whatever** it is given — no per-device
     * qualifier to maintain, and no assumption about density that a display-scale setting can
     * quietly invalidate. Wide screens still cap at [maxCellPx] — the shared toolbar-button
     * target size, so a swatch is as tappable as any other button — and the panel never sprawls.
     */
    private fun cellWidthPx(): Int {
        val available = boundsProvider().width()
        if (available <= 0) return maxCellPx()
        // The panel's own padding + border, then the per-cell horizontal margins.
        val chrome = dpi(10f)
        val perColumn = (available - chrome) / PenPalette.COLUMNS
        return (perColumn - dpi(2f)).coerceIn(dpi(MIN_CELL_DP), maxCellPx())
    }

    /** Cell-width cap: the app-wide icon-button tap target (44dp under sw720dp, 62dp on tablets). */
    private fun maxCellPx(): Int = root.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)

    /** Tile + caption in a fixed-width column, so all rows align regardless of label length. */
    private inline fun cell(label: String, tile: () -> View): LinearLayout =
        LinearLayout(root.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(cellWidthPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dpi(1f); marginEnd = dpi(1f); bottomMargin = dpi(2f) }
            addView(tile())
            addView(TextView(root.context).apply {
                text = label
                textSize = 9f
                // inkBlack, never grey: the caption carries information, so the design system says it
                // gets full contrast and is made *smaller* to read as secondary.
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                maxLines = 2
                typeface = Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dpi(1f) }
            })
        }

    /** Square, inset inside the cell so the caption underneath has breathing room. */
    private fun tileSizePx(): Int = (cellWidthPx() - dpi(6f)).coerceAtLeast(dpi(20f))

    private fun swatchTile(hex: String, isSelected: Boolean): View = View(root.context).apply {
        layoutParams = LinearLayout.LayoutParams(tileSizePx(), tileSizePx())
        background = swatchDrawable(InkColor.toInt(hex), isSelected)
    }

    private fun plusTile(): View = TextView(root.context).apply {
        layoutParams = LinearLayout.LayoutParams(tileSizePx(), tileSizePx())
        text = "+"
        textSize = 20f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(4f)
            setStroke(dpi(1f).coerceAtLeast(1), Color.BLACK)
        }
    }

    /**
     * A swatch tile: black outer ring, the ink as fill, and — when selected — a white gap ring
     * between them plus a thicker outer ring.
     *
     * The white gap is what makes selection legible on **black**, where a heavier black border would
     * be invisible. Selection can't be signalled with colour here: colour is already the content.
     */
    private fun swatchDrawable(argb: Int, isSelected: Boolean): LayerDrawable {
        val ring = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(4f)
        }
        val fill = GradientDrawable().apply {
            setColor(argb)
            cornerRadius = dp(3f)
            if (isSelected) setStroke(dpi(2f).coerceAtLeast(2), Color.WHITE)
        }
        val inset = if (isSelected) dpi(4f) else dpi(1f).coerceAtLeast(1)
        return LayerDrawable(arrayOf(ring, fill)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    // ── Visibility + placement ───────────────────────────────────────────────

    fun toggle(current: String, palette: PenPalette.Kind, customSlots: List<String?>) {
        if (isVisible) hide() else show(current, palette, customSlots)
    }

    fun show(current: String, palette: PenPalette.Kind, customSlots: List<String?>) {
        bind(current, palette, customSlots)
        // Hosts that add their canvas to the panel's parent at runtime (calendar, day detail) end up
        // with the canvas stacked ON TOP of this XML-declared panel — it then renders behind the page
        // and every tap lands on the canvas, so the panel looks completely inert. The other popovers
        // in those screens each call bringToFront() for the same reason.
        panel.bringToFront()
        // INVISIBLE, not VISIBLE: the panel still lays out (so [position] has real measurements) but
        // never paints at the default 0,0. Going straight to VISIBLE would flash it in the top-left
        // corner for a frame and then jump — two EPD refreshes and a visible stutter.
        panel.visibility = View.INVISIBLE
        panel.post {
            position()
            panel.visibility = View.VISIBLE
            // Only now does the panel have a real rect to exclude — panelRect() withholds one until
            // it is VISIBLE and positioned, precisely so an unplaced 0,0 rect never reaches the pen.
            onVisibilityChanged()
        }
    }

    fun hide() {
        if (panel.visibility == View.GONE) return
        panel.visibility = View.GONE
        onVisibilityChanged()
    }

    /**
     * Centre the panel on the anchor and push it to the requested side, then clamp it inside the
     * host's bounds. **The clamp is the "offset when the button is near an edge" behaviour** — a pen
     * button parked at the far right of the bar still gets a fully on-screen panel.
     */
    private fun position() {
        val w = panel.measuredWidth.toFloat()
        val h = panel.measuredHeight.toFloat()
        if (w <= 0f || h <= 0f) return

        val anchorRect = anchorRectInRoot()
        val gap = dp(8f)
        val (rawX, rawY) = when (sideProvider()) {
            Side.RIGHT_OF -> (anchorRect.right + gap) to (anchorRect.centerY() - h / 2f)
            Side.LEFT_OF  -> (anchorRect.left - gap - w) to (anchorRect.centerY() - h / 2f)
            Side.ABOVE    -> (anchorRect.centerX() - w / 2f) to (anchorRect.top - gap - h)
            Side.BELOW    -> (anchorRect.centerX() - w / 2f) to (anchorRect.bottom + gap)
        }

        val bounds = boundsProvider()
        val minY = maxOf(bounds.top, topGuardProvider()).toFloat()
        panel.x = rawX.coerceIn(bounds.left.toFloat(), (bounds.right - w).coerceAtLeast(bounds.left.toFloat()))
        panel.y = rawY.coerceIn(minY, (bounds.bottom - h).coerceAtLeast(minY))
    }

    /** The anchor's bounds expressed in the root's coordinate space (both may be nested). */
    private fun anchorRectInRoot(): android.graphics.RectF {
        val a = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val r = IntArray(2).also { root.getLocationOnScreen(it) }
        val left = (a[0] - r[0]).toFloat()
        val top = (a[1] - r[1]).toFloat()
        return android.graphics.RectF(left, top, left + anchor.width, top + anchor.height)
    }

    /**
     * The panel's rect translated into [target]'s coordinate space, or null when hidden.
     *
     * Hosts need this because the panel and the drawing view rarely share a parent — the scratch pad
     * and sticky editor park the panel on the root while the canvas lives inside a window — and the
     * BOOX exclusion rect must be in the *drawing view's* coordinates to keep the pen out from under
     * the panel. Going via screen coordinates makes the nesting irrelevant.
     */
    fun panelRectIn(target: View): Rect? {
        // Stricter than [isVisible] on purpose: during the measure frame the panel is still parked at
        // 0,0, and publishing that as a pen-exclusion zone would block the top-left of the canvas.
        if (panel.visibility != View.VISIBLE || panel.width <= 0 || panel.height <= 0) return null
        val p = IntArray(2).also { panel.getLocationOnScreen(it) }
        val t = IntArray(2).also { target.getLocationOnScreen(it) }
        val left = p[0] - t[0]
        val top = p[1] - t[1]
        return Rect(left, top, left + panel.width, top + panel.height)
    }

    /** True when [rawX]/[rawY] (screen coords, e.g. `MotionEvent.rawX`) land on the panel. */
    fun containsScreenPoint(rawX: Int, rawY: Int): Boolean {
        if (!isVisible) return false
        val loc = IntArray(2).also { panel.getLocationOnScreen(it) }
        return rawX >= loc[0] && rawX < loc[0] + panel.width &&
            rawY >= loc[1] && rawY < loc[1] + panel.height
    }

    companion object {
        /** Floor for the narrow end of the fleet; below this a swatch stops being a stylus target. */
        private const val MIN_CELL_DP = 30f

        /**
         * Tint a pen button's icon with the ink it will write in.
         *
         * A narrow, deliberate exception to "no colour in UI chrome": without it, the only way to
         * learn what colour is armed is to open the panel.
         */
        fun applyPenTint(button: ImageView, hex: String) {
            ImageViewCompat.setImageTintList(
                button,
                ColorStateList.valueOf(InkColor.paintColor(hex)),
            )
        }
    }
}
