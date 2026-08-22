package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * Where the floating [SelectionToolbar] goes — pure arithmetic, JVM-tested. Every value is px in
 * the notebook root's coordinate space.
 *
 * The bar sits [gap] below the selection box, horizontally centred on it. When that would push it
 * past [bandBottom] (the bottom strip's top edge) it flips to [gap] *above* the box instead — the
 * user's hand is below the selection when they finish a lasso, so below-then-flip is the order that
 * keeps the bar out from under it. Either way the result is clamped inside `[0, rootWidth]` ×
 * `[bandTop, bandBottom]` (the top bar's bottom edge to the bottom strip's top): a selection drawn
 * against a screen edge still gets a fully reachable bar, and the bar never lands under chrome,
 * where its own taps would be eaten.
 *
 * There is no dependency on Android here on purpose — the geometry is the part worth testing, and
 * the view work ([SelectionToolbar]) is the part that cannot be.
 */
object SelectionAnchor {

    /** Top-left of the bar, in root coordinates. */
    data class Placement(val x: Int, val y: Int)

    fun place(
        selLeft: Int,
        selTop: Int,
        selRight: Int,
        selBottom: Int,
        toolbarW: Int,
        toolbarH: Int,
        gap: Int,
        rootWidth: Int,
        bandTop: Int,
        bandBottom: Int,
    ): Placement {
        val x = clamp((selLeft + selRight) / 2 - toolbarW / 2, 0, maxOf(0, rootWidth - toolbarW))
        var y = selBottom + gap
        if (y + toolbarH > bandBottom) y = selTop - gap - toolbarH
        // A selection taller than the band leaves neither side room; the clamp lands it at the top
        // of the band rather than off-screen, which is still a reachable bar.
        y = clamp(y, bandTop, maxOf(bandTop, bandBottom - toolbarH))
        return Placement(x, y)
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
}
