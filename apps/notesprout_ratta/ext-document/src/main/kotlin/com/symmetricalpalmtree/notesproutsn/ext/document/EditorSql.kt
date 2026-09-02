package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.Statement

/**
 * Every statement the document editor sends, as a pure builder (arc 22 / X4) — SQL text and bound
 * arguments and nothing else, so the shapes are JVM-testable without a store.
 *
 * `now` is a parameter everywhere a timestamp is written rather than read here, so a test can pin
 * it. The `prefs` statements are built from [DocumentContract]'s pinned names for the same reason
 * [EditorSchema] builds its DDL from them: that table is the one the host reads for itself, and two
 * spellings of it would be two tables.
 *
 * **`INSERT OR REPLACE` is safe in this schema**, and it needs saying because X2 forbade it: the
 * scratch pad may never `REPLACE INTO page`, since REPLACE deletes the conflicting row first and
 * that delete cascades away the page's strokes. None of these three tables has children — no
 * `REFERENCES`, no cascade — so a replaced row takes nothing with it, and an upsert is one statement
 * instead of an update-then-insert dance.
 */
object EditorSql {

    /** The `prefs` key of the proofread toggle: `"1"` / `"0"`, and **absent means on**. (The text
     *  size's key is [DocumentContract.PREF_TEXT_SIZE] — the host reads that one too.) */
    const val PREF_PROOFREAD: String = "proofread"

    /**
     * How many pages' carets to keep — was `CaretMemory.LIMIT`. The eviction is now a `DELETE`
     * beside the write that caused it, and the cap is **bound** rather than written into the text:
     * a cap in the SQL is a cap a test cannot vary.
     */
    const val CARET_LIMIT: Int = 100

    // ── prefs ────────────────────────────────────────────────────────────────

    fun selectPref(key: String): Statement =
        Statement(
            "SELECT ${DocumentContract.PREFS_VALUE_COLUMN} FROM ${DocumentContract.PREFS_TABLE} " +
                "WHERE ${DocumentContract.PREFS_KEY_COLUMN} = ?",
            key,
        )

    fun upsertPref(key: String, value: String): Statement =
        Statement(
            "INSERT OR REPLACE INTO ${DocumentContract.PREFS_TABLE} " +
                "(${DocumentContract.PREFS_KEY_COLUMN}, ${DocumentContract.PREFS_VALUE_COLUMN}) VALUES (?, ?)",
            key, value,
        )

    // ── word (the user dictionary) ───────────────────────────────────────────

    /** Every vouched word, oldest first — the manage list's order. `word` is the tie-break so the
     *  order is **total**: two words added in the same millisecond still come back the same way
     *  twice, which a list the user taps rows in has to be able to promise. */
    fun selectWords(): Statement =
        Statement("SELECT word FROM word ORDER BY addedAt, word")

    /** Vouch for [word] (already normalized). `OR IGNORE` rather than `OR REPLACE`: re-adding a
     *  word does **not** move it, because its `addedAt` is what the manage list orders by and the
     *  writer added it when they added it (og's rule). */
    fun insertWord(word: String, now: Long): Statement =
        Statement("INSERT OR IGNORE INTO word (word, addedAt) VALUES (?, ?)", word, now)

    /** Take [word] back out — a hard drop: a removed word must stop vouching for itself
     *  immediately, and there is nothing a tombstone could preserve (og's rule). */
    fun deleteWord(word: String): Statement =
        Statement("DELETE FROM word WHERE word = ?", word)

    // ── caret ────────────────────────────────────────────────────────────────

    fun selectCaret(pageKey: String): Statement =
        Statement("SELECT offset FROM caret WHERE pageKey = ?", pageKey)

    fun upsertCaret(pageKey: String, offset: Int, now: Long): Statement =
        Statement(
            "INSERT OR REPLACE INTO caret (pageKey, offset, updatedAt) VALUES (?, ?, ?)",
            pageKey, offset.toLong(), now,
        )

    /** Evict everything past [CARET_LIMIT] most-recently-written pages. Sent in the **same batch**
     *  as the upsert above, so the row just written is inside the window it is measured against. */
    fun trimCarets(): Statement =
        Statement(
            "DELETE FROM caret WHERE pageKey NOT IN " +
                "(SELECT pageKey FROM caret ORDER BY updatedAt DESC LIMIT ?)",
            CARET_LIMIT.toLong(),
        )
}
