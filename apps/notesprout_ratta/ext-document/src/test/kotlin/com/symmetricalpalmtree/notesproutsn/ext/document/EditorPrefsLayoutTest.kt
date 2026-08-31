package com.symmetricalpalmtree.notesproutsn.ext.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's key layout, pinned.
 *
 * These are not behaviour tests — they are a lock on a **persistence format**. The two keys name
 * values already written to a real user's extension store, and a silent rename would not fail
 * anywhere: it would quietly orphan what is there and start again from the defaults, which reads as
 * "the app forgot" rather than as a bug.
 *
 * The size ladder is pinned for the same reason one step down: a stored size outside it is clamped
 * into it, so changing the ends changes what an existing value means.
 */
class EditorPrefsLayoutTest {

    @Test
    fun `the store keys are what is already on disk`() {
        assertEquals("size", EditorPrefs.KEY_TEXT_SIZE)
        assertEquals("carets", EditorPrefs.KEY_CARETS)
    }

    @Test
    fun `the offered sizes are og's ladder, smallest first`() {
        assertEquals(listOf(14f, 16f, 18f, 21f, 25f), EditorPrefs.SIZES.map { it.second })
    }

    @Test
    fun `every size has a label of its own`() {
        val labels = EditorPrefs.SIZES.map { it.first }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.none { it == 0 })
    }

    @Test
    fun `the default is one of the offered sizes and the preview reads larger`() {
        assertEquals(16f, EditorPrefs.DEFAULT_TEXT_SIZE, 0f)
        assertTrue(EditorPrefs.SIZES.any { it.second == EditorPrefs.DEFAULT_TEXT_SIZE })
        assertEquals(2f, EditorPrefs.PREVIEW_BUMP, 0f)
    }
}
