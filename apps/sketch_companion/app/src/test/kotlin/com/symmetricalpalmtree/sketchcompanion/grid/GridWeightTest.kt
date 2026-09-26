package com.symmetricalpalmtree.sketchcompanion.grid

import org.junit.Assert.assertEquals
import org.junit.Test

class GridWeightTest {
    @Test fun regularIsTheSketchPagesTwoPixelLineAtNomadWidth() {
        assertEquals(2.006f, GridWeight.REGULAR.lineWidth(1404), 1e-3f)
        assertEquals(4.012f, GridWeight.REGULAR.dotRadius(1404), 1e-3f)
    }

    @Test fun thinIsHalfAndBoldIsDouble() {
        val r = GridWeight.REGULAR.lineWidth(2000)
        assertEquals(r / 2f, GridWeight.THIN.lineWidth(2000), 1e-4f)
        assertEquals(r * 2f, GridWeight.BOLD.lineWidth(2000), 1e-4f)
    }

    @Test fun neverThinnerThanOnePixel() {
        assertEquals(1f, GridWeight.THIN.lineWidth(300), 0f)
    }

    @Test fun proportionalToWidth() {
        val a = GridWeight.REGULAR.lineWidth(4096)
        val b = GridWeight.REGULAR.lineWidth(1360)
        assertEquals(4096f / 1360f, a / b, 1e-4f)
    }

    @Test fun unknownOrdinalFallsBackToRegular() {
        assertEquals(GridWeight.REGULAR, GridWeight.fromOrdinal(9))
        assertEquals(GridWeight.BOLD, GridWeight.fromOrdinal(2))
    }
}
