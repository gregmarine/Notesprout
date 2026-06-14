package com.notesprout.android.notebook

import android.graphics.RectF
import kotlin.math.abs

object SnapEngine {

    /**
     * Computes a snapped drag offset given the selection box in its original (pre-drag) position
     * and the raw pointer delta. Returns adjusted dx/dy plus any active guide lines to draw.
     *
     * Snap regions per axis (in pixels):
     *   X: left edge, left margin, center, right margin, right edge
     *   Y: top edge, top margin, center, bottom margin, bottom edge
     *
     * The nearest (anchor → guide) pair within [thresholdPx] wins. X and Y snap independently.
     */
    fun computeSnap(
        originalBox: RectF,
        rawDx: Float,
        rawDy: Float,
        pageWidth: Float,
        pageHeight: Float,
        marginPx: Float,
        thresholdPx: Float,
    ): SnapResult {
        // Current visual positions of each selection anchor after raw drag.
        val movedLeft    = originalBox.left    + rawDx
        val movedCenterX = originalBox.centerX() + rawDx
        val movedRight   = originalBox.right   + rawDx

        val movedTop     = originalBox.top     + rawDy
        val movedCenterY = originalBox.centerY() + rawDy
        val movedBottom  = originalBox.bottom  + rawDy

        val vGuides = listOf(0f, marginPx, pageWidth / 2f, pageWidth - marginPx, pageWidth)
        val hGuides = listOf(0f, marginPx, pageHeight / 2f, pageHeight - marginPx, pageHeight)

        val xAnchors = listOf(movedLeft, movedCenterX, movedRight)
        val yAnchors = listOf(movedTop,  movedCenterY, movedBottom)

        // Best X snap: smallest abs distance within threshold across all (anchor, guide) pairs.
        var bestXAdj = Float.MAX_VALUE
        var snapXAt: Float? = null
        for (anchor in xAnchors) {
            for (guide in vGuides) {
                val dist = abs(anchor - guide)
                if (dist < thresholdPx && dist < abs(bestXAdj)) {
                    bestXAdj = guide - anchor
                    snapXAt  = guide
                }
            }
        }

        // Best Y snap.
        var bestYAdj = Float.MAX_VALUE
        var snapYAt: Float? = null
        for (anchor in yAnchors) {
            for (guide in hGuides) {
                val dist = abs(anchor - guide)
                if (dist < thresholdPx && dist < abs(bestYAdj)) {
                    bestYAdj = guide - anchor
                    snapYAt  = guide
                }
            }
        }

        val snappedDx = if (snapXAt != null) rawDx + bestXAdj else rawDx
        val snappedDy = if (snapYAt != null) rawDy + bestYAdj else rawDy

        val guides = buildList {
            snapXAt?.let { add(SnapGuide.Vertical(it)) }
            snapYAt?.let { add(SnapGuide.Horizontal(it)) }
        }

        return SnapResult(snappedDx, snappedDy, guides)
    }
}
