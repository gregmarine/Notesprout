package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.Atom
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.BreakAtom
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.Flow
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.HeadingAtom
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.HeadingKind
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.NumberAtom
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.WordAtom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The passage view's atom stream (arc 38 / R2) — the pure half of [PassageLoader], tested over
 * fake [VerseRow]s: a heading at every chapter crossing and nowhere else, a paragraph per chapter
 * run, a superscript number in front of every verse, and words that a page can be cut between.
 */
class PassageAtomsTest {

    private fun verse(usfm: String, chapter: Int, verse: Int, text: String) = VerseRow(
        verseKey = VerseKey.encode(Canon.byUsfm(usfm).ordinal, chapter, verse),
        usfm = usfm,
        chapter = chapter,
        verse = verse,
        text = text,
    )

    private fun headings(atoms: List<Atom>) = atoms.filterIsInstance<HeadingAtom>().map { it.text }
    private fun words(atoms: List<Atom>) = atoms.filterIsInstance<WordAtom>().map { it.word }
    private fun numbers(atoms: List<Atom>) = atoms.filterIsInstance<NumberAtom>().map { it.number }

    @Test
    fun `one chapter opens with its heading and one paragraph`() {
        val atoms = PassageAtoms.atomsFor(
            listOf(
                verse("JHN", 3, 16, "For God so loved"),
                verse("JHN", 3, 17, "For God did not send"),
            ),
        )
        assertEquals(listOf("John 3"), headings(atoms))
        assertEquals(HeadingKind.MAJOR, atoms.filterIsInstance<HeadingAtom>().single().kind)
        assertEquals(1, atoms.count { it is BreakAtom })
        assertEquals(Flow.PARAGRAPH, atoms.filterIsInstance<BreakAtom>().single().flow)
        assertEquals(listOf(16, 17), numbers(atoms))
        assertEquals(
            listOf("For", "God", "so", "loved", "For", "God", "did", "not", "send"),
            words(atoms),
        )
    }

    @Test
    fun `the heading and the break open the stream, then the first number`() {
        val atoms = PassageAtoms.atomsFor(listOf(verse("JHN", 3, 16, "For God")))
        assertTrue(atoms[0] is HeadingAtom)
        assertTrue(atoms[1] is BreakAtom)
        assertEquals(NumberAtom(16, VerseKey.encode(43, 3, 16)), atoms[2])
    }

    @Test
    fun `a chapter crossing inside one book gets its own heading and paragraph`() {
        val atoms = PassageAtoms.atomsFor(
            listOf(
                verse("JHN", 3, 36, "Whoever believes"),
                verse("JHN", 4, 1, "When Jesus learned"),
            ),
        )
        assertEquals(listOf("John 3", "John 4"), headings(atoms))
        assertEquals(2, atoms.count { it is BreakAtom })
    }

    @Test
    fun `a book crossing gets its own heading`() {
        val atoms = PassageAtoms.atomsFor(
            listOf(
                verse("JHN", 3, 16, "For God"),
                verse("PRO", 3, 5, "Trust in the LORD"),
            ),
        )
        assertEquals(listOf("John 3", "Proverbs 3"), headings(atoms))
    }

    @Test
    fun `a heading names a chapter of Psalms as the running head does`() {
        val atoms = PassageAtoms.atomsFor(listOf(verse("PSA", 23, 1, "The LORD is my shepherd")))
        assertEquals(listOf("Psalm 23"), headings(atoms))
    }

    @Test
    fun `two ranges of the same chapter do not repeat the heading`() {
        // `John 3:16, 18` reaches the builder as two reads concatenated — one chapter, one heading.
        val atoms = PassageAtoms.atomsFor(
            listOf(verse("JHN", 3, 16, "For God"), verse("JHN", 3, 18, "Whoever believes")),
        )
        assertEquals(listOf("John 3"), headings(atoms))
        assertEquals(listOf(16, 18), numbers(atoms))
    }

    @Test
    fun `runs of spaces never become empty words`() {
        val atoms = PassageAtoms.atomsFor(listOf(verse("JHN", 3, 16, "  For   God ")))
        assertEquals(listOf("For", "God"), words(atoms))
    }

    @Test
    fun `a verse with no text still carries its number`() {
        val atoms = PassageAtoms.atomsFor(listOf(verse("JHN", 3, 16, "")))
        assertEquals(listOf(16), numbers(atoms))
        assertTrue(words(atoms).isEmpty())
    }

    @Test
    fun `nothing in, nothing out`() {
        assertTrue(PassageAtoms.atomsFor(emptyList()).isEmpty())
    }
}
