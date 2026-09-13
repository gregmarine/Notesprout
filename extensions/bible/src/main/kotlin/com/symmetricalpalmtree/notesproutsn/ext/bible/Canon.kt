package com.symmetricalpalmtree.notesproutsn.ext.bible

/** Old or New Testament. */
enum class Testament { OLD, NEW }

/**
 * One book in the canonical 66-book table: the USFM code (the cross-source key,
 * e.g. `GEN`, `1CO`), the 1-based canonical [ordinal] (used for ordering and to
 * pack a verse address into a single sortable integer — see [VerseKey]), the
 * display [name] and its [testament].
 *
 * Deliberately translation-independent: a source may label a book "Psalm" vs
 * "Psalms", but both map back to the same USFM code here.
 */
data class CanonBook(
    val usfm: String,
    val ordinal: Int,
    val name: String,
    val testament: Testament,
)

/**
 * The 66-book Protestant canon, in order. USFM codes follow the Paratext /
 * unfoldingWord standard, and the names and ordinals are the ones
 * `tools/bible/build_bible_db.py` wrote into the `book` table — the two lists
 * must agree or a chapter title and its database row would disagree.
 *
 * Ported from Biblesprout (`data/Canon.kt`) **minus the alias table**: this
 * reader has no free-text reference parsing (no search, no "go to" field), so
 * `lookup`/`normalize` and the aliases they used came out with `Reference.kt`.
 */
object Canon {
    val books: List<CanonBook> = listOf(
        // --- Old Testament ---
        CanonBook("GEN", 1, "Genesis", Testament.OLD),
        CanonBook("EXO", 2, "Exodus", Testament.OLD),
        CanonBook("LEV", 3, "Leviticus", Testament.OLD),
        CanonBook("NUM", 4, "Numbers", Testament.OLD),
        CanonBook("DEU", 5, "Deuteronomy", Testament.OLD),
        CanonBook("JOS", 6, "Joshua", Testament.OLD),
        CanonBook("JDG", 7, "Judges", Testament.OLD),
        CanonBook("RUT", 8, "Ruth", Testament.OLD),
        CanonBook("1SA", 9, "1 Samuel", Testament.OLD),
        CanonBook("2SA", 10, "2 Samuel", Testament.OLD),
        CanonBook("1KI", 11, "1 Kings", Testament.OLD),
        CanonBook("2KI", 12, "2 Kings", Testament.OLD),
        CanonBook("1CH", 13, "1 Chronicles", Testament.OLD),
        CanonBook("2CH", 14, "2 Chronicles", Testament.OLD),
        CanonBook("EZR", 15, "Ezra", Testament.OLD),
        CanonBook("NEH", 16, "Nehemiah", Testament.OLD),
        CanonBook("EST", 17, "Esther", Testament.OLD),
        CanonBook("JOB", 18, "Job", Testament.OLD),
        CanonBook("PSA", 19, "Psalms", Testament.OLD),
        CanonBook("PRO", 20, "Proverbs", Testament.OLD),
        CanonBook("ECC", 21, "Ecclesiastes", Testament.OLD),
        CanonBook("SNG", 22, "Song of Solomon", Testament.OLD),
        CanonBook("ISA", 23, "Isaiah", Testament.OLD),
        CanonBook("JER", 24, "Jeremiah", Testament.OLD),
        CanonBook("LAM", 25, "Lamentations", Testament.OLD),
        CanonBook("EZK", 26, "Ezekiel", Testament.OLD),
        CanonBook("DAN", 27, "Daniel", Testament.OLD),
        CanonBook("HOS", 28, "Hosea", Testament.OLD),
        CanonBook("JOL", 29, "Joel", Testament.OLD),
        CanonBook("AMO", 30, "Amos", Testament.OLD),
        CanonBook("OBA", 31, "Obadiah", Testament.OLD),
        CanonBook("JON", 32, "Jonah", Testament.OLD),
        CanonBook("MIC", 33, "Micah", Testament.OLD),
        CanonBook("NAM", 34, "Nahum", Testament.OLD),
        CanonBook("HAB", 35, "Habakkuk", Testament.OLD),
        CanonBook("ZEP", 36, "Zephaniah", Testament.OLD),
        CanonBook("HAG", 37, "Haggai", Testament.OLD),
        CanonBook("ZEC", 38, "Zechariah", Testament.OLD),
        CanonBook("MAL", 39, "Malachi", Testament.OLD),
        // --- New Testament ---
        CanonBook("MAT", 40, "Matthew", Testament.NEW),
        CanonBook("MRK", 41, "Mark", Testament.NEW),
        CanonBook("LUK", 42, "Luke", Testament.NEW),
        CanonBook("JHN", 43, "John", Testament.NEW),
        CanonBook("ACT", 44, "Acts", Testament.NEW),
        CanonBook("ROM", 45, "Romans", Testament.NEW),
        CanonBook("1CO", 46, "1 Corinthians", Testament.NEW),
        CanonBook("2CO", 47, "2 Corinthians", Testament.NEW),
        CanonBook("GAL", 48, "Galatians", Testament.NEW),
        CanonBook("EPH", 49, "Ephesians", Testament.NEW),
        CanonBook("PHP", 50, "Philippians", Testament.NEW),
        CanonBook("COL", 51, "Colossians", Testament.NEW),
        CanonBook("1TH", 52, "1 Thessalonians", Testament.NEW),
        CanonBook("2TH", 53, "2 Thessalonians", Testament.NEW),
        CanonBook("1TI", 54, "1 Timothy", Testament.NEW),
        CanonBook("2TI", 55, "2 Timothy", Testament.NEW),
        CanonBook("TIT", 56, "Titus", Testament.NEW),
        CanonBook("PHM", 57, "Philemon", Testament.NEW),
        CanonBook("HEB", 58, "Hebrews", Testament.NEW),
        CanonBook("JAS", 59, "James", Testament.NEW),
        CanonBook("1PE", 60, "1 Peter", Testament.NEW),
        CanonBook("2PE", 61, "2 Peter", Testament.NEW),
        CanonBook("1JN", 62, "1 John", Testament.NEW),
        CanonBook("2JN", 63, "2 John", Testament.NEW),
        CanonBook("3JN", 64, "3 John", Testament.NEW),
        CanonBook("JUD", 65, "Jude", Testament.NEW),
        CanonBook("REV", 66, "Revelation", Testament.NEW),
    )

    private val byUsfm: Map<String, CanonBook> = books.associateBy { it.usfm }

    fun byUsfm(usfm: String): CanonBook =
        byUsfm[usfm.uppercase()] ?: error("Unknown USFM code: $usfm")

    fun tryUsfm(usfm: String): CanonBook? = byUsfm[usfm.uppercase()]

    fun byOrdinal(ordinal: Int): CanonBook = books[ordinal - 1]
}
