package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Search's pure half (arc 37 / B8): the FTS4 query grammar, the BM25 over a `matchinfo` blob,
 * the reference-or-words routing, and the snippet's window and bold ranges.
 */
class SearchTest {

    // --- the query ----------------------------------------------------------

    @Test
    fun `builds a lowercase prefix-AND expression`() {
        assertEquals("faith*", SearchQuery.matchExpression("faith"))
        assertEquals("the* lord*", SearchQuery.matchExpression("The Lord!"))
        assertEquals("love* your* enemies*", SearchQuery.matchExpression("  love, your… enemies "))
    }

    @Test
    fun `operators are words once lowercased`() {
        // FTS4's OR / NOT / NEAR are upper-case only; typed words are never operators.
        assertEquals("or* not* near*", SearchQuery.matchExpression("OR NOT NEAR"))
    }

    @Test
    fun `punctuation alone has no tokens`() {
        assertNull(SearchQuery.matchExpression("  ?? -- "))
        assertTrue(SearchQuery.tokens("…").isEmpty())
    }

    // --- the rank -----------------------------------------------------------

    /** A 'pcnalx' blob for one column: p phrases, n rows, avg a, len l, then (hits, all, docs). */
    private fun blob(phrases: Int, n: Int, a: Int, l: Int, vararg x: Int): ByteArray {
        val ints = intArrayOf(phrases, 1, n, a, l) + x
        val buf = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        ints.forEach { buf.putInt(it) }
        return buf.array()
    }

    @Test
    fun `a rarer word scores higher than a common one`() {
        val rare = SearchRank.bm25(blob(1, 31086, 23, 23, 1, 120, 114))
        val common = SearchRank.bm25(blob(1, 31086, 23, 23, 1, 20000, 15000))
        assertTrue(rare > common)
        assertTrue(common >= 0.0)
    }

    @Test
    fun `more hits in a shorter row score higher`() {
        val twiceShort = SearchRank.bm25(blob(1, 31086, 23, 12, 2, 120, 114))
        val onceLong = SearchRank.bm25(blob(1, 31086, 23, 60, 1, 120, 114))
        assertTrue(twiceShort > onceLong)
    }

    @Test
    fun `two phrases add up`() {
        val one = SearchRank.bm25(blob(1, 31086, 23, 23, 1, 120, 114))
        val two = SearchRank.bm25(blob(2, 31086, 23, 23, 1, 120, 114, 1, 300, 250))
        assertTrue(two > one)
    }

    @Test
    fun `a ubiquitous prefix scores every row alike`() {
        // idf clamps at zero, so "the*" leaves the canonical tie-break to decide.
        assertEquals(0.0, SearchRank.bm25(blob(1, 31086, 23, 23, 3, 60000, 28000)), 0.0)
    }

    @Test
    fun `a short or torn blob scores zero`() {
        assertEquals(0.0, SearchRank.bm25(ByteArray(0)), 0.0)
        assertEquals(0.0, SearchRank.bm25(blob(2, 31086, 23, 23, 1, 120, 114)), 0.0)
    }

    @Test
    fun `top keeps the best first and ties in canonical order`() {
        val scored = listOf(43003016 to 2.0, 1001001 to 2.0, 19023001 to 5.0, 66022021 to 0.5)
        assertEquals(listOf(19023001, 1001001, 43003016), SearchRank.top(scored, 3))
        assertEquals(listOf(19023001, 1001001), SearchRank.top(scored, 2))
        assertEquals(emptyList<Int>(), SearchRank.top(scored, 0))
        assertEquals(SearchRank.top(scored, scored.size), SearchRank.top(scored, 99))
    }

    // --- the route ----------------------------------------------------------

    @Test
    fun `a whole chapter alone is a chapter`() {
        assertEquals(SearchRoute.Chapter(ChapterRef("PSA", 23)), SearchRoute.classify(" Psalm 23 "))
        assertEquals(SearchRoute.Chapter(ChapterRef("1CO", 13)), SearchRoute.classify("1 Cor 13"))
    }

