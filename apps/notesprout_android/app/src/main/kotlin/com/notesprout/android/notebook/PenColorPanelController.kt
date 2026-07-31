package com.notesprout.android.notebook

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList
import com.notesprout.android.core.InkColor

/**
 * The pen-colour panel: a swatch popover docked to whichever pen button opens it.
 *
 * Written once and shared by all five drawing hosts (notebook, scratch pad, calendar, day detail,
 * sticky-note editor). The hosts differ in three ways, so those are constructor inputs rather than
 * branches in here:
 *
 * - **Which side to open on** ([sideProvider]) — the notebook's toolbar is user-placeable, so it
 *   reports a side derived from its current placement; the fixed-toolbar hosts return a constant.
 * - **What to clamp against** ([boundsProvider]) — full-screen hosts clamp to the root, but the
 *   scratch pad and sticky editor are 75%×75% windows, and a panel clamped to the screen there would
 *   float outside the window's border.
 * - **The top guard** ([topGuardProvider]) — a popover is tappable chrome, so it must never enter the
 *   guard band (see `core/TopGuard`).
 *
 * The panel is parented to the host's **root**, not to the window/toolbar, because those clip their
 * children (`clipToOutline`) and a popover has to overhang.
 */
class PenColorPanelController(
    private val root: View,
    private val panel: View,
    private val anchor: View,
    private val swatchRow1: LinearLayout,
    private val swatchRow2: LinearLayout,
    private val recentDivider: View,
    private val recentRow: LinearLayout,
    customButton: View,
    private val sideProvider: () -> Side,
    private val boundsProvider: () -> Rect,
    private val topGuardProvider: () -> Int,
    private val onColorChosen: (String) -> Unit,
    onCustomRequested: () -> Unit,
) {

    enum class Side { BELOW, ABOVE, RIGHT_OF, LEFT_OF }

    private val density = root.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun dpi(v: Float): Int = (v * density).toInt()

    /** Ink currently armed — drives which swatch is drawn as selected. */
    private var selected: String = InkColor.DEFAULT

    /**
     * "Open" from the caller's point of view — deliberately `!= GONE`, so the one INVISIBLE frame
     * [show] uses to measure still counts as open. Otherwise a fast second tap on the pen button
     * during that frame would open a second time instead of closing.
     */
    val isVisible: Boolean get() = panel.visibility != View.GONE

    init {
        customButton.setOnClickListener {
            hide()
            onCustomRequested()
        }
    }

    // ── Content ──────────────────────────────────────────────────────────────

    /**
     * Rebuild the swatches for [current] ink and [recents]. Cheap enough to run on every open, which
     * keeps the panel correct without any invalidation bookkeeping.
     */
    fun bind(current: String, recents: List<String>) {
        selected = current
        val defaults = PenPalette.DEFAULTS
        fillRow(swatchRow1, defaults.take(PenPalette.COLUMNS).map { it.name to it.hex })
        fillRow(swatchRow2, defaults.drop(PenPalette.COLUMNS).map { it.name to it.hex })

        val shown = recents.take(PenColorPreferences.MAX_RECENT)
        fillRow(recentRow, shown.map { PenPalette.labelFor(it) to it })
        val hasRecents = shown.isNotEmpty()
        recentRow.visibility = if (hasRecents) View.VISIBLE else View.GONE
        recentDivider.visibility = if (hasRecents) View.VISIBLE else View.GONE
    }

    private fun fillRow(row: LinearLayout, entries: List<Pair<String, String>>) {
        row.removeAllViews()
        for ((name, hex) in entries) row.addView(swatchView(name, hex))
    }

    private fun swatchView(name: String, hex: String): View = View(root.context).apply {
        layoutParams = LinearLayout.LayoutParams(dpi(44f), dpi(44f)).apply {
            marginStart = dpi(2f); marginEnd = dpi(2f)
            topMargin = dpi(2f); bottomMargin = dpi(2f)
        }
        background = swatchDrawable(InkColor.toInt(hex), hex.equals(selected, ignoreCase = true))
        // A wordless swatch has to be learnable: the same string serves the long-press hint and the
        // content description, per the icon rule in the design system. Uses the platform tooltip
        // rather than a Toast — it self-dismisses and costs one less EPD refresh.
        contentDescription = name
        ViewCompat.setTooltipText(this, name)
        setOnClickListener {
            selected = hex
            onColorChosen(hex)
            hide()
        }
    }

    /**
     * A swatch cell: black outer ring, the ink as fill, and — when selected — a white gap ring
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

    fun toggle(current: String, recents: List<String>) {
        if (isVisible) hide() else show(current, recents)
    }

    fun show(current: String, recents: List<String>) {
        bind(current, recents)
        // INVISIBLE, not VISIBLE: the panel still lays out (so [position] has real measurements) but
        // never paints at the default 0,0. Going straight to VISIBLE would flash it in the top-left
        // corner for a frame and then jump — two EPD refreshes and a visible stutter.
        panel.visibility = View.INVISIBLE
        panel.post {
            position()
            panel.visibility = View.VISIBLE
        }
    }

    fun hide() {
        panel.visibility = View.GONE
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
     * The panel's laid-out rect in root coordinates, or null when hidden — used by hosts to union it
     * into the BOOX pen-exclusion zone and to detect outside taps. Returns null until it has a size,
     * so a rect is never published before [position] has run.
     */
    fun panelRect(): Rect? {
        // Stricter than [isVisible] on purpose: during the measure frame the panel is still parked at
        // 0,0, and publishing that as a pen-exclusion zone would block the top-left of the canvas.
        if (panel.visibility != View.VISIBLE || panel.width <= 0 || panel.height <= 0) return null
        return Rect(
            panel.x.toInt(),
            panel.y.toInt(),
            (panel.x + panel.width).toInt(),
            (panel.y + panel.height).toInt(),
        )
    }

    /** True when [rawX]/[rawY] (screen coords, e.g. `MotionEvent.rawX`) land on the panel. */
    fun containsScreenPoint(rawX: Int, rawY: Int): Boolean {
        if (!isVisible) return false
        val loc = IntArray(2).also { panel.getLocationOnScreen(it) }
        return rawX >= loc[0] && rawX < loc[0] + panel.width &&
            rawY >= loc[1] && rawY < loc[1] + panel.height
    }

    companion object {
        /**
         * Tint a pen button's icon with the ink it will write in.
         *
         * A narrow, deliberate exception to "no colour in UI chrome": without it, the only way to
         * learn what colour is armed is to open the panel. Uses [InkColor.paintColor], so on a
         * greyscale device the icon is simply black — identical to how it has always looked.
         */
        fun applyPenTint(button: ImageView, hex: String) {
            ImageViewCompat.setImageTintList(
                button,
                ColorStateList.valueOf(InkColor.paintColor(hex)),
            )
        }
    }
}
