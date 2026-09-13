package com.symmetricalpalmtree.notesproutsn.ext.bible

/**
 * The two statements the reader sends, as exact text (arc 37 / B0) — pinned here so a change to
 * either is a deliberate edit to this file, never a typo inline at the call site.
 */
object BibleSql {

    const val SELECT_STATE = "SELECT value FROM state WHERE key = ?"
    const val UPSERT_STATE = "INSERT OR REPLACE INTO state(key, value) VALUES (?, ?)"

    /** The one key this table holds today: the last-read position. */
    const val KEY_POSITION = "position"
}
