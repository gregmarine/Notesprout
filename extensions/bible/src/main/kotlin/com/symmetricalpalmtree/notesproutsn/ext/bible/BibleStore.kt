package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.BibleNote
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads

/** The extension cannot reach its storage (any store exception — the host's rule: treat all as unavailable). */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/**
 * The reader's per-device state over the host's `IExtensionStore` (arc 37 / B0, the tag
 * manager's `TagStore` shape; grown by B7 with the recent chapters and by arc 38 / R2 with the
 * recent references). **Blocking** — every call runs on
 * `Dispatchers.IO` (the screen) or a Binder thread (the service's `begin`/`end`), never Main. The
 * extension writes nothing to disk itself: this store is the host's, lent for the showing.
 *
 * The schema is [BibleSchema.CURRENT] and [load] applies it — the ONE door, because the host's
 * gate refuses `exec` / `query` on a binder that has not declared. Every public method applies it
 * first: that is idempotent, and a matching version costs one `SELECT` host-side.
 *
 * Every exception becomes [StoreUnavailable] via [guard]. **Neither the position nor a recent
 * chapter is ever logged** — they are only references into scripture, but the rule is uniform
 * across the reader: nothing that names where the user has read is written to a log, on either
 * side of the seam.
 */
class BibleStore(private val store: IExtensionStore) {

    /** True once this binder has declared the schema — every later call skips the round trip. */
    @Volatile private var declared = false

    /** Declare the schema. Idempotent, and the only door — nothing may reach the store before it. */
    fun load() = declare()

    /**
     * The declaration, made once per binder: the host's gate refuses `exec` / `query` on a binder
     * that has not declared, and a matching version still costs a Binder round trip plus a
     * host-side `SELECT` — on the path of every page turn. A store that later fails clears the
     * flag, so the next call declares again rather than trusting a binder that may have been
     * replaced.
     */
    private fun declare() {
        if (declared) return
        guard { store.applySchema(BibleSchema.CURRENT) }
        declared = true
    }

    /** The last-read position, or null when nothing has been saved yet. */
    fun readPosition(): String? = guard {
        declare()
        val rows = StoreReads.all(store, Statement(BibleSql.SELECT_STATE, BibleSql.KEY_POSITION))
        rows.rows.firstOrNull()?.text("value")
    }

    /** Save the last-read position. One statement — `INSERT OR REPLACE` is safe because `state`
     *  has no children for a replacement to cascade away. */
    fun writePosition(value: String) = guard {
        declare()
        StoreReads.exec(store, Statement(BibleSql.UPSERT_STATE, BibleSql.KEY_POSITION, value))
        Unit
    }

    /**
     * The recent chapters, newest first, at most [limit]. A row this build cannot read (an
     * unknown book code, a chapter below 1, a cell of the wrong class) is dropped, not thrown —
     * a malformed history row is never a dialog.
     */
    fun readRecents(limit: Int): List<RecentRef> = guard {
        declare()
        val rows = StoreReads.all(store, Statement(BibleSql.SELECT_RECENTS, limit.toLong()))
        rows.rows.mapNotNull { row ->
            runCatching {
                RecentRef.of(row.text("usfm"), row.long("chapter").toInt(), row.long("at"))
            }.getOrNull()
        }
    }

    /**
     * Record a pick, stamped [at], and trim the table to its newest [keep] rows — one
     * two-statement batch, so the trim can never run against a store the upsert did not reach.
     */
    fun writeRecent(ref: ChapterRef, at: Long, keep: Int) = guard {
        declare()
        StoreReads.exec(
            store,
            listOf(
                Statement(BibleSql.UPSERT_RECENT, ref.usfm, ref.chapter.toLong(), at),
                Statement(BibleSql.TRIM_RECENTS, keep.toLong()),
            ),
        )
        Unit
    }

    /**
     * The recent references, newest first, at most [limit] (arc 38 / R2). A row whose wire this
     * build cannot decode — a reference written by a later shape of the grammar, a truncated
     * write — is dropped, not thrown: a malformed history row is never a dialog. Decoded here,
     * on IO, so a panel row names itself without parsing on Main.
     */
    fun readRecentRefs(limit: Int): List<RecentEntry.Reference> = guard {
        declare()
        val rows = StoreReads.all(store, Statement(BibleSql.SELECT_RECENT_REFS, limit.toLong()))
        rows.rows.mapNotNull { row ->
            runCatching {
                val wire = row.text("ref")
                val passages = ReferenceCodec.decode(wire) ?: return@runCatching null
                RecentEntry.Reference(wire, passages, row.long("at"))
            }.getOrNull()
        }
    }

    /**
     * Record a passage opened, stamped [at], and trim the table to its newest [keep] rows — one
     * two-statement batch, [writeRecent]'s shape exactly. The wire is the key, so re-opening the
     * same reference re-stamps the row it already has.
     */
    fun writeRecentRef(wire: String, at: Long, keep: Int) = guard {
        declare()
        StoreReads.exec(
            store,
            listOf(
                Statement(BibleSql.UPSERT_RECENT_REF, wire, at),
                Statement(BibleSql.TRIM_RECENT_REFS, keep.toLong()),
            ),
        )
        Unit
    }

    // ── The notes index (arc 42 "Notes") ────────────────────────────────────────────────────
    // Every write is ONE `exec` batch — the transaction is the lock, and a partial push is
    // impossible. Nothing here is logged: a notebook name is the user's, a wire is where they read.

