package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The verses-as-Markdown builder and its cap (arc 40 "Verses"), over fake rows — no database. */
class PassageMarkdownTest {

    private fun verse(usfm: String, chapter: Int, verse: Int, text: String) = VerseRow(
        verseKey = VerseKey.encode(Canon.byUsfm(usfm).ordinal, chapter, verse),
        usfm = usfm, chapter = chapter, verse = verse, text = text,
    )

    private fun passages(wire: String) = ReferenceCodec.decode(wire)!!

    @Test
    fun `label line then numbered verses in one paragraph`() {
        val md = PassageMarkdown.build(
            "John 3:16–17",
            listOf(verse("JHN", 3, 16, "For God so loved"), verse("JHN", 3, 17, "For God did not send")),
        )
        assertEquals("**John 3:16–17**\n\n16 For God so loved 17 For God did not send", md)
    }

    @Test
    fun `a chapter crossing opens a new paragraph under its own label`() {
        val md = PassageMarkdown.build(
            "John 3:36; Proverbs 3:5",
            listOf(verse("JHN", 3, 36, "Whoever believes"), verse("PRO", 3, 5, "Trust in the LORD")),
        )
        assertEquals(
            "**John 3:36; Proverbs 3:5**\n\n36 Whoever believes\n\n**Proverbs 3**\n\n5 Trust in the LORD",
            md,
        )
    }

    @Test
    fun `the psalm title name is used for a crossing into Psalms`() {
        val md = PassageMarkdown.build(
            "x",
            listOf(verse("JHN", 1, 1, "In the beginning"), verse("PSA", 23, 1, "The LORD is my shepherd")),
        )
        assertTrue(md.contains("**Psalm 23**"))
    }

    @Test
    fun `no verses builds nothing`() {
        assertEquals("", PassageMarkdown.build("John 3:16", emptyList()))
    }

    @Test
    fun `ten verses are within the cap and eleven are not`() {
        assertTrue(PassageMarkdown.withinCap(passages("JHN:3:10-3:19")))
        assertFalse(PassageMarkdown.withinCap(passages("JHN:3:10-3:20")))
    }

    @Test
    fun `the cap counts across ranges and books`() {
        assertTrue(PassageMarkdown.withinCap(passages("JHN:3:14-3:18,PRO:3:5-3:9")))   // 5 + 5
        assertFalse(PassageMarkdown.withinCap(passages("JHN:3:14-3:18,PRO:3:5-3:10")))  // 5 + 6
    }

    @Test
    fun `a whole chapter is refused however short`() {
        assertFalse(PassageMarkdown.withinCap(passages("PSA:117:0-117:999")))
        assertFalse(PassageMarkdown.withinCap(passages("JHN:3:16-3:16,PSA:117:0-117:999")))
    }

    @Test
    fun `a cross-chapter range is refused`() {
        assertFalse(PassageMarkdown.withinCap(passages("JHN:3:35-4:2")))
    }
}
