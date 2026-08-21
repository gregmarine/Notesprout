package com.symmetricalpalmtree.notesprout.notebook

/**
 * Where the floating selection toolbar and its sub-toolbar go (arc 4 / H2 — pure, JVM-tested). All
 * values are px in the notebook root's coordinate space. The toolbar sits [gap] below the selection,
 * horizontally centred on it; when that would cross [bandBottom] (the bottom strip's top) it flips
 * to [gap] above the selection; either way it is clamped inside `[0, rootWidth]` × `[bandTop,
 * bandBottom]` (the top bar's bottom edge to the bottom strip's top). The sub-toolbar hangs off the
 * **toolbar** (below it, or above it when the toolbar flipped — and the other way round when that
 * would leave the band), centred on it, clamped the same way — so the two rows never overlap the
 * selection's chrome or each other.
 */
object ToolbarAnchor {

    data class Placement(val x: Int, val y: Int, val flipped: Boolean)

    fun place(
        selLeft: Int, selTop: Int, selRight: Int, selBottom: Int,
        w: Int, h: Int, gap: Int, rootWidth: Int, bandTop: Int, bandBottom: Int,
    ): Placement {
        val x = clamp((selLeft + selRight) / 2 - w / 2, 0, maxOf(0, rootWidth - w))
        var y = selBottom + gap
        var flipped = false
        if (y + h > bandBottom) { y = selTop - gap - h; flipped = true }
        y = clamp(y, bandTop, maxOf(bandTop, bandBottom - h))
        return Placement(x, y, flipped)
    }

    fun placeSub(
        bar: Placement, barW: Int, barH: Int,
        w: Int, h: Int, gap: Int, rootWidth: Int, bandTop: Int, bandBottom: Int,
    ): Placement {
        val x = clamp(bar.x + barW / 2 - w / 2, 0, maxOf(0, rootWidth - w))
        val below = bar.y + barH + gap
        val above = bar.y - gap - h
        var y: Int
        var flipped: Boolean
        if (!bar.flipped) {
            y = below; flipped = false
            if (y + h > bandBottom) { y = above; flipped = true }
        } else {
            y = above; flipped = true
            if (y < bandTop) { y = below; flipped = false }
        }
        y = clamp(y, bandTop, maxOf(bandTop, bandBottom - h))
        return Placement(x, y, flipped)
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
}
