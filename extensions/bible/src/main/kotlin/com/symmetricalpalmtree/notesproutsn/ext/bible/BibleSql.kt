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
}
