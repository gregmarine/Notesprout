package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema

/**
 * The document editor's tables in the host's extension store (arc 22 / X4) — declared once, applied
 * by the host. Every statement is validated by `StoreSql.checkDdl` at construction, so a mistake
 * here fails on this side, at class-load, and never at bind.
 *
 * ```sql
 * prefs (key, value)                   -- 'size', 'proofread' (absent = on)
 * word  (word, addedAt)                -- the user dictionary
 * caret (pageKey, offset, updatedAt)   -- where the writer left off, per page
 * ```
 *
 * **Three tables and not one.** Arc 19 kept all of this under four keys of one key/value store, so
 * the shape it took was the shape a blob can hold: the caret map and the dictionary were each a
 * whole line-codec value, read and rewritten entirely to change one entry. On rows each of the
 * three is a different *identity*, and saying so is what removes the read-modify-write:
 *
 *  - a **pref** is a key with one value — the one table the host itself reads (below), so its shape
 *    is pinned in [DocumentContract] rather than spelled here;
 *  - a **word** is its own identity: the word *is* the primary key, which is what makes adding one
 *    an `INSERT OR IGNORE` and removing one a `DELETE`, with no set to decode in between;
 *  - a **caret** is per page, and `updatedAt` is what the LRU orders by — the eviction arc 19 did
 *    in Kotlin over a `LinkedHashMap` is now one `DELETE` beside the write that caused it.
 *
 * `prefs`' table and column names come from [DocumentContract] because this is the **one** extension
 * table the host reads: Document-PDF export takes the editor's saved text size straight from it
 * through the host's own executor (arc 19 / M9, rewired in X1). Both sides naming the same constants
 * is what keeps them from drifting; a test pins that they do.
 *
 * `key` and `offset` are SQLite fallback keywords and legal unquoted — the host's own read of this
 * table (`DocumentPdfRender`) sends `key` / `value` unquoted, and X2's scratch-pad `state` table is
 * spelled the same way; this file follows both.
 */
object EditorSchema {

    /** The current version. A landed step is never edited — a change is a new step. */
    val V1: StoreSchema = StoreSchema(
        version = 1,
        steps = listOf(
            listOf(
                "CREATE TABLE ${DocumentContract.PREFS_TABLE} (" +
                    "${DocumentContract.PREFS_KEY_COLUMN} TEXT PRIMARY KEY, " +
                    "${DocumentContract.PREFS_VALUE_COLUMN} TEXT NOT NULL);",
                "CREATE TABLE word (word TEXT PRIMARY KEY, addedAt INTEGER NOT NULL);",
                "CREATE TABLE caret (pageKey TEXT PRIMARY KEY, offset INTEGER NOT NULL, updatedAt INTEGER NOT NULL);",
            ),
        ),
    )
}
