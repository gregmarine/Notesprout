package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchGuideSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc 51 / J3 — the face's model of a page's guides. */
class GuideStateTest {

    @Test fun aPageWithNeitherRowShowsNothing() {
        val s = GuideState.fromSettings(SketchGuideSettings.NONE, hasImage = false)
        assertEquals(GuideState.NONE, s)
        assertFalse(s.showsAnything)
        assertEquals(GuideSheet.DEFAULT_COUNT, s.gridCount)
        assertEquals(GuideSheet.DEFAULT_OPACITY, s.imageOpacity)
        assertEquals(GuideState.NONE, GuideState.fromSettings(null, hasImage = false))
    }

    @Test fun defaultsAreTheUsersWord() {
        assertEquals(4, GuideSheet.DEFAULT_COUNT)
        assertEquals(25, GuideSheet.DEFAULT_OPACITY)
        assertEquals(listOf(2, 3, 4, 6, 8, 12), GuideSheet.COUNTS)
        assertEquals(listOf(10, 25, 50, 75), GuideSheet.OPACITIES)
        assertEquals(SketchContract.GRID_LINES, GuideState.DEFAULT_KIND)
        assertEquals(0xFFAAAAAA.toInt(), GuideSheet.GRID_TONE)
    }

    @Test fun offLadderValuesReadAsDefaultsEachOnTheirOwn() {
        val s = GuideState.fromSettings(
            SketchGuideSettings(SketchContract.GRID_DOTS, 5, false, 33, false),
            hasImage = true,
        )
        assertEquals(SketchContract.GRID_DOTS, s.gridKind)
        assertEquals(GuideSheet.DEFAULT_COUNT, s.gridCount)
        assertFalse(s.gridVisible)
        assertEquals(GuideSheet.DEFAULT_OPACITY, s.imageOpacity)
        assertFalse(s.imageVisible)
        assertTrue(s.hasImage)
    }

    @Test fun anUnknownKindReadsAsLines() {
        val s = GuideState.fromSettings(SketchGuideSettings(7, 8, true, 50, true), hasImage = false)
        assertEquals(SketchContract.GRID_LINES, s.gridKind)
        assertEquals(8, s.gridCount)
        assertEquals(50, s.imageOpacity)
    }

    @Test fun toSettingsRoundTrips() {
        val s = GuideState(SketchContract.GRID_DOTS, 12, false, 75, true, hasImage = true)
        assertEquals(s, GuideState.fromSettings(s.toSettings(), hasImage = true))
        val settings = s.toSettings()
        assertEquals(SketchContract.GRID_DOTS, settings.gridKind)
        assertEquals(12, settings.gridCount)
        assertFalse(settings.gridVisible)
        assertEquals(75, settings.imageOpacity)
        assertTrue(settings.imageVisible)
    }

    @Test fun noneIsALegalParcel() {
        // Kind off with the default count: the host soft-deletes the grid row on kind 0.
        val settings = GuideState.NONE.toSettings()
        assertFalse(settings.hasGrid)
    }

    @Test fun pickingAKindShowsTheGridAndOffKeepsTheCount() {
        val hidden = GuideState.NONE.withGrid(SketchContract.GRID_LINES).withCount(8).toggleGridVisible()
        assertFalse(hidden.showsGrid)
        val dots = hidden.withGrid(SketchContract.GRID_DOTS)
        assertTrue(dots.showsGrid)
        val off = dots.withGrid(SketchContract.GRID_OFF)
        assertFalse(off.gridOn)
        assertEquals(8, off.gridCount)
        assertEquals(8, off.withGrid(SketchContract.GRID_LINES).gridCount)
    }

    @Test fun aCountWithTheGridOffTurnsLinesOn() {
        val s = GuideState.NONE.withCount(6)
        assertEquals(SketchContract.GRID_LINES, s.gridKind)
        assertEquals(6, s.gridCount)
        assertTrue(s.showsGrid)
        assertEquals(GuideSheet.DEFAULT_COUNT, GuideState.NONE.withCount(5).gridCount)
    }

    @Test fun theImageVerbs() {
        val picked = GuideState.NONE.withOpacity(50).withImage()
        assertTrue(picked.showsImage)
        assertEquals(50, picked.imageOpacity)
        val hidden = picked.toggleImageVisible()
        assertFalse(hidden.showsImage)
        assertTrue(hidden.hasImage)
        assertTrue(hidden.withOpacity(10).showsImage)
        val removed = hidden.withoutImage()
        assertFalse(removed.hasImage)
        assertFalse(removed.showsImage)
        assertEquals(50, removed.imageOpacity)
        assertEquals(GuideSheet.DEFAULT_OPACITY, picked.withOpacity(40).imageOpacity)
    }

    @Test fun gridOverImageBothShow() {
        val s = GuideState.NONE.withGrid(SketchContract.GRID_DOTS).withImage()
        assertTrue(s.showsGrid)
        assertTrue(s.showsImage)
        assertTrue(s.showsAnything)
    }
}
