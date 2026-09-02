package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.ink.InkDocument
import com.symmetricalpalmtree.notesproutsn.ink.InkSql

/**
 * Every statement the calendar sends, as a pure builder (arc 23 / Y1) — SQL text and bound
 * arguments and nothing else, so the shapes are JVM-testable without a store, pinned by
 * `CalendarSqlTest`.
 *
 * **Every write is idempotent**, because a batch that failed part-way is retried by whatever caller
 * owns it and the retry has to converge:
 *
 * - `period` and `page` rows are created with `INSERT OR IGNORE` — **never `INSERT OR REPLACE`**:
 *   REPLACE deletes the conflicting row first, and with `foreign_keys` ON that delete CASCADES (a
 *   period's pages and their strokes, a page's strokes — X2's trap). The page's `periodId` is
 *   resolved **inside the statement** from `(kind, date)`, so a period that already exists (the
 *   day's other half minted it) is joined, not duplicated, whatever id the caller minted for it;
 * - a stroke row is written with `INSERT OR REPLACE` (a stroke has no children, so REPLACE is
 *   safe) and removed with `DELETE … WHERE id = ?` (a row that is not there is not an error);
 * - `state` rows are `INSERT OR REPLACE` — `state` has no children either.
 *
 * **The `stroke` half is `:ext-ink`'s** ([InkSql], arc 23): the two write statements arrive as
 * [InkDocument.StrokeSql] by delegation and the three reads forward, so the calendar's table and
 * the pad's are declared and addressed once rather than twice. The text is unchanged — this file's
 * own test pins every string through the host's validator.
 *
 * `key` / `value` / `order`: `"order"` is quoted (a real keyword); `key` and `value` are SQLite
 * fallback keywords and pass **unquoted** on the JVM and on the Nomad alike (arc 22 / X4).
 * `now` is passed in rather than read here so a test can pin it.
 */
object CalendarSql : InkDocument.StrokeSql by InkSql {

    // ── period / page — minted on the first stroke ──────

    fun insertPeriod(id: String, kind: Int, date: String): Statement =
        Statement("INSERT OR IGNORE INTO period (id, kind, date) VALUES (?, ?, ?)", id, kind.toLong(), date)

    /** The page row under the period named by `(kind, date)` — resolved in the statement, so the
     *  period row's id need not be the one this caller minted. */
    fun insertPage(id: String, kind: Int, date: String, half: Int, width: Float, height: Float, now: Long): Statement =
        Statement(
            "INSERT OR IGNORE INTO page (id, periodId, half, width, height, createdAt, updatedAt) " +
                "VALUES (?, (SELECT id FROM period WHERE kind = ? AND date = ?), ?, ?, ?, ?, ?)",
            id, kind.toLong(), date, half.toLong(), width.toDouble(), height.toDouble(), now, now,
        )

    /** The page learned its size (a `0 × 0` page — one a placement minted before any screen saw it). */
    fun sizePage(id: String, width: Float, height: Float, now: Long): Statement =
        Statement(
            "UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?",
            width.toDouble(), height.toDouble(), now, id,
        )

    /** The page's ink changed. */
    fun touchPage(id: String, now: Long): Statement =
        Statement("UPDATE page SET updatedAt = ? WHERE id = ?", now, id)

    // ── state — the bookmark ──────

    const val KEY_LAST_VIEW = "lastView"
    const val KEY_LAST_DATE = "lastDate"
    const val KEY_LAST_HALF = "lastHalf"

    fun setState(key: String, value: String): Statement =
        Statement("INSERT OR REPLACE INTO state (key, value) VALUES (?, ?)", key, value)

    // ── reads ──────

    fun selectPeriod(kind: Int, date: String): Statement =
        Statement("SELECT id FROM period WHERE kind = ? AND date = ?", kind.toLong(), date)

    /** Opening a page is one join: the page under the period named by `(kind, date)`, at [half]. */
    fun selectPage(kind: Int, date: String, half: Int): Statement =
        Statement(
            "SELECT page.id AS id, page.periodId AS periodId, page.width AS width, page.height AS height " +
                "FROM page JOIN period ON period.id = page.periodId " +
                "WHERE period.kind = ? AND period.date = ? AND page.half = ?",
            kind.toLong(), date, half.toLong(),
        )

    /** The read plan's first step: every stroke's order and blob length, which is small. */
    fun selectStrokeLens(pageId: String): Statement = InkSql.selectStrokeLens(pageId)

    /** One planned range of the page's strokes (`StrokeReadPlan`); `BETWEEN` is inclusive. */
    fun selectStrokes(pageId: String, range: LongRange): Statement = InkSql.selectStrokes(pageId, range)

    /** Where a placement onto an existing page starts numbering; `-1` on an empty page. */
    fun selectMaxOrder(pageId: String): Statement = InkSql.selectMaxOrder(pageId)

    fun selectState(): Statement =
        Statement("SELECT key, value FROM state")

    /** The three row counts in one read — the "browsing wrote nothing" proof, logged at `begin`. */
    fun selectCounts(): Statement =
        Statement(
            "SELECT (SELECT COUNT(*) FROM period) AS periods, (SELECT COUNT(*) FROM page) AS pages, " +
                "(SELECT COUNT(*) FROM stroke) AS strokes",
        )
}
