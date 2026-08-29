package com.symmetricalpalmtree.notesproutsn.data.index

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.symmetricalpalmtree.notesproutsn.core.Slog

/**
 * **The global-index purge** (arc 17 / K1) — the `.soil` purge's index-side twin
 * ([com.symmetricalpalmtree.notesproutsn.data.soil.SoilCompactor]): hard-delete every soft-deleted
 * `objects` row, sweep the `list_item` edges a proper delete would have scrubbed, and `VACUUM`
 * when anything went. Runs opportunistically at bootstrap when soft-deleted rows exist (the one
 * moment the index has no other reader) and from K2's backup run.
 *
 * Purging soft-deleted rows is safe against every revive-in-place path by inspection, pinned by
 * the K1 review: `createNotebook` and `importNotebookRow` upsert (a missing row inserts fresh,
 * keeping the caller-provided `createdAt`), `setScheme` mints a new naming row when none is found,
 * and the clipboard's next copy upserts the sentinel-id row wholesale. Sentinels that were never
 * rows (Blank, Default, the three built-in papers — `ListIds`) cannot be reached by a
 * `deletedAt IS NOT NULL` delete at all.
 *
 * The edge sweep is where the arc-13 prune trap lives: a `list_item`'s `refId` may name a row
 * **or a built-in sentinel that is not a row** (the pinned-templates shelf), so "refId doesn't
 * resolve" must exempt the sentinel ids by name or a purge would silently unpin every built-in
 * paper. An edge with a null `refId` is malformed in a way this pass does not understand — left
 * exactly as it came (the remap rule: never rewrite what you cannot read).
 *
 * `updatedAt` is untouched everywhere — rows are deleted, never rewritten. Never throws.
 */
object IndexCompactor {

    private const val TAG = "IndexCompactor"

    // ── The pure half (JVM-tested) ───────────────────────────────────────────

    /**
     * The `refId`s an edge may carry that no row will ever back — the template library's
     * sentinels. Pinned by test against `TemplateLibrary.SENTINEL_IDS` (defined here from the same
     * `ListIds` constants so the data layer does not reach into a screen package).
     */
    val PROTECTED_REF_IDS: Set<String> = setOf(
        ListIds.TEMPLATE_BLANK_ID,
        ListIds.TEMPLATE_DEFAULT_ID,
        ListIds.TEMPLATE_LINED_ID,
        ListIds.TEMPLATE_DOTTED_ID,
        ListIds.TEMPLATE_GRID_ID,
    )

    /** One membership edge, as the sweep sees it. */
    data class Edge(val id: String, val refId: String?)

    /**
     * The edge rows to hard-delete: members whose `refId` is neither an existing row ([rowIds],
     * read **after** the soft-deleted purge) nor a protected sentinel. Null `refId` survives.
     */
    fun orphanEdgeIds(edges: List<Edge>, rowIds: Set<String>): List<String> =
        edges.filter { it.refId != null && it.refId !in rowIds && it.refId !in PROTECTED_REF_IDS }
            .map { it.id }

    // ── The executor ─────────────────────────────────────────────────────────

    /** True when the index holds anything worth a purge — the cheap bootstrap gate. */
    fun hasSoftDeletedRows(db: SupportSQLiteDatabase): Boolean = try {
        db.query("SELECT EXISTS(SELECT 1 FROM objects WHERE deletedAt IS NOT NULL)").use { c ->
            c.moveToFirst() && c.getInt(0) != 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "soft-delete probe failed", e)
        false
    }

    /**
     * Purge [db] (the open index) and `VACUUM` iff anything was deleted. Returns how many rows
     * went (soft-deleted + swept edges); 0 on any failure — logged, never thrown. IO thread.
     */
    fun compact(db: SupportSQLiteDatabase): Int {
        val deleted = try {
            var n = 0
            db.beginTransaction()
            try {
                db.compileStatement("DELETE FROM objects WHERE deletedAt IS NOT NULL")
                    .use { n += it.executeUpdateDelete() }
                val edges = ArrayList<Edge>()
                db.query("SELECT id, refId FROM objects WHERE type = 'list_item'").use { c ->
                    while (c.moveToNext()) edges.add(Edge(c.getString(0), if (c.isNull(1)) null else c.getString(1)))
                }
                val rowIds = HashSet<String>()
                db.query("SELECT id FROM objects").use { c ->
                    while (c.moveToNext()) rowIds.add(c.getString(0))
                }
                for (chunk in orphanEdgeIds(edges, rowIds).chunked(500)) {
                    val marks = chunk.joinToString(",") { "?" }
                    db.execSQL("DELETE FROM objects WHERE id IN ($marks)", chunk.toTypedArray())
                    n += chunk.size
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            n
        } catch (e: Exception) {
            Log.w(TAG, "index purge failed", e)
            return 0
        }
        if (deleted == 0) return 0
        try {
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            db.execSQL("VACUUM")
        } catch (e: Exception) {
            // The rows are gone either way; the space comes back on the next successful purge.
            Log.w(TAG, "index VACUUM failed", e)
        }
        Slog.d(TAG) { "purged $deleted index row(s)" }
        return deleted
    }
}
