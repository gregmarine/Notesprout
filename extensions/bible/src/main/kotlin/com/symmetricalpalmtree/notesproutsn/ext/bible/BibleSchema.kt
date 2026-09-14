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
 * ```
 *
 * `state` is one key/value table — the calendar's `state` / the document editor's `prefs`
 * precedent. `recent` (B7) is one row per chapter the user has picked from the Contents or the
 * Recents, stamped with when; the chapter is the key, so a re-pick re-stamps rather than
 * duplicates. `INSERT OR REPLACE` is safe on both: neither table has children for a row's
 * replacement to cascade away.
 *
 * **A landed step is never edited.** [V1] is exactly what B0 shipped, [V2] is that step plus the
 * recents step, [V3] those two plus the reference-recents step, and the host runs only the steps
 * a store has not seen.
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

    /** The current version. */
    val V3: StoreSchema = StoreSchema(
        version = 3,
        steps = V2.steps + listOf(RECENT_REF_STEP),
    )

    /** What every call declares — the newest version. */
    val CURRENT: StoreSchema get() = V3
}
