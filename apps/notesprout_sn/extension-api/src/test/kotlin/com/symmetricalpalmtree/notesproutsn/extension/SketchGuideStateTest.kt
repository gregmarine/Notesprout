package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The guide-state parcelable's constructor `require`s (arc 51 / J1) — [SketchPageStateTest]'s
 *  shape for the third read window: the image stream obeys the chunker's arithmetic. */
class SketchGuideStateTest {

    private val some = SketchGuideSettings(SketchContract.GRID_LINES, 4, true, 25, true)

    private fun assertRefused(build: () -> SketchGuideState) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun anAbsentImageIsOneEmptyChunk() {
        val s = SketchGuideState("p1", SketchGuideSettings.NONE, 0, 1)
        assertFalse(s.hasImage)
        assertEquals(1, s.imageChunks)
    }

    @Test
    fun aLiveImageCountsItsChunks() {
        val bytes = SketchContract.SKETCH_CHUNK_BYTES * 2 + 1
        val s = SketchGuideState("p1", some, bytes, 3)
        assertTrue(s.hasImage)
        assertEquals(ByteChunks.countFor(bytes), s.imageChunks)
    }

    @Test
    fun theStreamIsPinnedToTheChunker() {
        assertRefused { SketchGuideState("p1", some, 0, 2) }
        assertRefused { SketchGuideState("p1", some, 10, 2) }
        assertRefused { SketchGuideState("p1", some, SketchContract.SKETCH_CHUNK_BYTES + 1, 1) }
        assertRefused { SketchGuideState("p1", some, -1, 1) }
        assertRefused { SketchGuideState("p1", some, SketchContract.MAX_BYTES + 1, SketchContract.MAX_CHUNKS) }
    }

    @Test
    fun theKeyIsChecked() {
        assertRefused { SketchGuideState("", some, 0, 1) }
        assertRefused { SketchGuideState("k".repeat(SketchContract.MAX_PAGE_KEY_CHARS + 1), some, 0, 1) }
    }
}
