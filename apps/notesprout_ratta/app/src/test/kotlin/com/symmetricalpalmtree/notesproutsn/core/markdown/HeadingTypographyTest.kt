package com.symmetricalpalmtree.notesproutsn.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The heading size ladder. Pinned exactly because a change here silently resizes every heading
 * already stored in every notebook — the text is saved, the pixels are not.
 */
class HeadingTypographyTest {

    private val eps = 0.0001f

    @Test
    fun scaleTable_isExact() {
        assertEquals(2.0f, HeadingTypography.scaleFor(1), eps)
        assertEquals(1.75f, HeadingTypography.scaleFor(2), eps)
        assertEquals(1.5f, HeadingTypography.scaleFor(3), eps)
        assertEquals(1.25f, HeadingTypography.scaleFor(4), eps)
        assertEquals(1.1f, HeadingTypography.scaleFor(5), eps)
        assertEquals(1.0f, HeadingTypography.scaleFor(6), eps)
    }

    @Test
    fun outOfRangeLevels_clampIntoTheTable() {
        // A level comes out of a stored flags column; a bad one must still render as something.
        assertEquals(HeadingTypography.scaleFor(1), HeadingTypography.scaleFor(0), eps)
        assertEquals(HeadingTypography.scaleFor(1), HeadingTypography.scaleFor(-4), eps)
        assertEquals(HeadingTypography.scaleFor(6), HeadingTypography.scaleFor(7), eps)
        assertEquals(HeadingTypography.scaleFor(6), HeadingTypography.scaleFor(99), eps)
    }

    @Test
    fun textSizePx_isBaseTimesScaleTimesDensity() {
        assertEquals(24f * 2.0f * 2f, HeadingTypography.textSizePx(1, 2f), eps)
        assertEquals(24f * 1.1f * 1.5f, HeadingTypography.textSizePx(5, 1.5f), eps)
        // h6 is body size, distinguished by weight alone.
        assertEquals(24f * 1f, HeadingTypography.textSizePx(6, 1f), eps)
    }

    @Test
    fun paddingPx_scalesWithDensity() {
        assertEquals(8f, HeadingTypography.paddingPx(1f), eps)
        assertEquals(16f, HeadingTypography.paddingPx(2f), eps)
    }

    @Test
    fun baseConstants() {
        assertEquals(24f, HeadingTypography.BASE_SP, eps)
        assertEquals(8f, HeadingTypography.PADDING_DP, eps)
    }
}
