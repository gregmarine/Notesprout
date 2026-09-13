package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema

/**
 * The Bible reader's tables in the host's extension store (arc 37 / B0, grown by B7) — declared
 * once, applied by the host. Every statement is validated by `StoreSql.checkDdl` at construction,
 * so a mistake here fails on this side, at class-load, and never at bind.
 *
 * ```sql
 * state  (key TEXT PRIMARY KEY, value TEXT NOT NULL)                               -- v1
 * recent (usfm TEXT NOT NULL, chapter INTEGER NOT NULL, at INTEGER NOT NULL,
 *         PRIMARY KEY (usfm, chapter))                                             -- v2
 * ```
 *
 * `state` is one key/value table — the calendar's `state` / the document editor's `prefs`
 * precedent. `recent` (B7) is one row per chapter the user has picked from the Contents or the
 * Recents, stamped with when; the chapter is the key, so a re-pick re-stamps rather than
 * duplicates. `INSERT OR REPLACE` is safe on both: neither table has children for a row's
 * replacement to cascade away.
 *
 * **A landed step is never edited.** [V1] is exactly what B0 shipped; [V2] is that step plus the
 * recents step, and the host runs only the steps a store has not seen.
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

    /** The current version. */
    val V2: StoreSchema = StoreSchema(
        version = 2,
        steps = V1.steps + listOf(RECENT_STEP),
    )

    /** What every call declares — the newest version. */
    val CURRENT: StoreSchema get() = V2
}
