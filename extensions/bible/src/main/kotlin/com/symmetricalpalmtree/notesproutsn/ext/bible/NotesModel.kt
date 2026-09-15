package com.symmetricalpalmtree.notesproutsn.ext.bible

import kotlin.math.roundToInt

/**
 * What the Notes panel shows, as arithmetic (arc 42 "Notes" / N1 — pure Kotlin, JVM-tested): the
 * scope the reader is in, the overlap test that answers it, and the rows of the index grouped
 * into the things the user actually wrote on — a notebook page, or a document.
 *
 * **Scope is the chapter in view, or the passage's own ranges** (decision 5). A chapter is one
 * range across its whole verse band, which is why a whole-chapter row (`c:0`–`c:999`) and a
 * single-verse row are found by the same query; a passage names several, so the panel asks
 * several times and de-duplicates here.
 *
 * **A page is one row, whatever it holds** (the judgment call recorded in `NOTES_PLAN.md`): a
 * page carrying three references reads as one entry naming all three, because what the user is
 * looking for is the page, not the link object. The rows arrive in reading order
 * (`BibleSql.SELECT_NOTES`) and the groups keep it — where the note *points* is the only order
 * that means anything while reading a chapter.
 *
 * Never logged: a wire names where the user has read and a notebook name is the user's own word.
 */
object NotesModel {

    /** Most rows the panel reads per range — a scope with more than this is not a list any more. */
    const val NOTES_LIMIT = 500

    /**
     * The panel's share of the window width — the Contents' 60 %, wider than the Recents' 50 %:
     * a row is a notebook name, a page and a list of references, not a name and a time. The
     * 480 dp full-screen breakpoint is [ContentsLayout]'s, as every panel's is.
     */
    const val SIDEBAR_WIDTH_FRACTION = 0.60f

    fun sidebarWidthPx(windowWidthPx: Int): Int = (windowWidthPx * SIDEBAR_WIDTH_FRACTION).roundToInt()

    /**
     * The verse ranges the reader is standing in. **Passage mode wins**: when [passages] is
     * non-null they are the scope, every range of every passage; otherwise the whole of
     * [chapter]'s verse band as one range. Nothing open — or a book code this build does not
     * know — is an empty scope, which the panel's door treats as a silent no-op.
     */
    fun scope(chapter: ChapterRef?, passages: List<Passage>?): List<VerseRange> {
        if (passages != null) return passages.flatMap { it.ranges }
        val ref = chapter ?: return emptyList()
        val book = Canon.tryUsfm(ref.usfm) ?: return emptyList()
        val (start, end) = VerseKey.chapterBounds(book.ordinal, ref.chapter)
        return listOf(VerseRange(start, end))
    }

    /**
     * `SELECT_NOTES`' `startKey <= scopeEnd AND endKey >= scopeStart` in Kotlin — the SQL's twin,
     * so the overlap the store relies on is pinned by a test rather than by a reading of the
     * string. A [NoteRow] carries no keys (the query already matched on them), so the range is
     * derived from the wire it does carry: a row this build cannot read, or one whose `rangeIx`
     * names a range the wire no longer has, overlaps nothing.
     */
    fun overlaps(row: NoteRow, range: VerseRange): Boolean {
        val own = rangeOf(row) ?: return false
        return own.startKey <= range.endKey && own.endKey >= range.startKey
    }

    /**
     * One entry of the panel: everything one target — a notebook page, or a document — has to
     * say about the scope. [labels] are its references in reading order, [newestAt] the most
     * recent of them, and [firstStartKey] where the earliest of them points (the list's order).
     */
    data class NoteGroup(
        val notebookId: String,
        val pageId: String,
        val kind: Int,
        val notebookName: String,
        val pageNumber: Int,
        val labels: List<String>,
        val newestAt: Long,
        val firstStartKey: Int,
    )

