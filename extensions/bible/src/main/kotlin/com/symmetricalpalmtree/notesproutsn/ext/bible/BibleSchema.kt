package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema

/**
 * The Bible reader's one table in the host's extension store (arc 37 / B0) — declared once,
 * applied by the host. Every statement is validated by `StoreSql.checkDdl` at construction, so a
 * mistake here fails on this side, at class-load, and never at bind.
 *
 * ```sql
 * state (key TEXT PRIMARY KEY, value TEXT NOT NULL)
 * ```
 *
 * One key/value table — the calendar's `state` / the document editor's `prefs` precedent.
 * `INSERT OR REPLACE` is safe because the table has no children: there is nothing for a row's
 * replacement to cascade away.
 */
object BibleSchema {

    /** The current version. A landed step is never edited — a change is a new step. */
    val V1: StoreSchema = StoreSchema(
        version = 1,
        steps = listOf(
            listOf(
                "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL);",
            ),
        ),
    )
}
