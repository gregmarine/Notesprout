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
 * needs — the SQL is copied exactly. Search (FTS), cross-references, the word
 * layer, concordance and verse slices are gone: the slim build carries no word
 * layer, and this reader has no search.
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

    /** One verse of the `verse` table's plain text (the passage view's source, arc 38 / R2). */
    data class VerseRow(val verseKey: Int, val usfm: String, val chapter: Int, val verse: Int, val text: String)

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