    /**
     * The rows as the panel shows them. Three collapses, in order:
     *
     * 1. **`(noteId, rangeIx)`** — passage mode runs one query per range and a note spanning two
     *    of them comes back from both.
     * 2. **`(notebookId, pageId, kind)`** — the group: what the user wrote on.
     * 3. **`noteId` inside a group** — a multi-range link names its reference once, not once per
     *    range (`John 3:14–18` is one label whatever its wire holds).
     *
     * A row whose range cannot be derived is dropped whole: it could not have matched the query
     * either, and one foreign row must not cost the page it sits on its place in the list.
     * Groups sort by where they point, then newest first — reading order is the subject here.
     */
    fun group(rows: List<NoteRow>): List<NoteGroup> {
        val seen = HashSet<Pair<String, Int>>(rows.size)
        val byTarget = LinkedHashMap<Triple<String, String, Int>, MutableList<Keyed>>()
        for (row in rows) {
            if (!seen.add(row.noteId to row.rangeIx)) continue
            val start = rangeOf(row)?.startKey ?: continue
            byTarget.getOrPut(Triple(row.notebookId, row.pageId, row.kind)) { ArrayList() }
                .add(Keyed(row, start))
        }
        val groups = ArrayList<NoteGroup>(byTarget.size)
        for ((target, keyed) in byTarget) {
            val notes = LinkedHashMap<String, Note>()
            for ((row, start) in keyed) {
                val note = notes[row.noteId]
                if (note == null) {
                    notes[row.noteId] = Note(label(row) ?: continue, start)
                } else if (start < note.firstStartKey) {
                    note.firstStartKey = start
                }
            }
            if (notes.isEmpty()) continue
            // The name and the page number come from the FRESHEST row: a rename or a page
            // renumber re-stamps the rows it touched, and the newest push is the true one.
            val newest = keyed.maxByOrNull { it.row.at }?.row ?: continue
            groups += NoteGroup(
                notebookId = target.first,
                pageId = target.second,
                kind = target.third,
                notebookName = newest.notebookName,
                pageNumber = newest.pageNumber,
                labels = notes.values.sortedBy { it.firstStartKey }.map { it.label }.distinct(),
                newestAt = keyed.maxOf { it.row.at },
                firstStartKey = keyed.minOf { it.startKey },
            )
        }
        groups.sortWith(compareBy<NoteGroup> { it.firstStartKey }.thenByDescending { it.newestAt })
        return groups
    }

    /**
     * A row's first line: the notebook, then where in it — `"Study · Page 4"`, or
     * `"Study · Document"` for a row the notebook itself carries (a text document, or the
     * notebook document). The words are the caller's, so this stays free of Android.
     */
    fun title(group: NoteGroup, pageWord: String, documentWord: String): String =
        if (group.pageId.isEmpty()) "${group.notebookName} · $documentWord"
        else "${group.notebookName} · $pageWord ${group.pageNumber}"

    /**
     * A row's second line: the references it holds, then when the newest of them was written.
     * A **document** row on a page says so first — the page and its document are two places,
     * and only the second line can tell them apart (the first already named the page).
     */
    fun detail(group: NoteGroup, dateText: String, documentWord: String): String {
        val body = group.labels.joinToString("; ") + " · " + dateText
        return if (group.kind == BibleSql.KIND_DOCUMENT.toInt() && group.pageId.isNotEmpty()) {
            "$documentWord · $body"
        } else {
            body
        }
    }

    /** The range [NoteRow.rangeIx] names, counted across the wire's passages in order. */
    private fun rangeOf(row: NoteRow): VerseRange? {
        if (row.rangeIx < 0) return null
        val passages = ReferenceCodec.decode(row.wire) ?: return null
        var ix = row.rangeIx
        for (passage in passages) {
            if (ix < passage.ranges.size) return passage.ranges[ix]
            ix -= passage.ranges.size
        }
        return null
    }

    /** The whole reference a row belongs to, canonically — never one range of it. */
    private fun label(row: NoteRow): String? =
        ReferenceCodec.decode(row.wire)?.let { ReferenceCodec.label(it) }

    private data class Keyed(val row: NoteRow, val startKey: Int)

    private class Note(val label: String, var firstStartKey: Int)
}
