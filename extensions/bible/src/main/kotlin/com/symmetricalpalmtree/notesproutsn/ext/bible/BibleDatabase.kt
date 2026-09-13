package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable

/**
 * Read-only accessor for the bundled Bible source (`bsb.bible`, installed by
 * [ContentInstaller]). Plain `android.database.sqlite` — **no SQLCipher and no
 * Room**: the file is public-domain scripture shipped inside the APK, not user
 * data, and it is opened `OPEN_READONLY` so nothing here can ever write to it.
 *
 * All methods are blocking; call them off the main thread (`Dispatchers.IO`).
 *
 * Ported from Biblesprout (`data/BibleDatabase.kt`), trimmed to what the reader
 * needs — the SQL is copied exactly. Cross-references, the word layer,
 * concordance and verse slices are gone: the slim build carries no word layer.
 * Search (arc 37 / B8) is here, over the slim build's **FTS4** index — see
 * [search].
 */
class BibleDatabase private constructor(
    private val db: SQLiteDatabase,
    /** The source's `metadata` table as a plain map (id, title, versification…). */
    val metadata: Map<String, String>,
) : Closeable {
    val id: String get() = metadata["id"] ?: "unknown"
    val title: String get() = metadata["title"] ?: id

    override fun close() = db.close()

    /** The source's books in canonical order, with the chapter count of each. */
    fun books(): List<BookRow> {
        val out = ArrayList<BookRow>(66)
        db.rawQuery(
            "SELECT usfm, ordinal, name, testament, chapter_count FROM book ORDER BY ordinal",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    BookRow(
                        usfm = c.getString(0),
                        ordinal = c.getInt(1),
                        name = c.getString(2),
                        testament = if (c.getString(3) == "OT") Testament.OLD else Testament.NEW,
                        chapterCount = c.getInt(4),
                    ),
                )
            }
        }
        return out
    }

    /**
     * The chapter's display blocks (paragraphs, poetry lines, headings, stanza
     * breaks) in reading order, each carrying its verse-number spans — the rich
     * layer the formatted reader renders. `usfm` is the book code (e.g. "PSA").
     */
    fun blocksForChapter(usfm: String, chapter: Int): List<RenderBlock> {
        val markers = HashMap<Int, MutableList<VerseMark>>()
        db.rawQuery(
            """
            SELECT vm.block_id, vm.start, vm.end, vm.verse_key, vm.number
            FROM verse_marker vm JOIN block b ON b.id = vm.block_id
            WHERE b.usfm = ? AND b.chapter = ?
            """.trimIndent(),
            arrayOf(usfm, chapter.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                markers.getOrPut(c.getInt(0)) { mutableListOf() }
                    .add(VerseMark(c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4)))
            }
        }
        val out = ArrayList<RenderBlock>()
        db.rawQuery(
            "SELECT id, kind, start_key, content FROM block WHERE usfm = ? AND chapter = ? ORDER BY id",
            arrayOf(usfm, chapter.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0)
                out.add(
                    RenderBlock(
                        id = id,
                        kind = c.getString(1),
                        startKey = if (c.isNull(2)) null else c.getInt(2),
                        content = c.getString(3),
                        verses = markers[id]?.sortedBy { it.start } ?: emptyList(),
                    ),
                )
            }
        }
        return out
    }

    /** The chapter's footnotes, each anchored to a block + caller offset. */
    fun footnotesForChapter(usfm: String, chapter: Int): List<Footnote> {
        val out = ArrayList<Footnote>()
        db.rawQuery(
            """
            SELECT f.id, f.block_id, f.offset, f.verse_key, f.label, f.text
            FROM footnote f JOIN block b ON b.id = f.block_id
            WHERE b.usfm = ? AND b.chapter = ?
            ORDER BY f.block_id, f.offset
            """.trimIndent(),
            arrayOf(usfm, chapter.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Footnote(
                        id = c.getInt(0),
                        blockId = c.getInt(1),
                        offset = c.getInt(2),
                        verseKey = if (c.isNull(3)) null else c.getInt(3),
                        label = if (c.isNull(4)) null else c.getString(4),
                        text = c.getString(5),
                    ),
                )
            }
        }
        return out
    }

    // --- arc 38 / R1: what a reference needs ----------------------------------

    /**
     * The verses whose key falls in `[startKey, endKey]`, in reading order — the `verse` table's
     * clean plain text (no block structure: the passage view flows them as prose). Keys are
     * app-controlled integers, so binding them is a formality kept anyway.
     */
    fun versesForRange(startKey: Int, endKey: Int): List<VerseRow> {
        val out = ArrayList<VerseRow>()
        db.rawQuery(
            "SELECT verse_key, usfm, chapter, verse, text FROM verse " +
                "WHERE verse_key BETWEEN ? AND ? ORDER BY verse_key",
            arrayOf(startKey.toString(), endKey.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(VerseRow(c.getInt(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4)))
            }
        }
        return out
    }

    // --- arc 37 / B8: search ------------------------------------------------

    /**
     * The verses [query]'s words are found in, best first (arc 37 / B8): Biblesprout's
     * `search()`, over FTS4 instead of FTS5 — the platform `android.database.sqlite` this file
     * is opened with carries FTS3/4 on every Android release and cannot be assumed to carry
     * FTS5, and the extension bundles no engine of its own. FTS4 has no `rank`, so the ranking is
     * ours: every matching row's `matchinfo` blob is read (rowid + a few dozen bytes — cheap even
     * for a ubiquitous prefix), scored by [SearchRank.bm25] on the caller's thread, and only the
     * best [SearchQuery.MAX_HITS] rows are then read in full. The count is the true count.
     *
     * The match expression is bound, never interpolated; [SearchQuery] guarantees it is a
     * syntax-safe list of lowercase prefix tokens. **Neither the query nor a hit is logged.**
     */
    fun search(query: String): SearchResults {
        val tokens = SearchQuery.tokens(query)
        val match = SearchQuery.matchExpression(query) ?: return SearchResults(query, tokens, emptyList(), 0)
        val scored = ArrayList<Pair<Int, Double>>()
        db.rawQuery(
            "SELECT rowid, matchinfo(verse_fts, 'pcnalx') FROM verse_fts WHERE verse_fts MATCH ?",
            arrayOf(match),
        ).use { c ->
            while (c.moveToNext()) scored.add(c.getInt(0) to SearchRank.bm25(c.getBlob(1)))
        }
        if (scored.isEmpty()) return SearchResults(query, tokens, emptyList(), 0)
        val keys = SearchRank.top(scored, SearchQuery.MAX_HITS)
        val rows = HashMap<Int, SearchHit>(keys.size * 2)
        // Keys are app-controlled integers straight out of the index, so the IN list is built
        // from them directly; there is nothing user-typed in it.
        db.rawQuery(
            "SELECT verse_key, usfm, chapter, verse, text FROM verse WHERE verse_key IN (${keys.joinToString(",")})",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                rows[c.getInt(0)] = SearchHit(c.getInt(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4))
            }
        }
        return SearchResults(query, tokens, keys.mapNotNull { rows[it] }, scored.size)
    }

    /** The chapter count of [usfm] in the source, or 0 for a book it does not carry. */
    fun chapterCount(usfm: String): Int =
        db.rawQuery("SELECT chapter_count FROM book WHERE usfm = ?", arrayOf(usfm))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Whether exactly this verse exists in the source. */
    fun verseExists(verseKey: Int): Boolean =
        db.rawQuery("SELECT 1 FROM verse WHERE verse_key = ? LIMIT 1", arrayOf(verseKey.toString()))
            .use { it.moveToFirst() }

    companion object {
        /** Opens an installed `.bible` file read-only. Blocking. */
        fun open(path: String): BibleDatabase {
            val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            return BibleDatabase(db, readMetadata(db))
        }

        /** Reads a source database's `metadata` key/value table into a map. */
        private fun readMetadata(db: SQLiteDatabase): Map<String, String> {
            val meta = LinkedHashMap<String, String>()
            db.rawQuery("SELECT key, value FROM metadata", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
            }
            return meta
        }
    }
}