    @Test
    fun `verses and spans are passages`() {
        val one = SearchRoute.classify("John 3:16") as SearchRoute.Passage
        assertEquals("JHN:3:16-3:16", one.wire)
        val span = SearchRoute.classify("Gen 1-2") as SearchRoute.Passage
        assertEquals("GEN:1:0-2:999", span.wire)
        val two = SearchRoute.classify("John 3:14-17, Acts 1:3") as SearchRoute.Passage
        assertEquals("JHN:3:14-3:17,ACT:1:3-1:3", two.wire)
    }

    @Test
    fun `anything else is words`() {
        assertEquals(SearchRoute.Words("love your enemies"), SearchRoute.classify("love your enemies"))
        assertEquals(SearchRoute.Words("shepherd"), SearchRoute.classify("shepherd"))
        assertEquals(SearchRoute.Words("Jhon 3:16"), SearchRoute.classify("Jhon 3:16"))
    }

    // --- the snippet --------------------------------------------------------

    @Test
    fun `matches are word starts, case-insensitive, as long as the token`() {
        val m = SearchSnippet.matches("Beloved, let us love one another, for love is from God.", listOf("love"))
        assertEquals(listOf(16..19, 38..41), m)   // "love" and "love", not "Beloved"
    }

    @Test
    fun `an early match keeps the whole text`() {
        val r = SearchSnippet.render("The LORD is my shepherd; I shall not want.", listOf("shepherd"))
        assertEquals("The LORD is my shepherd; I shall not want.", r.text)
        assertEquals(listOf(15..22), r.bold)
    }

    @Test
    fun `a deep match is windowed to a word boundary and the ranges shift`() {
        val text = "For God so loved the world that He gave His one and only Son, that everyone who believes in Him shall not perish but have eternal life."
        val r = SearchSnippet.render(text, listOf("perish"))
        assertTrue(r.text.startsWith("…"))
        val at = r.text.indexOf("perish")
        assertEquals(listOf(at until at + 6), r.bold)
        assertTrue(text.indexOf("perish") - (r.text.length - 1 - (r.text.length - 1 - at)) >= 0)
        // The window keeps at most LEAD_CHARS of run-up and cuts on a space.
        assertTrue(at - 1 <= SearchSnippet.LEAD_CHARS)
        assertTrue(r.text[1] != ' ')
    }

    @Test
    fun `no match means no window and no bold`() {
        val r = SearchSnippet.render("In the beginning God created the heavens and the earth.", listOf("zzz"))
        assertEquals("In the beginning God created the heavens and the earth.", r.text)
        assertTrue(r.bold.isEmpty())
    }

    @Test
    fun `a hit labels itself in the running head's form`() {
        assertEquals("Psalm 23:1", SearchHit(19023001, "PSA", 23, 1, "").label)
        assertEquals("John 3:16", SearchHit(43003016, "JHN", 3, 16, "").label)
    }

    @Test
    fun `results know when the list is capped`() {
        val hit = SearchHit(1001001, "GEN", 1, 1, "")
        assertTrue(SearchResults("x", listOf("x"), listOf(hit), 2).capped)
        assertTrue(!SearchResults("x", listOf("x"), listOf(hit), 1).capped)
    }

    @Test
    fun `a one-chapter book's verse routes as a passage against the source's counts`() {
        val counts = { usfm: String -> if (usfm == "JUD") 1 else 21 }
        val route = SearchRoute.classify("Jude 5", counts)
        assertTrue(route is SearchRoute.Passage)
        assertEquals("JUD:1:5-1:5", (route as SearchRoute.Passage).wire)
        assertTrue(SearchRoute.classify("Jude 5") is SearchRoute.Chapter)
        assertTrue(SearchRoute.classify("Jude 1", counts) is SearchRoute.Chapter)
    }
}
