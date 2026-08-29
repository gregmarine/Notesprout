package com.symmetricalpalmtree.notesproutsn.data.soil

import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.notebook.LinkPayload
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB

/**
 * **The import remap pass** (arc 16 / I1) — the job the arc-15 / E3 round-trip finding named:
 * inside a `.soil`, the pages are parented to the **notebook id**, and `NotebookSession` queries
 * pages by the *index* row's id — so an import that lands under a fresh UUID (Keep both, or a
 * manifest id that failed validation) is a notebook that opens **empty** unless the file itself
 * is re-identified first.
 *
 * The pass runs **in-file, in the import cache, before the Garden copy**, on a connection the
 * pipeline owns (opened through `SoilCrypto` on the already-keyed cache file), inside one
 * transaction — a crash mid-remap leaves a cache temp the next import wipes, never a
 * half-identified Garden file. It covers, deliberately in this order:
 *
 *  1. the notebook row's own `id`;
 *  2. every row whose `parentId` is the old id (pages — and by the same statement anything else
 *     the family ever parents to the root row), soft-deleted rows included: a deleted page's
 *     parentage is still read by anything that walks the file;
 *  3. every `link` row whose payload targets the old notebook id by [LinkPayload]'s
 *     `KIND_NOTEBOOK` / `KIND_NOTEBOOK_PAGE` — a link "to this notebook by id" must follow the
 *     rename or it dies as a dead target (own-page `KIND_PAGE` links carry no notebook id and
 *     survive untouched);
 *  4. the `notebook_meta` row's `notebookId` — best effort, like every meta write (the pipeline's
 *     post-import refresh rewrites the row properly anyway).
 *
 * Child ids (pages, strokes, headings, links, templates) are **not** touched — they are minted
 * UUIDs with no meaning outside the file, and only the notebook's identity changed.
 */
object NotebookRemap {

    private const val TAG = "NotebookRemap"

    /**
     * Re-identify the open notebook file from [oldId] to [newId]. The caller owns the connection
     * (and closes/checkpoints it after); this pass owns its transaction. Throws on failure —
     * the pipeline treats that as an unacceptable import, not a partial one.
     */
    fun remap(db: ZeticDB, oldId: String, newId: String) {
        require(oldId.isNotBlank() && newId.isNotBlank() && oldId != newId) { "bad remap ids" }
        db.beginTransaction()
        try {
            db.execSQL("UPDATE ${SoilSchema.TABLE} SET id = ? WHERE id = ?", arrayOf(newId, oldId))
            db.execSQL("UPDATE ${SoilSchema.TABLE} SET parentId = ? WHERE parentId = ?", arrayOf(newId, oldId))
            remapLinkRows(db, oldId, newId)
            remapMeta(db, newId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Slog.d(TAG) { "remapped notebook identity" }
    }

    /** Rewrite every link payload that targets [oldId] — collected first, then updated by row id
     *  (never mutate under an open cursor). */
    private fun remapLinkRows(db: ZeticDB, oldId: String, newId: String) {
        val updates = ArrayList<Pair<String, String>>()
        db.rawQuery(
            "SELECT id, text FROM ${SoilSchema.TABLE} WHERE type = ?",
            arrayOf(SoilSchema.TYPE_LINK),
        ).use { c ->
            while (c.moveToNext()) {
                val rowId = c.getString(0)
                val payload = c.getString(1) ?: continue
                val remapped = remapLinkPayload(payload, oldId, newId) ?: continue
                updates.add(rowId to remapped)
            }
        }
        for ((rowId, payload) in updates) {
            db.execSQL(
                "UPDATE ${SoilSchema.TABLE} SET text = ? WHERE id = ?",
                arrayOf(payload, rowId),
            )
        }
        if (updates.isNotEmpty()) Slog.d(TAG) { "re-pointed ${updates.size} link payload(s)" }
    }

    /**
     * The pure half, pinned by test: the same payload with only the notebook id changed, or null
     * when there is nothing to do — an unusable payload (foreign/future grammar stays exactly as
     * it came: rewriting what we cannot read would corrupt it), a kind that carries no notebook
     * id, or a target that is not [oldId].
     */
    fun remapLinkPayload(payload: String, oldId: String, newId: String): String? {
        val decoded = LinkPayload.decode(payload) ?: return null
        if (decoded.notebookId != oldId) return null
        return LinkPayload.encode(decoded.chrome, decoded.kind, newId, decoded.pageId)
    }

    /** Restamp `notebook_meta.notebookId` — best effort; a missing or unreadable row is skipped. */
    private fun remapMeta(db: ZeticDB, newId: String) {
        try {
            val json = db.rawQuery("SELECT json FROM ${SoilSchema.META_TABLE} WHERE id = 0", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: return
            val stamped = try {
                NotebookMeta.fromJson(json).copy(notebookId = newId).toJson()
            } catch (_: Exception) {
                return
            }
            db.execSQL("UPDATE ${SoilSchema.META_TABLE} SET json = ? WHERE id = 0", arrayOf(stamped))
        } catch (e: Exception) {
            Log.w(TAG, "meta remap skipped: ${e.javaClass.simpleName}")
        }
    }
}
