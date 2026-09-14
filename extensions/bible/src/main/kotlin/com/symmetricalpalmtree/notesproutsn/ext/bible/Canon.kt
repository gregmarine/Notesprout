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
    /** The human abbreviations the reference parser accepts ("Ps", "1 Cor", "Song of Songs") —
     *  arc 38 / R1, Biblesprout's table brought back with `Reference.kt`. */
    val aliases: List<String> = emptyList(),
)

/**
 * The 66-book Protestant canon, in order. USFM codes follow the Paratext /
 * unfoldingWord standard, and the names and ordinals are the ones
 * `tools/bible/build_bible_db.py` wrote into the `book` table — the two lists
 * must agree or a chapter title and its database row would disagree.
 *
 * Ported from Biblesprout (`data/Canon.kt`). Arc 37 left the alias table and
 * `lookup`/`normalize` behind (no free-text parsing then); arc 38 / R1 brought
 * them back verbatim for the notebook's Bible reference objects (`Reference.kt`).
 */
object Canon {
    val books: List<CanonBook> = listOf(
        // --- Old Testament ---
        CanonBook("GEN", 1, "Genesis", Testament.OLD, listOf("gn")),
        CanonBook("EXO", 2, "Exodus", Testament.OLD, listOf("ex", "exod")),
        CanonBook("LEV", 3, "Leviticus", Testament.OLD, listOf("lv")),
        CanonBook("NUM", 4, "Numbers", Testament.OLD, listOf("nm", "nb")),
        CanonBook("DEU", 5, "Deuteronomy", Testament.OLD, listOf("dt", "deut")),
        CanonBook("JOS", 6, "Joshua", Testament.OLD, listOf("jsh", "josh")),
        CanonBook("JDG", 7, "Judges", Testament.OLD, listOf("jdgs", "judg")),
        CanonBook("RUT", 8, "Ruth", Testament.OLD, listOf("rth")),
        CanonBook("1SA", 9, "1 Samuel", Testament.OLD, listOf("1sam", "1sm")),
        CanonBook("2SA", 10, "2 Samuel", Testament.OLD, listOf("2sam", "2sm")),
        CanonBook("1KI", 11, "1 Kings", Testament.OLD, listOf("1kgs", "1kg")),
        CanonBook("2KI", 12, "2 Kings", Testament.OLD, listOf("2kgs", "2kg")),
        CanonBook("1CH", 13, "1 Chronicles", Testament.OLD, listOf("1chr", "1chron")),
        CanonBook("2CH", 14, "2 Chronicles", Testament.OLD, listOf("2chr", "2chron")),
        CanonBook("EZR", 15, "Ezra", Testament.OLD),
        CanonBook("NEH", 16, "Nehemiah", Testament.OLD, listOf("ne")),
        CanonBook("EST", 17, "Esther", Testament.OLD, listOf("esth")),
        CanonBook("JOB", 18, "Job", Testament.OLD, listOf("jb")),
        CanonBook("PSA", 19, "Psalms", Testament.OLD, listOf("ps", "psalm", "psm", "pss")),
        CanonBook("PRO", 20, "Proverbs", Testament.OLD, listOf("prov", "prv")),
        CanonBook("ECC", 21, "Ecclesiastes", Testament.OLD, listOf("eccl", "qoh")),
        CanonBook("SNG", 22, "Song of Solomon", Testament.OLD, listOf("song", "songofsongs", "sos", "canticles", "cant")),
        CanonBook("ISA", 23, "Isaiah", Testament.OLD, listOf("is", "isa")),
        CanonBook("JER", 24, "Jeremiah", Testament.OLD, listOf("je", "jer")),
        CanonBook("LAM", 25, "Lamentations", Testament.OLD, listOf("la")),
        CanonBook("EZK", 26, "Ezekiel", Testament.OLD, listOf("ez", "ezek")),
        CanonBook("DAN", 27, "Daniel", Testament.OLD, listOf("dn")),
        CanonBook("HOS", 28, "Hosea", Testament.OLD, listOf("ho")),
        CanonBook("JOL", 29, "Joel", Testament.OLD, listOf("jl")),
        CanonBook("AMO", 30, "Amos", Testament.OLD, listOf("am")),
        CanonBook("OBA", 31, "Obadiah", Testament.OLD, listOf("ob", "obad")),
        CanonBook("JON", 32, "Jonah", Testament.OLD, listOf("jnh")),
        CanonBook("MIC", 33, "Micah", Testament.OLD, listOf("mc")),
        CanonBook("NAM", 34, "Nahum", Testament.OLD, listOf("na", "nah")),
        CanonBook("HAB", 35, "Habakkuk", Testament.OLD, listOf("hb", "hab")),
        CanonBook("ZEP", 36, "Zephaniah", Testament.OLD, listOf("zph", "zeph")),
        CanonBook("HAG", 37, "Haggai", Testament.OLD, listOf("hg", "hag")),
        CanonBook("ZEC", 38, "Zechariah", Testament.OLD, listOf("zc", "zech")),
        CanonBook("MAL", 39, "Malachi", Testament.OLD, listOf("ml", "mal")),
        // --- New Testament ---
        CanonBook("MAT", 40, "Matthew", Testament.NEW, listOf("mt", "matt")),
        CanonBook("MRK", 41, "Mark", Testament.NEW, listOf("mk", "mrk")),
        CanonBook("LUK", 42, "Luke", Testament.NEW, listOf("lk")),
        CanonBook("JHN", 43, "John", Testament.NEW, listOf("jn", "jhn")),
        CanonBook("ACT", 44, "Acts", Testament.NEW, listOf("ac")),
        CanonBook("ROM", 45, "Romans", Testament.NEW, listOf("ro", "rm")),
        CanonBook("1CO", 46, "1 Corinthians", Testament.NEW, listOf("1cor")),
        CanonBook("2CO", 47, "2 Corinthians", Testament.NEW, listOf("2cor")),
        CanonBook("GAL", 48, "Galatians", Testament.NEW, listOf("ga")),
        CanonBook("EPH", 49, "Ephesians", Testament.NEW, listOf("ep")),
        CanonBook("PHP", 50, "Philippians", Testament.NEW, listOf("php", "phil", "pp")),
        CanonBook("COL", 51, "Colossians", Testament.NEW, listOf("co")),
        CanonBook("1TH", 52, "1 Thessalonians", Testament.NEW, listOf("1thess", "1thes")),
        CanonBook("2TH", 53, "2 Thessalonians", Testament.NEW, listOf("2thess", "2thes")),
        CanonBook("1TI", 54, "1 Timothy", Testament.NEW, listOf("1tim")),
        CanonBook("2TI", 55, "2 Timothy", Testament.NEW, listOf("2tim")),
        CanonBook("TIT", 56, "Titus", Testament.NEW, listOf("ti")),
        CanonBook("PHM", 57, "Philemon", Testament.NEW, listOf("phm", "phlm", "philem")),
        CanonBook("HEB", 58, "Hebrews", Testament.NEW, listOf("he")),
        CanonBook("JAS", 59, "James", Testament.NEW, listOf("jm", "jas")),
        CanonBook("1PE", 60, "1 Peter", Testament.NEW, listOf("1pet", "1pt")),
        CanonBook("2PE", 61, "2 Peter", Testament.NEW, listOf("2pet", "2pt")),
        CanonBook("1JN", 62, "1 John", Testament.NEW, listOf("1jn", "1jhn", "1jo")),
        CanonBook("2JN", 63, "2 John", Testament.NEW, listOf("2jn", "2jhn", "2jo")),
        CanonBook("3JN", 64, "3 John", Testament.NEW, listOf("3jn", "3jhn", "3jo")),
        CanonBook("JUD", 65, "Jude", Testament.NEW, listOf("jud", "jd")),
        CanonBook("REV", 66, "Revelation", Testament.NEW, listOf("re", "rev", "apocalypse", "apoc")),
    )

