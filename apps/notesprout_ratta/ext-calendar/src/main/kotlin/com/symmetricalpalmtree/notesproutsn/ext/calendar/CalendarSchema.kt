package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema

/**
 * The calendar's tables in the host's extension store (arc 23 / Y1) — declared once, applied by the
 * host. Every statement is validated by `StoreSql.checkDdl` at construction, so a mistake here
 * fails on this side, at class-load, and never at bind.
 *
 * ```sql
 * period (id, kind, date)                                   -- UNIQUE(kind, date); date = ISO day
 * page   (id, periodId → period.id ON DELETE CASCADE, half, width, height, createdAt, updatedAt)
 * stroke (id, pageId → page.id ON DELETE CASCADE, "order", color, width, style, blob)
 * state  (key, value)                                       -- lastView · lastDate · lastHalf
 * ```
 *
 * A `period` is a month (dated by its first day), a week (by its Sunday) or a day; the kind column
 * says which, so no key prefix does. A month or a week owns one `page` (`half` 0); a day owns two
 * (0 = AM, 1 = PM). **Rows are minted on the first stroke, never on open** — browsing empty months
 * writes nothing. `stroke` is the pad's row exactly (`StrokeCodec` format B in `blob`, `"order"`
 * the writing order within the page); `page.width/height` is the page's minted size, this device's
 * screen, so a template rendered at the page's own size keeps grid and ink registered on any
 * screen the store is later carried to.
 *
 * Foreign keys are ON for the store connection, so `DELETE FROM period` takes its pages and their
 * strokes with it — which is why neither `period` nor `page` is ever written with
 * `INSERT OR REPLACE` (REPLACE deletes the conflicting row first, and that delete cascades).
 * Nothing deletes a `period` in this arc at all.
 */
object CalendarSchema {

    /** The current version. A landed step is never edited — a change is a new step. */
    val V1: StoreSchema = StoreSchema(
        version = 1,
        steps = listOf(
            listOf(
                """CREATE TABLE period (
                       id TEXT PRIMARY KEY,
                       kind INTEGER NOT NULL,
                       date TEXT NOT NULL,
                       UNIQUE(kind, date));""",
                """CREATE TABLE page (
                       id TEXT PRIMARY KEY,
                       periodId TEXT NOT NULL REFERENCES period(id) ON DELETE CASCADE,
                       half INTEGER NOT NULL,
                       width REAL NOT NULL,
                       height REAL NOT NULL,
                       createdAt INTEGER NOT NULL,
                       updatedAt INTEGER NOT NULL,
                       UNIQUE(periodId, half));""",
                """CREATE TABLE stroke (
                       id TEXT PRIMARY KEY,
                       pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
                       "order" INTEGER NOT NULL,
                       color INTEGER NOT NULL,
                       width REAL NOT NULL,
                       style TEXT NOT NULL,
                       blob BLOB NOT NULL);""",
                """CREATE INDEX stroke_page_order ON stroke(pageId, "order");""",
                "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL);",
            ),
        ),
    )
}
