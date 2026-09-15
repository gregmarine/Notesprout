package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.BibleNote
import com.symmetricalpalmtree.notesproutsn.extension.Row
import com.symmetricalpalmtree.notesproutsn.extension.Statement

/**
 * One row of the notes index (arc 42 "Notes"), as read back — `note_ref`'s columns minus the two
 * keys the query already matched on. [kind] is `BibleSql.KIND_LINK` / `KIND_DOCUMENT`; [pageId]
 * is empty for a document row on the notebook itself. Never logged: the wire names where the
 * user has read, and the name is the user's own.
 */
data class NoteRow(
    val noteId: String,
    val rangeIx: Int,
    val notebookId: String,
    val pageId: String,
    val kind: Int,
    val wire: String,
    val notebookName: String,
    val pageNumber: Int,
    val at: Long,
)

/**
 * The notes index's row shapes, pure (arc 42 "Notes") — **the one place a pushed wire is read**:
 * the host treats a wire as opaque and this decodes it (`ReferenceCodec`) into one `INSERT_NOTE`
 * statement per verse range, `rangeIx` running from 0 across every passage of the reference. A
 * whole chapter already carries the codec's `c:0` / `c:999` sentinels (`VerseKey.MAX_VERSE`), so
 * the overlap read finds it against any verse of the chapter.
 *
 * A wire this build cannot read yields **no** statements — counted by the caller, never thrown:
 * one foreign row must not fail a whole page's push.
 */
object NoteRows {

    /** The statements that insert every range of [note] as LINK rows of one page. */
    fun inserts(note: BibleNote, notebookId: String, notebookName: String): List<Statement> =
        inserts(
            noteId = note.noteId, notebookId = notebookId, pageId = note.pageId, kind = BibleSql.KIND_LINK,
            wire = note.wire, notebookName = notebookName, pageNumber = note.pageNumber, at = note.at,
        )

    /** The statements that insert every range of a DOCUMENT row. [noteId] is [documentNoteId]'s. */
    fun documentInserts(
        noteId: String, notebookId: String, pageId: String, wire: String,
        notebookName: String, pageNumber: Int, at: Long,
    ): List<Statement> =
        inserts(noteId, notebookId, pageId, BibleSql.KIND_DOCUMENT, wire, notebookName, pageNumber, at)

    /**
     * A document row's id: the host minted none, so the (document, reference) pair is the key —
     * re-looking up the same reference on the same document re-stamps the row it already has,
     * a different reference on the same document is another row. A page-less document is keyed
     * by its notebook.
     */
    fun documentNoteId(notebookId: String, pageId: String, wire: String): String =
        "doc:" + pageId.ifEmpty { notebookId } + ":" + wire

    private fun inserts(
        noteId: String, notebookId: String, pageId: String, kind: Long, wire: String,
        notebookName: String, pageNumber: Int, at: Long,
    ): List<Statement> {
        val passages = ReferenceCodec.decode(wire) ?: return emptyList()
        val out = ArrayList<Statement>()
        var ix = 0L
        for (passage in passages) {
            for (range in passage.ranges) {
                out += Statement(
                    BibleSql.INSERT_NOTE,
                    noteId, ix++, notebookId, pageId, kind, wire,
                    range.startKey.toLong(), range.endKey.toLong(),
                    notebookName, pageNumber.toLong(), at,
                )
            }
        }
        return out
    }

    /** A `SELECT_NOTES` row, or null for one this build cannot read (a cell of the wrong class,
     *  an unreadable wire) — dropped by the caller, never a dialog. */
    fun decode(row: Row): NoteRow? = runCatching {
        val wire = row.text("wire")
        if (ReferenceCodec.decode(wire) == null) return null
        NoteRow(
            noteId = row.text("noteId"),
            rangeIx = row.long("rangeIx").toInt(),
            notebookId = row.text("notebookId"),
            pageId = row.text("pageId"),
            kind = row.long("kind").toInt(),
            wire = wire,
            notebookName = row.text("notebookName"),
            pageNumber = row.long("pageNumber").toInt(),
            at = row.long("at"),
        )
    }.getOrNull()
}
