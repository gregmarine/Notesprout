package com.notesprout.android.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * One-time, idempotent compaction that strips the legacy per-point `ts` from stored
 * stroke rows and reclaims the freed disk space.
 *
 * Background: every [StrokePoint] used to carry a `ts` (JSON key `"ts"`) that was never
 * read and was stamped with a single save-time value per stroke — roughly 40% of a
 * stroke's JSON, and ~29% of a heavy notebook's total size. New writes no longer emit it
 * (see [StrokePoint.timestamp]/[LiveStroke.toStrokeData]), but existing rows keep theirs
 * until rewritten. This backfills that: it rewrites the `ts`-bearing rows in place and
 * VACUUMs the file.
 *
 * Two invariants make it safe to run opportunistically:
 * - **Self-limiting.** Only rows whose `data` still contains `"ts"` are touched
 *   ([NotebookDao.strokeRowsWithLegacyTimestamp]); once compacted a notebook does no work
 *   beyond a cheap indexless scan, so it can run on every seal without accumulating cost.
 * - **`updatedAt` preserved.** The rewrite uses [NotebookDao.rewriteStrokeDataKeepingTimestamp]
 *   so a row's `updatedAt` is untouched. Bumping it would make [NotebookDao.getMaxContentUpdatedAt]
 *   exceed the page snapshot's timestamp, invalidating every page's fast-load snapshot and
 *   forcing a full re-render — the opposite of what snapshots are for.
 *
 * Reclamation requires a full `VACUUM`: shrinking a TEXT value in place leaves the freed
 * bytes as internal page fragmentation, which `incremental_vacuum` does not return to the OS.
 * VACUUM preserves SQLCipher encryption. It is only issued when at least one row changed.
 *
 * Only `type = 'stroke'` rows are compacted. Strokes embedded in headings/text/links/
 * sticky-notes still carry `ts`; they are a small tail left for a future pass.
 */
object StrokeCompactor {

    /**
     * Strip legacy per-point `ts` from every stroke row that still has it, then VACUUM if
     * anything changed. Returns the number of stroke rows rewritten (0 = already compact,
     * no VACUUM issued). Runs its own DB work — call from `Dispatchers.IO`.
     */
    suspend fun compact(db: SoilDatabase): Int {
        val dao = db.notebookDao()
        val rows = dao.strokeRowsWithLegacyTimestamp()
        for (r in rows) {
            val sd = StrokeData.fromJson(r.data)
            val stripped = sd.copy(
                points = sd.points.map { if (it.timestamp == null) it else it.copy(timestamp = null) },
            )
            dao.rewriteStrokeDataKeepingTimestamp(r.id, stripped.toJson())
        }
        if (rows.isNotEmpty()) {
            val raw: SupportSQLiteDatabase = db.openHelper.writableDatabase
            raw.execSQL("VACUUM")
        }
        return rows.size
    }
}
