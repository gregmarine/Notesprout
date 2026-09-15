package com.symmetricalpalmtree.notesproutsn.ext.bible

/**
 * The statements the reader sends, as exact text (arc 37 / B0, grown by B7 and by arc 38 / R2)
 * — pinned here so a
 * change to any is a deliberate edit to this file, never a typo inline at the call site.
 */
object BibleSql {

    const val SELECT_STATE = "SELECT value FROM state WHERE key = ?"
    const val UPSERT_STATE = "INSERT OR REPLACE INTO state(key, value) VALUES (?, ?)"

    /** The one key `state` holds: the last-read position. */
    const val KEY_POSITION = "position"

    // --- recents (B7) ---------------------------------------------------------

    /** Newest first, at most `?` rows — the panel's whole read. */
    const val SELECT_RECENTS = "SELECT usfm, chapter, at FROM recent ORDER BY at DESC LIMIT ?"

    /** A pick: the chapter is the key, so a re-pick re-stamps the row it already has. */
    const val UPSERT_RECENT = "INSERT OR REPLACE INTO recent(usfm, chapter, at) VALUES (?, ?, ?)"

    /** Keep the newest `?` rows and drop the rest — sent in the same batch as [UPSERT_RECENT]. */
    const val TRIM_RECENTS =
        "DELETE FROM recent WHERE rowid NOT IN (SELECT rowid FROM recent ORDER BY at DESC LIMIT ?)"

    // --- recent references (arc 38 / R2) --------------------------------------

    /** Newest first, at most `?` rows — the other half of what the panel merges. */
    const val SELECT_RECENT_REFS = "SELECT ref, at FROM recent_ref ORDER BY at DESC LIMIT ?"

    /** A passage opened: the wire is the key, so re-opening the same reference re-stamps it. */
    const val UPSERT_RECENT_REF = "INSERT OR REPLACE INTO recent_ref(ref, at) VALUES (?, ?)"

    /** [TRIM_RECENTS]' shape in the reference table — sent in the same batch as the upsert. */
    const val TRIM_RECENT_REFS =
        "DELETE FROM recent_ref WHERE rowid NOT IN (SELECT rowid FROM recent_ref ORDER BY at DESC LIMIT ?)"

    // --- the notes index (arc 42 "Notes") --------------------------------------

    /** The `kind` column: a Bible link object on a page. `ExtensionContract.BIBLE_NOTE_KIND_LINK`. */
    const val KIND_LINK = 0L

    /** The `kind` column: a reference looked up from a document. `ExtensionContract.BIBLE_NOTE_KIND_DOCUMENT`. */
    const val KIND_DOCUMENT = 1L

    /** Every row overlapping `[?2, ?1]` — the classic two-comparison overlap, `start <= scopeEnd
     *  AND end >= scopeStart` — in reading order then newest first, at most `?3`. */
    const val SELECT_NOTES =
        "SELECT noteId, rangeIx, notebookId, pageId, kind, wire, notebookName, pageNumber, at " +
            "FROM note_ref WHERE startKey <= ? AND endKey >= ? ORDER BY startKey, at DESC LIMIT ?"

    /** One verse range of one note; the (note, range) pair is the key so a re-push re-stamps. */
    const val INSERT_NOTE =
        "INSERT OR REPLACE INTO note_ref(noteId, rangeIx, notebookId, pageId, kind, wire, " +
            "startKey, endKey, notebookName, pageNumber, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    /** A page's rows of one kind — the LINK half of a page, replaced whole on every push. */
    const val DELETE_PAGE_KIND_NOTES = "DELETE FROM note_ref WHERE notebookId = ? AND pageId = ? AND kind = ?"

    /** A notebook's rows of one kind — the LINK half of a notebook, replaced whole on a rebuild. */
    const val DELETE_NOTEBOOK_KIND_NOTES = "DELETE FROM note_ref WHERE notebookId = ? AND kind = ?"

    /** Every row of a notebook, any kind — the notebook is gone. */
    const val DELETE_NOTEBOOK_NOTES = "DELETE FROM note_ref WHERE notebookId = ?"

    /** The name rides every row; a rename (or any push) refreshes them all. */
    const val RENAME_NOTES = "UPDATE note_ref SET notebookName = ? WHERE notebookId = ?"

    /** The page's ordinal on every row of one page. */
    const val RENUMBER_PAGE_NOTES = "UPDATE note_ref SET pageNumber = ? WHERE notebookId = ? AND pageId = ?"

    /** The renumber's sentinel: every page-bound row of a notebook is unnumbered first, each live
     *  page then numbered, and what stayed 0 is a page no longer live — so no `IN (…)` list of
     *  page ids ever crosses. A document row on the notebook itself (an empty page id) is never
     *  page-bound and never touched. */
    const val UNNUMBER_NOTES = "UPDATE note_ref SET pageNumber = 0 WHERE notebookId = ? AND LENGTH(pageId) > 0"

    /** The rows the renumber left at 0 — pages that are gone. */
    const val DELETE_UNNUMBERED_NOTES =
        "DELETE FROM note_ref WHERE notebookId = ? AND LENGTH(pageId) > 0 AND pageNumber = 0"

    /** Every notebook the index knows — the prune's read; the diff is done in Kotlin. */
    const val SELECT_NOTE_NOTEBOOKS = "SELECT DISTINCT notebookId FROM note_ref"
}
