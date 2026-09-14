package com.symmetricalpalmtree.notesproutsn.ext.bible

/**
 * The verses of a small passage as the Markdown a notebook text object holds (arc 40 "Verses")
 * — the pure half of `IBible.passageText`, JVM-tested over fake [VerseRow]s with no database.
 *
 * The user's shape (2026-09-13): a **bold label line** — the canonical form the reference
 * resolves to (`**John 3:16–18**`) — then the verses as prose with **plain verse numbers**
 * (`16 For God so loved…`), one paragraph per chapter run, exactly where [PassageAtoms] puts a
 * heading: a passage read across a chapter edge changes paragraph there, and a passage across
 * books says so with a second label line of its own.
 *
 * The cap is the user's rule — "only small references; full chapters are too big" — and the
 * reader enforces it, because only the reader knows how many verses a wire names:
 * [MAX_VERSES] verses in all, and **no whole-chapter range at all**, however short the chapter.
 * The host adds its own page-fit check on top; this one is about what may be asked for.
 *
 * **Never logged**: what this builds is scripture, and where it comes from is a reference.
 */
object PassageMarkdown {

    /** Ten verses — about half a page at the notebook's 24 sp; the user's number. */
    const val MAX_VERSES = 10

    /**
     * Whether [passages] may be asked for as text, from the reference alone: no whole-chapter
     * range, and at most [MAX_VERSES] verses **as named** — the difference between the endpoints,
     * not the rows the source has (a hole in the source makes a passage shorter, never longer).
     * A range crossing a chapter ("3:36–4:2", the user's call 2026-09-13) is counted by what the
     * reference alone can know — its last chapter's verses plus one — and the rows decide the
     * rest ([rowsWithinCap] over the verses read). Pure.
     */
    fun withinCap(passages: List<Passage>): Boolean {
        var verses = 0
        for (passage in passages) {
            for (range in passage.ranges) {
                val sv = VerseKey.verseOf(range.startKey)
                val ev = VerseKey.verseOf(range.endKey)
                if (sv == 0 || ev == VerseKey.MAX_VERSE) return false
                val crossing = VerseKey.chapterOf(range.startKey) != VerseKey.chapterOf(range.endKey)
                verses += if (crossing) ev + 1 else ev - sv + 1
                if (verses > MAX_VERSES) return false
            }
        }
        return verses in 1..MAX_VERSES
    }

    /** The cap over the rows actually read — the exact answer for a chapter-crossing range. */
    fun rowsWithinCap(verses: List<VerseRow>): Boolean = verses.size in 1..MAX_VERSES

    /**
     * [verses] (in reading order, the ranges concatenated as written, the rows the source has)
     * under [label] as Markdown. Every (book, chapter) run is one paragraph; the first opens
     * under the bold label, a later one under a bold label of its own book and chapter so the
     * reader never mistakes where a paragraph came from. Empty for no verses.
     */
    fun build(label: String, verses: List<VerseRow>): String {
        if (verses.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("**").append(label.trim()).append("**")
        var usfm: String? = null
        var chapter = 0
        var first = true
        for (row in verses) {
            if (row.usfm != usfm || row.chapter != chapter) {
                usfm = row.usfm
                chapter = row.chapter
                if (!first) {
                    sb.append("\n\n**").append(Canon.chapterTitleName(row.usfm)).append(' ').append(row.chapter).append("**")
                }
                sb.append("\n\n")
                first = false
            } else {
                sb.append(' ')
            }
            sb.append(row.verse).append(' ').append(row.text.trim())
        }
        return sb.toString()
    }
}
