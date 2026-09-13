package com.symmetricalpalmtree.notesproutsn.ext.bible.reader

import com.symmetricalpalmtree.notesproutsn.ext.bible.Footnote
import com.symmetricalpalmtree.notesproutsn.ext.bible.RenderBlock
import com.symmetricalpalmtree.notesproutsn.ext.bible.VerseKey
import com.symmetricalpalmtree.notesproutsn.ext.bible.VerseMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paginator (arc 37 / B1), JVM-side: the block layer becomes atoms, and
 * atoms become pages against a fake [BodyMeasurer] — the split that keeps the
 * real measuring (a `StaticLayout`, which needs Android) out of these tests.
 */
class ChapterPaginatorTest {

    // Psalm 23 is ordinal 19 — the shape these blocks imitate.
    private fun key(chapter: Int, verse: Int) = VerseKey.encode(19, chapter, verse)

    /** Every atom height is 10 px per atom — page fits are then plain arithmetic. */
    private val tenEach = BodyMeasurer { _, _, count, _ -> count * 10 }

    /** Nothing ever fits: one atom already overruns the tallest page below. */
    private val nothingFits = BodyMeasurer { _, _, count, _ -> count * 100 }

    @Test
    fun `blocks become headings, numbers, words and a spliced footnote caller`() {
        val blocks = listOf(
            RenderBlock(1, "s1", null, "The LORD Is My Shepherd", emptyList()),
            RenderBlock(2, "r", null, "(John 10:1–21)", emptyList()),
            RenderBlock(
                3, "d", key(23, 1), "1 A Psalm of David.",
                listOf(VerseMark(0, 2, key(23, 1), 1)),
            ),
            RenderBlock(
                4, "q1", key(23, 2), "2 The LORD is my shepherd;",
                listOf(VerseMark(0, 2, key(23, 2), 2)),
            ),
            RenderBlock(5, "q2", null, "I shall not want.", emptyList()),
            RenderBlock(6, "b", null, "", emptyList()),
            RenderBlock(
                7, "q1", key(23, 3), "3 He restores my soul.",
                listOf(VerseMark(0, 2, key(23, 3), 3)),
            ),
        )
        // The caller sits right after "LORD" (chars 6 until 10 of block 4's content).
        val footnotes = listOf(Footnote(7, blockId = 4, offset = 10, verseKey = key(23, 2), label = "23:2", text = "Or Yahweh"))

        val atoms = ChapterPaginator.atomsForBlocks(blocks, footnotes)

        assertEquals(
            listOf<Atom>(
                HeadingAtom("The LORD Is My Shepherd", HeadingKind.MAJOR),
                HeadingAtom("(John 10:1–21)", HeadingKind.REFERENCE),
                // A psalm superscription is a heading, so its verse marker stays
                // inline in the heading's own text — it is not lifted out.
                HeadingAtom("1 A Psalm of David.", HeadingKind.SUPERSCRIPTION),
                BreakAtom(Flow.POETRY1),
                NumberAtom(2, key(23, 2)),
                WordAtom("The"),
                WordAtom("LORD"),
                FootnoteAtom(7),
                WordAtom("is"),
                WordAtom("my"),
                WordAtom("shepherd;"),
                BreakAtom(Flow.POETRY2),
                WordAtom("I"),
                WordAtom("shall"),
                WordAtom("not"),
                WordAtom("want."),
                BreakAtom(Flow.STANZA),
                BreakAtom(Flow.POETRY1),
                NumberAtom(3, key(23, 3)),
                WordAtom("He"),
                WordAtom("restores"),
                WordAtom("my"),
                WordAtom("soul."),
            ),
            atoms,
        )
    }

    @Test
    fun `minor heading kinds map to MINOR`() {
        val blocks = listOf("s2", "s3", "mr", "qa", "sr", "sp").mapIndexed { i, kind ->
            RenderBlock(i + 1, kind, null, kind, emptyList())
        }
        val kinds = ChapterPaginator.atomsForBlocks(blocks, emptyList())
            .filterIsInstance<HeadingAtom>().map { it.kind }
        assertEquals(List(6) { HeadingKind.MINOR }, kinds)
    }

    @Test
    fun `a page never ends on a verse number`() {
        val atoms = poem()
        val pages = ChapterPaginator.paginate(atoms, tenEach, WIDTH, 35, 50)

        assertTrue("some pages", pages.isNotEmpty())
        for (page in pages) {
            assertTrue("no empty page", page.isNotEmpty())
            // A number stranded at a page's foot would print without its verse.
            assertTrue("page ends on ${page.last()}", page.last() !is NumberAtom)
        }
        // Nothing is dropped or duplicated: the pages ARE the chapter.
        assertEquals(atoms, pages.flatten())
    }

    @Test
    fun `pagination still makes progress when not even one atom fits`() {
        val atoms = poem()
        val pages = ChapterPaginator.paginate(atoms, nothingFits, WIDTH, 35, 50)

        assertEquals("one atom per page rather than a stall", atoms.size, pages.size)
        assertTrue(pages.all { it.size == 1 })
        assertEquals(atoms, pages.flatten())
    }

    @Test
    fun `fitCount is zero when one atom does not fit`() {
        val atoms = poem()
        assertEquals(0, ChapterPaginator.fitCount(atoms, 0, 35, nothingFits, WIDTH))
        // …and it counts exactly what fits when things do.
        assertEquals(3, ChapterPaginator.fitCount(atoms, 0, 35, tenEach, WIDTH))
        assertEquals(5, ChapterPaginator.fitCount(atoms, 0, 50, tenEach, WIDTH))
        assertEquals(0, ChapterPaginator.fitCount(atoms, atoms.size, 50, tenEach, WIDTH))
    }

    @Test
    fun `firstVerseKey is the page's first verse number, or null mid-verse`() {
        val page = listOf(
            BreakAtom(Flow.PARAGRAPH),
            NumberAtom(2, key(23, 2)),
            WordAtom("The"),
            NumberAtom(3, key(23, 3)),
        )
        assertEquals(key(23, 2), ChapterPaginator.firstVerseKey(page))
        // A page that opens inside a verse carries no number of its own.
        assertNull(ChapterPaginator.firstVerseKey(listOf(WordAtom("shepherd;"), WordAtom("I"))))
        assertNull(ChapterPaginator.firstVerseKey(emptyList()))
    }

    /** Twelve verses of three words each, in poetry lines — enough for several pages. */
    private fun poem(): List<Atom> {
        val atoms = ArrayList<Atom>()
        for (verse in 1..12) {
            atoms.add(BreakAtom(if (verse % 2 == 0) Flow.POETRY1 else Flow.POETRY2))
            atoms.add(NumberAtom(verse, key(23, verse)))
            repeat(3) { word -> atoms.add(WordAtom("w$verse-$word")) }
        }
        return atoms
    }

    private companion object {
        /** The fake measurers ignore it; it only has to be a plausible px width. */
        const val WIDTH = 600
    }
}