    /**
     * Replace one page's LINK rows with [notes] — delete the page's LINK half, insert every range
     * of every note, refresh the notebook's name on all its rows, stamp the page's ordinal on the
     * page's remaining (document) rows. Answers how many notes' wires this build could read.
     */
    fun replacePageNotes(
        notebookId: String, notebookName: String, pageId: String, pageNumber: Int, notes: List<BibleNote>,
    ): Int = guard {
        declare()
        val batch = ArrayList<Statement>()
        batch += Statement(BibleSql.DELETE_PAGE_KIND_NOTES, notebookId, pageId, BibleSql.KIND_LINK)
        var readable = 0
        for (note in notes) {
            val inserts = NoteRows.inserts(note, notebookId, notebookName)
            if (inserts.isNotEmpty()) readable++
            batch += inserts
        }
        batch += Statement(BibleSql.RENAME_NOTES, notebookName, notebookId)
        batch += Statement(BibleSql.RENUMBER_PAGE_NOTES, pageNumber.toLong(), notebookId, pageId)
        StoreReads.exec(store, batch)
        readable
    }

    /**
     * Replace a notebook's LINK rows with [notes], refresh its name, renumber every page-bound
     * row from [livePageIds] (1-based) and drop the rows of pages no longer live — the sentinel
     * renumber, so no id list crosses. Answers how many notes' wires this build could read.
     */
    fun replaceNotebookNotes(
        notebookId: String, notebookName: String, livePageIds: List<String>, notes: List<BibleNote>,
    ): Int = guard {
        declare()
        val batch = ArrayList<Statement>()
        batch += Statement(BibleSql.DELETE_NOTEBOOK_KIND_NOTES, notebookId, BibleSql.KIND_LINK)
        var readable = 0
        for (note in notes) {
            val inserts = NoteRows.inserts(note, notebookId, notebookName)
            if (inserts.isNotEmpty()) readable++
            batch += inserts
        }
        batch += Statement(BibleSql.RENAME_NOTES, notebookName, notebookId)
        batch += Statement(BibleSql.UNNUMBER_NOTES, notebookId)
        livePageIds.forEachIndexed { ix, pageId ->
            batch += Statement(BibleSql.RENUMBER_PAGE_NOTES, (ix + 1).toLong(), notebookId, pageId)
        }
        batch += Statement(BibleSql.DELETE_UNNUMBERED_NOTES, notebookId)
        StoreReads.exec(store, batch)
        readable
    }

    /** The notebook was renamed. */
    fun renameNotes(notebookId: String, notebookName: String) = guard {
        declare()
        StoreReads.exec(store, Statement(BibleSql.RENAME_NOTES, notebookName, notebookId))
        Unit
    }

    /** The notebook is gone: every row of it, any kind. */
    fun deleteNotes(notebookId: String) = guard {
        declare()
        StoreReads.exec(store, Statement(BibleSql.DELETE_NOTEBOOK_NOTES, notebookId))
        Unit
    }

    /** Drop every notebook the index knows that is not in [alive]. Answers how many were dropped. */
    fun pruneNotes(alive: Set<String>): Int = guard {
        declare()
        val known = StoreReads.all(store, Statement(BibleSql.SELECT_NOTE_NOTEBOOKS)).rows.map { it.text("notebookId") }
        val dead = known.filter { it !in alive }
        if (dead.isNotEmpty()) {
            StoreReads.exec(store, dead.map { Statement(BibleSql.DELETE_NOTEBOOK_NOTES, it) })
        }
        dead.size
    }

    /**
     * A reference looked up from a document: its DOCUMENT rows, keyed by the (document,
     * reference) pair ([NoteRows.documentNoteId]) so a repeat re-stamps. Answers whether the wire
     * was readable (nothing is written for one that is not).
     */
    fun noteDocument(
        notebookId: String, notebookName: String, pageId: String, pageNumber: Int, wire: String, at: Long,
    ): Boolean = guard {
        declare()
        val inserts = NoteRows.documentInserts(
            NoteRows.documentNoteId(notebookId, pageId, wire), notebookId, pageId, wire, notebookName, pageNumber, at,
        )
        if (inserts.isEmpty()) return@guard false
        StoreReads.exec(store, inserts + Statement(BibleSql.RENAME_NOTES, notebookName, notebookId))
        true
    }

    /**
     * Every row overlapping any of [ranges], at most [limit] per range, in reading order then
     * newest first — one query per range (a passage names several). A row this build cannot read
     * is dropped. Decoded here, on IO, so the panel never parses on Main.
     */
    fun readNotes(ranges: List<VerseRange>, limit: Int): List<NoteRow> = guard {
        declare()
        val out = ArrayList<NoteRow>()
        for (range in ranges) {
            val rows = StoreReads.all(
                store,
                Statement(BibleSql.SELECT_NOTES, range.endKey.toLong(), range.startKey.toLong(), limit.toLong()),
            )
            rows.rows.mapNotNullTo(out) { NoteRows.decode(it) }
        }
        out
    }

    /** Every failure is [StoreUnavailable] — the host's own rule, and the extension's whole
     *  answer to a store it cannot reach. */
    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: StoreUnavailable) {
            throw e
        } catch (e: Exception) {
            declared = false
            throw StoreUnavailable(e)
        }
}
