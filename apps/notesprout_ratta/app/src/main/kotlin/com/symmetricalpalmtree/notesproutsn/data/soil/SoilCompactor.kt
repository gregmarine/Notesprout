package com.symmetricalpalmtree.notesproutsn.data.soil

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.symmetricalpalmtree.notesproutsn.core.Slog
import java.io.File

/**
 * **The `.soil` purge** (arc 17 / K1): every close hard-deletes the file's soft-deleted rows and
 * gives the space back. Undo is in-memory and dies with the session, so a closed notebook's
 * soft-deleted rows are unreachable by construction — from the user's view nothing changes except
 * file size. og never purged user content; SN does, on the user's explicit 2026-08-28 decision.
 *
 * Runs from the notebook close path ([com.symmetricalpalmtree.notesproutsn.notebook.NotebookSession]
 * `seal`, after the writer has drained and before [SoilDatabase.seal]'s checkpoint absorbs the
 * result) and standalone from K2's backup compact pass. **[compact] never throws** — it rides
 * inside seal's never-throw contract, and a compaction bug must never cost a save.
 *
 * Three rules, each carrying an arc's lesson:
 *  - **Template rows are exempt, by type, from both passes** (arc 13): nothing ever soft-deletes a
 *    template and reuse-before-mint depends on the rows persisting — and a foreign file that broke
 *    the first half of that rule must not lose paper an alive page may still point at.
 *  - **The cascade starts from what this purge deletes, never from "parent missing"** — an alive
 *    row whose parent was purged is unreachable and goes with it, but an alive row whose parent
 *    simply isn't in the file is evidence of damage, and never-delete-on-corruption applies:
 *    sweeping it would turn one corrupt `parentId` into destroyed content.
 *  - **`updatedAt` is untouched everywhere** (og's rule): rows are deleted, never rewritten — a
 *    bump would re-flag the notebook as needing backup forever.
 *
 * `VACUUM`, not `incremental_vacuum`, and only when something was deleted (og's measured finding:
 * freed-value fragmentation is not returned by incremental, and an imported/re-keyed file may not
 * have `auto_vacuum` set at all, so the full form is the only one that works fleet-wide; it
 * preserves SQLCipher encryption). The checkpoint that follows in [SoilDatabase.seal] absorbs it.
 */
object SoilCompactor {

    private const val TAG = "SoilCompactor"

    // ── The pure half (JVM-tested) ───────────────────────────────────────────

    /** One row of the snapshot the purge decides over — identity and liveness, nothing else. */
    data class Row(val id: String, val parentId: String, val type: String, val deleted: Boolean)

    /**
     * The ids to hard-delete: every soft-deleted row, plus the cascade — rows (alive or not)
     * parented, transitively, to a row this purge removes. Template rows never appear, on either
     * side of the cascade; a row whose parent was never in [rows] at all survives (see class doc).
     */
    fun purgeIds(rows: List<Row>): Set<String> {
        val byParent = rows.groupBy { it.parentId }
        val doomed = HashSet<String>()
        val queue = ArrayDeque<String>()
        for (r in rows) {
            if (r.deleted && r.type != SoilSchema.TYPE_TEMPLATE && doomed.add(r.id)) queue.add(r.id)
        }
        while (queue.isNotEmpty()) {
            for (child in byParent[queue.removeFirst()].orEmpty()) {
                if (child.type == SoilSchema.TYPE_TEMPLATE) continue
                if (doomed.add(child.id)) queue.add(child.id)
            }
        }
        return doomed
    }

    /**
     * Whether the `-wal`/`-shm` pair beside a sealed file may be removed: yes when the WAL is
     * absent (a stray `-shm` describes nothing) or empty (fully checkpointed). **A non-empty WAL
     * is live data** — a failed checkpoint leaves real writes in it, and og's rule stands: it is
     * copied alongside the file at backup, never deleted.
     */
    fun sidecarsRemovable(walExists: Boolean, walLength: Long): Boolean =
        !walExists || walLength == 0L

    // ── The executors ────────────────────────────────────────────────────────

    /**
     * Purge [db] (an open `.soil` connection with no writers behind it) and `VACUUM` iff anything
     * was deleted. Returns how many rows went; 0 on any failure — logged, never thrown.
     */
    fun compact(db: SupportSQLiteDatabase): Int {
        // The cheap gate first (K3 review): the common close has nothing soft-deleted — K1 purged
        // at the previous close — and must not pay a whole-table snapshot to find that out. Exact,
        // because the cascade only ever starts from a soft-deleted row.
        try {
            db.query("SELECT EXISTS(SELECT 1 FROM ${SoilSchema.TABLE} WHERE deletedAt IS NOT NULL)").use { c ->
                if (!(c.moveToFirst() && c.getInt(0) != 0)) return 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "purge probe failed", e)
            return 0
        }
        val doomed = try {
            purgeIds(snapshot(db)).toList()
        } catch (e: Exception) {
            Log.w(TAG, "purge snapshot failed", e)
            return 0
        }
        if (doomed.isEmpty()) return 0
        try {
            db.beginTransaction()
            try {
                // SQLite's bound-parameter ceiling is 999; stay well under it.
                for (chunk in doomed.chunked(500)) {
                    val marks = chunk.joinToString(",") { "?" }
                    db.execSQL("DELETE FROM ${SoilSchema.TABLE} WHERE id IN ($marks)", chunk.toTypedArray())
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.w(TAG, "purge failed", e)
            return 0
        }
        try {
            db.execSQL("VACUUM")
        } catch (e: Exception) {
            // The rows are gone either way; the space comes back on the next successful close.
            Log.w(TAG, "VACUUM failed", e)
        }
        Slog.d(TAG) { "purged ${doomed.size} row(s)" }
        return doomed.size
    }

    /**
     * Sidecar hygiene after a close ([SoilDatabase.seal] calls this last, once no connection is
     * left): remove a fully-checkpointed WAL and its `-shm`, or a stray `-shm` with no WAL at all.
     * Never touches a non-empty WAL. Never throws.
     */
    fun sweepSidecars(file: File) {
        try {
            val wal = File(file.path + "-wal")
            val shm = File(file.path + "-shm")
            if (!sidecarsRemovable(wal.exists(), if (wal.exists()) wal.length() else 0L)) return
            if (wal.exists()) wal.delete()
            if (shm.exists()) shm.delete()
        } catch (e: Exception) {
            Log.w(TAG, "sidecar sweep failed for ${file.name}", e)
        }
    }

    private fun snapshot(db: SupportSQLiteDatabase): List<Row> {
        val rows = ArrayList<Row>()
        db.query("SELECT id, parentId, type, deletedAt IS NOT NULL FROM ${SoilSchema.TABLE}").use { c ->
            while (c.moveToNext()) {
                rows.add(Row(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) != 0))
            }
        }
        return rows
    }
}
