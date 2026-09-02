package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.ink.InkDocument
import com.symmetricalpalmtree.notesproutsn.ink.InkSql
import com.symmetricalpalmtree.notesproutsn.ink.StrokeBlob

/**
 * Every statement the scratch pad sends, as a pure builder (arc 22 / X2) — SQL text and bound
 * arguments and nothing else, so the shapes are JVM-testable without a store.
 *
 * **Two write ops, both idempotent**, because a batch that failed part-way is retried by whatever
 * caller owns it and the retry has to converge:
 *
 * - a stroke row is written with `INSERT OR REPLACE` (a stroke has no children, so REPLACE is safe)
 *   and removed with `DELETE … WHERE id = ?` (a row that is not there is not an error);
 * - a **page** row is created with `INSERT OR IGNORE` and then `UPDATE`d. **Never
 *   `INSERT OR REPLACE INTO page`**: REPLACE deletes the conflicting row first, and with
 *   `foreign_keys` ON that delete CASCADES — it would take the page's strokes with it.
 *
 * **The `stroke` half is `:ext-ink`'s** ([InkSql], arc 23): the two write statements arrive as
 * [InkDocument.StrokeSql] by delegation and the three reads forward, so the pad's table and the
 * calendar's are declared and addressed once rather than twice. The text is unchanged — this file's
 * own test pins every string through the host's validator. What is written out here is what is the
 * **pad's own**: its `page` table, its `state` key and the two page reads.
 *
 * `now` is passed in rather than read here so a test can pin it.
 */
object ScratchSql : InkDocument.StrokeSql by InkSql {

    // ── page ──────

    fun insertPage(id: String, position: Int, width: Float, height: Float, now: Long): Statement =
        Statement(
            "INSERT OR IGNORE INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
            id, position.toLong(), width.toDouble(), height.toDouble(), now, now,
        )

    /** The page learned its size (a `0 × 0` page at first layout, or a placement's page size). */
    fun sizePage(id: String, width: Float, height: Float, now: Long): Statement =
        Statement(
            "UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?",
            width.toDouble(), height.toDouble(), now, id,
        )

    /** One page's place in the list. Renumbering is per id — page counts are tens. */
    fun position(id: String, position: Int): Statement =
        Statement("UPDATE page SET position = ? WHERE id = ?", position.toLong(), id)

    /** Drops the page **and its strokes** (the declared cascade). */
    fun deletePage(id: String): Statement =
        Statement("DELETE FROM page WHERE id = ?", id)

    /** Empties a page, keeping the row — what deleting the pad's lone page does. */
    fun clearPage(id: String): Statement = InkSql.clearStrokes(id)

    // ── state ──────

    fun setCurrent(id: String): Statement =
        Statement("INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)", id)

    // ── reads ──────

    fun selectPages(): Statement =
        Statement("SELECT id FROM page ORDER BY position")

    fun selectCurrent(): Statement =
        Statement("SELECT value FROM state WHERE key = 'current'")

    fun selectPageSize(id: String): Statement =
        Statement("SELECT width, height FROM page WHERE id = ?", id)

    /** The read plan's first step: every stroke's order and blob length, which is small. */
    fun selectStrokeLens(pageId: String): Statement = InkSql.selectStrokeLens(pageId)

    /** One planned range of the page's strokes (`StrokeReadPlan`); `BETWEEN` is inclusive. */
    fun selectStrokes(pageId: String, range: LongRange): Statement = InkSql.selectStrokes(pageId, range)

    /** Where a placement onto the current page starts numbering; `-1` on an empty page. */
    fun selectMaxOrder(pageId: String): Statement = InkSql.selectMaxOrder(pageId)

    // ── geometry ──────

    /** A stroke's points as `StrokeCodec` format B — the `.soil`'s own encoding, unchanged
     *  (`:ext-ink`'s [StrokeBlob], kept under the pad's name for its callers and tests). */
    fun geometry(stroke: Stroke): ByteArray = StrokeBlob.encode(stroke)
}
