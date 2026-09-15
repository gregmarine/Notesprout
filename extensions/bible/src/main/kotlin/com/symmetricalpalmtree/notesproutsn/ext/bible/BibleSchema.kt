package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema

/**
 * The Bible reader's tables in the host's extension store (arc 37 / B0, grown by B7 and by
 * arc 38 / R2) — declared
 * once, applied by the host. Every statement is validated by `StoreSql.checkDdl` at construction,
 * so a mistake here fails on this side, at class-load, and never at bind.
 *
 * ```sql
 * state  (key TEXT PRIMARY KEY, value TEXT NOT NULL)                               -- v1
 * recent (usfm TEXT NOT NULL, chapter INTEGER NOT NULL, at INTEGER NOT NULL,
 *         PRIMARY KEY (usfm, chapter))                                             -- v2
 * recent_ref (ref TEXT PRIMARY KEY, at INTEGER NOT NULL)                           -- v3
 * note_ref (noteId, rangeIx, notebookId, pageId, kind, wire, startKey, endKey,
 *           notebookName, pageNumber, at, PRIMARY KEY (noteId, rangeIx))            -- v4
 * ```
 *
 * `state` is one key/value table — the calendar's `state` / the document editor's `prefs`
 * precedent. `recent` (B7) is one row per chapter the user has picked from the Contents or the
 * Recents, stamped with when; the chapter is the key, so a re-pick re-stamps rather than
 * duplicates. `INSERT OR REPLACE` is safe on both: neither table has children for a row's
 * replacement to cascade away.
 *
 * `note_ref` (arc 42 "Notes") is the notes index — one row per verse range of every Bible
 * reference the host has pushed: a link object on a notebook page (`kind` 0, `noteId` = the link
 * row's id) or a reference looked up from a document (`kind` 1, `noteId` minted here). The
 * reader's read is an overlap on `(startKey, endKey)`; the host's writes are by
 * `(notebookId, pageId)`. `notebookName` and `pageNumber` ride the row because the extension
 * cannot read the host's index. `INSERT OR REPLACE` is safe: no children.
 *
 * **A landed step is never edited.** [V1] is exactly what B0 shipped, [V2] is that step plus the
 * recents step, [V3] those two plus the reference-recents step, [V4] those three plus the notes
 * step, and the host runs only the steps a store has not seen.
 */
object BibleSchema {

    /** B0's step — landed, and therefore never edited; [V2] builds on it. */
    val V1: StoreSchema = StoreSchema(
        version = 1,
        steps = listOf(
            listOf(
                "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL);",
            ),
        ),
    )

    /** B7's step: the recent chapters. */
    val RECENT_STEP: List<String> = listOf(
        "CREATE TABLE recent (usfm TEXT NOT NULL, chapter INTEGER NOT NULL, at INTEGER NOT NULL, " +
            "PRIMARY KEY (usfm, chapter));",
    )

    /** B7's shape — landed, and therefore never edited; [V3] builds on it. */
    val V2: StoreSchema = StoreSchema(
        version = 2,
        steps = V1.steps + listOf(RECENT_STEP),
    )

    /** Arc 38 / R2's step: the recent references, the passage view's half of the history. */
    val RECENT_REF_STEP: List<String> = listOf(
        "CREATE TABLE recent_ref (ref TEXT PRIMARY KEY, at INTEGER NOT NULL);",
    )

    /** Arc 38 / R2's shape — landed, and therefore never edited; [V4] builds on it. */
    val V3: StoreSchema = StoreSchema(
        version = 3,
        steps = V2.steps + listOf(RECENT_REF_STEP),
    )

    /** Arc 42 "Notes"' step: the notes index and its two reads' indexes. */
    val NOTE_STEP: List<String> = listOf(
        "CREATE TABLE note_ref (noteId TEXT NOT NULL, rangeIx INTEGER NOT NULL, " +
            "notebookId TEXT NOT NULL, pageId TEXT NOT NULL, kind INTEGER NOT NULL, " +
            "wire TEXT NOT NULL, startKey INTEGER NOT NULL, endKey INTEGER NOT NULL, " +
            "notebookName TEXT NOT NULL, pageNumber INTEGER NOT NULL, at INTEGER NOT NULL, " +
            "PRIMARY KEY (noteId, rangeIx));",
        "CREATE INDEX note_ref_span ON note_ref (startKey, endKey);",
        "CREATE INDEX note_ref_target ON note_ref (notebookId, pageId);",
    )

    /** The current version. */
    val V4: StoreSchema = StoreSchema(
        version = 4,
        steps = V3.steps + listOf(NOTE_STEP),
    )

    /** What every call declares — the newest version. */
    val CURRENT: StoreSchema get() = V4
}
