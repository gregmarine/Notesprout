package com.symmetricalpalmtree.notesproutsn.ext.drive

import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema

/**
 * The Drive provider's one table in the host's extension store (arc 25 / V1) — declared once,
 * applied by the host. Every statement is validated by `StoreSql.checkDdl` at construction, so a
 * mistake here fails on this side, at class-load, and never at bind.
 *
 * ```sql
 * account (key, value)
 * ```
 *
 * A handful of rows hold the whole account: the refresh token, the account label the person is
 * recognized by, and cached root-folder ids — the same shape as the document editor's `prefs`
 * table, which is the precedent for reaching for one key/value table instead of a wider one. The
 * token is host-encrypted at rest because the store itself is (`Garden/<pkg>.db`, under the global
 * key); this extension writes nothing to disk itself, ever — every read and write of it goes
 * through this store, lent for the call.
 */
object DriveSchema {

    /** The current version. A landed step is never edited — a change is a new step. */
    val V1: StoreSchema = StoreSchema(
        version = 1,
        steps = listOf(
            listOf(
                "CREATE TABLE account (key TEXT PRIMARY KEY, value TEXT NOT NULL);",
            ),
        ),
    )
}
