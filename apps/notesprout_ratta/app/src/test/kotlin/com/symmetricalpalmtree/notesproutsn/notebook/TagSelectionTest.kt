package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lasso's Tag button (arc 21 / W3): which selections get it, which flow each takes, and what
 * text is fit to carry into either one.
 */
class TagSelectionTest {

    // ── Which flow ───────────────────────────────────────────────────────────

    @Test
    fun `a lone heading is the silent flow`() {
        assertEquals(TagFlow.SILENT, TagSelection.flowFor(SelectionMode.HEADING))
    }

    @Test
    fun `ink alone is recognized`() {
        assertEquals(TagFlow.RECOGNIZE, TagSelection.flowFor(SelectionMode.STROKES))
    }

    @Test
    fun `a mixed selection has no flow at all`() {
        // The user's W3 call: a heading carries words already and the ink beside it carries other
        // words, and there is no way to ask which was meant — so nothing is offered.
        assertEquals(TagFlow.NONE, TagSelection.flowFor(SelectionMode.MIXED))
        assertEquals(TagFlow.NONE, TagSelection.flowFor(SelectionMode.MIXED_WITH_LINK))
    }

    @Test
    fun `a lone link is not ink`() {
        assertEquals(TagFlow.NONE, TagSelection.flowFor(SelectionMode.LINK))
    }

    // ── Whether it is offered ────────────────────────────────────────────────

    @Test
    fun `no tag manager means no button, whatever is selected`() {
        for (mode in SelectionMode.entries) {
            assertFalse(mode.name, TagSelection.offered(mode, tagsAvailable = false))
        }
    }

    @Test
    fun `with a tag manager, exactly the two flows are offered`() {
        assertTrue(TagSelection.offered(SelectionMode.HEADING, tagsAvailable = true))
        assertTrue(TagSelection.offered(SelectionMode.STROKES, tagsAvailable = true))
        assertFalse(TagSelection.offered(SelectionMode.MIXED, tagsAvailable = true))
        assertFalse(TagSelection.offered(SelectionMode.MIXED_WITH_LINK, tagsAvailable = true))
        assertFalse(TagSelection.offered(SelectionMode.LINK, tagsAvailable = true))
    }

    // ── Is it a tag as it stands (the silent gate) ───────────────────────────

    @Test
    fun `an ordinary title is a tag`() {
        assertTrue(TagSelection.isTag("Reading list"))
    }

    @Test
    fun `a blank title is not a tag`() {
        assertFalse(TagSelection.isTag("   "))
        assertFalse(TagSelection.isTag(""))
    }

    @Test
    fun `a title past the cap is not a tag`() {
        assertFalse(TagSelection.isTag("x".repeat(ExtensionContract.MAX_TAG_CHARS + 1)))
        assertTrue(TagSelection.isTag("x".repeat(ExtensionContract.MAX_TAG_CHARS)))
    }

    // ── The prefill ──────────────────────────────────────────────────────────

    @Test
    fun `a prefill is normalized the way a tag is`() {
        assertEquals("reading list", TagSelection.prefill("  reading   list \n"))
    }

    @Test
    fun `recognized line breaks collapse rather than survive`() {
        assertEquals("two lines", TagSelection.prefill("two\nlines"))
    }

    @Test
    fun `nothing left is a null prefill, never an empty one`() {
        assertNull(TagSelection.prefill("   \n\t "))
        assertNull(TagSelection.prefill(""))
    }

    @Test
    fun `an over-long prefill is cut to the cap rather than refused`() {
        // TagShowing's constructor refuses a prefill over the cap, so the alternative to cutting is
        // a crash on the way to the screen.
        val cut = TagSelection.prefill("x".repeat(ExtensionContract.MAX_TAG_CHARS + 40))
        assertEquals(ExtensionContract.MAX_TAG_CHARS, cut!!.length)
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        // A pair straddling the cap: the cut lands one char short rather than leaving a lone high
        // surrogate behind.
        val head = "a".repeat(ExtensionContract.MAX_TAG_CHARS - 1)
        val cut = TagSelection.prefill(head + "🌱" + "tail")!!
        assertEquals(ExtensionContract.MAX_TAG_CHARS - 1, cut.length)
        assertEquals(head, cut)
        assertFalse(cut.any { it.isSurrogate() })
    }

    @Test
    fun `a pair that fits whole is kept whole`() {
        val head = "a".repeat(ExtensionContract.MAX_TAG_CHARS - 2)
        val cut = TagSelection.prefill(head + "🌱" + "tail")!!
        assertEquals(ExtensionContract.MAX_TAG_CHARS, cut.length)
        assertTrue(cut.endsWith("🌱"))
    }
}
