package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema
import com.symmetricalpalmtree.notesproutsn.ink.InkSql

/**
 * The scratch pad's tables in the host's extension store (arc 22 / X2) — declared once, applied by
 * the host. Every statement is validated by `StoreSql.checkDdl` at construction, so a mistake here
 * fails on this side, at class-load, and never at bind.
 *
 * ```sql
 * page   (id, position, width, height, createdAt, updatedAt)   -- 0 × 0 = size not learned yet
 * stroke (id, pageId → page.id ON DELETE CASCADE, "order", color, width, style, blob)
 * state  (key, value)                                          -- 'current' → the current page id
 * ```
 *
 * **The `stroke` half is `:ext-ink`'s** ([InkSql.CREATE_STROKE_TABLE] / [InkSql.CREATE_STROKE_INDEX],
 * arc 23) — one declaration for both consumers, byte-identical to what this object used to spell
 * out and pinned by `ScratchSqlTest`; only the pad's own tables are written here.
 *
 * `stroke.blob` is `StrokeCodec` format B (x / y / pressure / tilt) — the `.soil`'s own stroke
 * encoding, exactly the geometry bytes arc 11's page blob nested. `stroke."order"` is the writing
 * order within its page and is what makes the page's ink stable across an undo/redo cycle.
 *
 * Foreign keys are ON for the store connection, so `DELETE FROM page` takes that page's strokes
 * with it — which is why a page row is never written with `INSERT OR REPLACE` (REPLACE deletes the
 * conflicting row first, and that delete cascades).
 */
object ScratchSchema {

    /** The current version. A landed step is never edited — a change is a new step. */
    val V1: StoreSchema = StoreSchema(
        version = 1,
        steps = listOf(
            listOf(
                """CREATE TABLE page (
                       id TEXT PRIMARY KEY,
                       position INTEGER NOT NULL,
                       width REAL NOT NULL,
                       height REAL NOT NULL,
                       createdAt INTEGER NOT NULL,
                       updatedAt INTEGER NOT NULL);""",
                "CREATE INDEX page_position ON page(position);",
                InkSql.CREATE_STROKE_TABLE,
                InkSql.CREATE_STROKE_INDEX,
                "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL);",
            ),
        ),
    )
}
