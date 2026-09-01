package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.TagShowing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Arc 21 / W2 — what the notebook's tag doors aim at. */
class TagTargetsTest {

    @Test
    fun `page numbers are 1-based and follow the list's order`() {
        val pages = listOf("a", "b", "c")
        assertEquals(1, TagTargets.pageNumber(pages, "a"))
        assertEquals(2, TagTargets.pageNumber(pages, "b"))
        assertEquals(3, TagTargets.pageNumber(pages, "c"))
    }

    /** During a page op the displayed page can briefly be one the list no longer holds — the
     *  caller needs to be told that, not handed a "Page 0". */
    @Test
    fun `a page that is not in the list has no number`() {
        assertNull(TagTargets.pageNumber(listOf("a", "b"), "z"))
        assertNull(TagTargets.pageNumber(emptyList(), "a"))
    }

    @Test
    fun `every page is listed while the parcel can carry them`() {
        val pages = List(500) { "p$it" }
        assertEquals(pages, TagTargets.listedPages(pages))
        assertEquals(emptyList<String>(), TagTargets.listedPages(emptyList()))
    }

    /** The parcel refuses above its bound rather than allocating, so the tap must not offer more
     *  than it will accept — a crash is worse than a list missing a tail no notebook has. */
    @Test
    fun `the listing stops at the parcel's bound`() {
        val pages = List(TagShowing.MAX_PAGES + 17) { "p$it" }
        val listed = TagTargets.listedPages(pages)
        assertEquals(TagShowing.MAX_PAGES, listed.size)
        assertEquals("p0", listed.first())
        assertEquals("p${TagShowing.MAX_PAGES - 1}", listed.last())
    }

    @Test
    fun `exactly the bound is listed whole`() {
        val pages = List(TagShowing.MAX_PAGES) { "p$it" }
        assertEquals(TagShowing.MAX_PAGES, TagTargets.listedPages(pages).size)
    }
}
