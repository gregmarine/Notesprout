package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arc 38 / R1 — the reference machinery, JVM-side: the parser (Biblesprout's, ported), the wire
 * codec, the bounds check over fake source answers, and the canon's alias lookup.
 */
class ReferenceTest {

    private fun key(ordinal: Int, c: Int, v: Int) = VerseKey.encode(ordinal, c, v)

    // --- Canon aliases ------------------------------------------------------

    @Test
    fun `lookup accepts names, codes, abbreviations, roman and ordinal prefixes`() {
        assertEquals("JHN", Canon.lookup("John")?.usfm)
        assertEquals("JHN", Canon.lookup("jn")?.usfm)
        assertEquals("JHN", Canon.lookup("JHN")?.usfm)
        assertEquals("1CO", Canon.lookup("1 Cor.")?.usfm)
        assertEquals("1CO", Canon.lookup("I Corinthians")?.usfm)
        assertEquals("1CO", Canon.lookup("First Corinthians")?.usfm)
        assertEquals("PSA", Canon.lookup("Ps")?.usfm)
        assertEquals("PSA", Canon.lookup("Psalm")?.usfm)
        assertEquals("SNG", Canon.lookup("Song of Songs")?.usfm)
        assertNull(Canon.lookup("Hezekiah"))
        assertNull(Canon.lookup(""))
    }

    // --- the parser ---------------------------------------------------------

    @Test
    fun `a single verse, a verse range and a whole chapter`() {
        val single = ReferenceParser.parse("John 3:16")!!
        assertEquals("JHN", single.book.usfm)
        assertEquals(listOf(VerseRange(key(43, 3, 16), key(43, 3, 16))), single.ranges)

        val range = ReferenceParser.parse("Jn 3:14-18")!!
        assertEquals(listOf(VerseRange(key(43, 3, 14), key(43, 3, 18))), range.ranges)

        val chapter = ReferenceParser.parse("Genesis 1")!!
        assertEquals(listOf(VerseRange(key(1, 1, 0), key(1, 1, VerseKey.MAX_VERSE))), chapter.ranges)
    }

    @Test
    fun `en dashes, spaceless forms and a cross-chapter span`() {
        assertEquals(
            listOf(VerseRange(key(19, 23, 1), key(19, 23, 3))),
            ReferenceParser.parse("Ps23:1–3")!!.ranges,
        )
        assertEquals(
            listOf(VerseRange(key(1, 1, 5), key(1, 2, 3))),
            ReferenceParser.parse("Genesis 1:5-2:3")!!.ranges,
        )
    }

    @Test
    fun `verse lists carry the chapter forward`() {
        val p = ReferenceParser.parse("John 3:14-16, 18")!!
        assertEquals(
            listOf(VerseRange(key(43, 3, 14), key(43, 3, 16)), VerseRange(key(43, 3, 18), key(43, 3, 18))),
            p.ranges,
        )
        assertEquals("John 3:14–16, 18", p.format())
    }

    @Test
    fun `parseAll splits books and continues the previous one on a bare number`() {
        val all = ReferenceParser.parseAll("John 3:14-18, Proverbs 3:5-6; John 1:1, 14")
        assertEquals(listOf("JHN", "PRO", "JHN"), all.map { it.book.usfm })
        assertEquals("John 1:1, 14", all[2].format())
        assertEquals("John 3:14–18; Proverbs 3:5–6; John 1:1, 14", ReferenceCodec.label(all))
    }

    @Test
    fun `any bad part refuses the whole line`() {
        assertTrue(ReferenceParser.parseAll("John 3:16, Hezekiah 2:1").isEmpty())
        assertTrue(ReferenceParser.parseAll("3:16").isEmpty())
        assertTrue(ReferenceParser.parseAll("").isEmpty())
        assertTrue(ReferenceParser.parseAll("just some words").isEmpty())
        assertNull(ReferenceParser.parse("John 3:18-14"))
        assertNull(ReferenceParser.parse("John 0:1"))
        assertNull(ReferenceParser.parse("John 3:0"))
        assertNull(ReferenceParser.parse("John"))
    }

    // --- the codec ----------------------------------------------------------

    @Test
    fun `wire form round-trips and reads as the plan says`() {
        val all = ReferenceParser.parseAll("John 3:14-18, Proverbs 3:5-6, Genesis 1")
        val wire = ReferenceCodec.encode(all)
        assertEquals("JHN:3:14-3:18,PRO:3:5-3:6,GEN:1:0-1:999", wire)
        val back = ReferenceCodec.decode(wire)!!
        assertEquals(all.map { it.book.usfm }, back.map { it.book.usfm })
        assertEquals(all.flatMap { it.ranges }, back.flatMap { it.ranges })
        assertEquals("John 3:14–18; Proverbs 3:5–6; Genesis 1", ReferenceCodec.label(back))
        assertTrue(wire.none { it == '|' || it.isWhitespace() })
    }

    @Test
    fun `wholeChapter parks a chapter the way a typed one reads (B9 Send)`() {
        val whole = ReferenceCodec.wholeChapter(ChapterRef("JHN", 3))
        assertEquals("JHN:3:0-3:999", ReferenceCodec.encode(listOf(whole)))
        assertEquals("John 3", whole.format())
        // The same wire and label a typed "John 3" resolves to — the reader and the dialog agree.
        assertEquals(ReferenceCodec.encode(ReferenceParser.parseAll("John 3")), ReferenceCodec.encode(listOf(whole)))
        assertEquals(listOf(whole), ReferenceCodec.decode("JHN:3:0-3:999"))
        // The canonical form, as a typed "Psalm 23" resolves — not the reader title's "Psalm 23".
        assertEquals("Psalms 23", ReferenceCodec.wholeChapter(ChapterRef("PSA", 23)).format())
    }

    @Test
    fun `adjacent ranges of one book fold into one passage`() {
        val back = ReferenceCodec.decode("JHN:3:14-3:16,JHN:3:18-3:18")!!
        assertEquals(1, back.size)
        assertEquals("John 3:14–16, 18", back[0].format())
    }

    @Test
    fun `decode is total`() {
        assertNull(ReferenceCodec.decode(null))
        assertNull(ReferenceCodec.decode(""))
        assertNull(ReferenceCodec.decode("JHN:3:16"))
        assertNull(ReferenceCodec.decode("XXX:3:16-3:16"))
        assertNull(ReferenceCodec.decode("JHN:3:18-3:14"))
        assertNull(ReferenceCodec.decode("JHN:3:a-3:b"))
        assertNull(ReferenceCodec.decode("JHN:0:1-0:1"))
        assertNull(ReferenceCodec.decode("J".repeat(ReferenceCodec.MAX_WIRE_CHARS + 1)))
    }

    // --- the bounds check ---------------------------------------------------

    private val johnHas21 = { usfm: String -> if (usfm == "JHN") 21 else if (usfm == "PSA") 150 else 0 }

    @Test
    fun `a verse range needs its endpoints, a chapter range only its chapters`() {
        val exists = { k: Int -> VerseKey.verseOf(k) <= 36 }   // John 3 has 36 verses
        assertTrue(ReferenceResolver.valid(ReferenceParser.parseAll("John 3:14-18"), johnHas21, exists))
        assertTrue(ReferenceResolver.valid(ReferenceParser.parseAll("John 21"), johnHas21, exists))
        assertFalse(ReferenceResolver.valid(ReferenceParser.parseAll("John 3:14-99"), johnHas21, exists))
        assertFalse(ReferenceResolver.valid(ReferenceParser.parseAll("John 22"), johnHas21, exists))
        assertFalse(ReferenceResolver.valid(ReferenceParser.parseAll("John 3:16, Psalm 151"), johnHas21, exists))
        assertFalse(ReferenceResolver.valid(ReferenceParser.parseAll("Genesis 1"), johnHas21, exists))
        assertFalse(ReferenceResolver.valid(emptyList(), johnHas21, exists))
    }

    @Test
    fun `format drops the chapter once established and names a whole chapter bare`() {
        assertEquals("Genesis 1–2", ReferenceParser.parse("Gen 1-2")!!.format())
        assertEquals("John 3:16", ReferenceParser.parse("John 3:16")!!.format())
        assertEquals("Genesis 1:5–2:3", ReferenceParser.parse("Genesis 1:5-2:3")!!.format())
    }
}