    private val byUsfm: Map<String, CanonBook> = books.associateBy { it.usfm }

    // Declared before byAlias: its initializer calls normalize(), which uses these, and an
    // object's properties initialize in declaration order.
    private val romanPrefix = Regex("^(iii|ii|i)\\s+")
    private val ordinalPrefix = Regex("^(1st|2nd|3rd|first|second|third)\\s+")
    private val strip = Regex("[\\s.]+")

    /** Normalised alias → book, built once from names, USFM codes and aliases (arc 38 / R1). */
    private val byAlias: Map<String, CanonBook> = buildMap {
        fun add(key: String, b: CanonBook) {
            val n = normalize(key)
            if (n.isNotEmpty()) put(n, b)
        }
        for (b in books) {
            add(b.usfm, b)
            add(b.name, b)
            b.aliases.forEach { add(it, b) }
        }
    }

    fun byUsfm(usfm: String): CanonBook =
        byUsfm[usfm.uppercase()] ?: error("Unknown USFM code: $usfm")

    fun tryUsfm(usfm: String): CanonBook? = byUsfm[usfm.uppercase()]

    /**
     * The name a **chapter** wears — on the page heading and in the running head — as distinct
     * from the name the **book** wears in the index. They differ for exactly one book: the book
     * is "Psalms" but a chapter of it is "Psalm 23", the way every printed Bible says it (the
     * user's call at the arc-37 freeze). Every other book names its chapters as itself.
     */
    fun chapterTitleName(usfm: String): String =
        if (usfm.equals("PSA", ignoreCase = true)) "Psalm" else byUsfm(usfm).name

    fun byOrdinal(ordinal: Int): CanonBook = books[ordinal - 1]

    /** Human-typed or source book name → canon entry, or null if unrecognised (arc 38 / R1). */
    fun lookup(name: String): CanonBook? = byAlias[normalize(name)]

    /**
     * Lowercases and strips whitespace/punctuation so "1 Cor.", "1cor" and "I Corinthians"
     * collapse toward a common key. Leading Roman numerals and ordinal words for the numbered
     * books are converted to digits first. Biblesprout's, verbatim.
     */
    private fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
        // Patterns are anchored at ^, so replace() matches at most once.
        s = romanPrefix.replace(s) { m ->
            when (m.groupValues[1]) { "iii" -> "3"; "ii" -> "2"; else -> "1" }
        }
        s = ordinalPrefix.replace(s) { m ->
            when (m.groupValues[1]) {
                "2nd", "second" -> "2"; "3rd", "third" -> "3"; else -> "1"
            }
        }
        return s.replace(strip, "")
    }
}
