package com.notesprout.android.data

import android.util.Base64
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.notesprout.android.core.ImageCodec
import com.notesprout.android.data.index.NotebookObject
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ObjectType
import com.notesprout.android.data.index.TemplateObject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One-time, idempotent **transitional** compaction of stored notebooks and the global index.
 * Independent migrations share one pass so a single VACUUM reclaims them all:
 *
 * 1. **Legacy-`ts` strip.** Every [StrokePoint] used to carry a per-point `ts` that was never read
 *    (~40% of a stroke's JSON). New writes no longer emit it; this rewrites the rows that still have
 *    it. See [StrokePoint.timestamp] / [LiveStroke.toStrokeData].
 * 2. **Image → WEBP q100.** Embedded page templates (and the index's template/cover images) are
 *    re-encoded to WEBP q100 (see [ImageCodec]). This catches both legacy **PNG** and the earlier,
 *    mistaken **lossless-WEBP** blobs (Android's Skia lossless encoder bloated to 2–6× PNG),
 *    converting both to the compact q100 form. Already-q100 rows are skipped.
 * 3. **Dead heading/text strokes strip.** Recognized headings and converted text objects used to
 *    embed a full copy of their original handwriting strokes that was never read again (headings
 *    once supported un-heading; text never reverted). Both now drop those strokes on conversion;
 *    this rewrites the legacy rows that still carry them. Unrecognized fallbacks — where the strokes
 *    ARE the visual — keep theirs. See [HeadingObject] / [TextObject].
 * 4. **Per-page snapshot strip.** Page rows used to cache a base64 `snapshot` PNG. Snapshots are no
 *    longer stored per page (the library-grid cover is captured to the global index on close), so
 *    this drops the dead blob from every page that still carries one. Runs on close for encrypted
 *    notebooks too (their key is only available while open), cleaning them in place.
 * 5. **Custom-cover removal.** The removed "set a custom cover image" feature stored `type='cover'`
 *    rows referenced by the notebook row's `cover` pointer. This hard-deletes those rows and drops
 *    the stale pointer.
 *
 * All passes are safe to run opportunistically:
 * - **Self-limiting.** ts / snapshot / cover rows are found by `LIKE` scans; template image rows are
 *   decided from a cheap header ([needsWebpReencode]). Once converted a notebook does no heavy work
 *   beyond those scans, so this can run on every seal.
 * - **`updatedAt` preserved.** Rewrites go through [NotebookDao.rewriteObjectDataKeepingTimestamp]
 *   (and [com.notesprout.android.data.index.ObjectDao.rewriteObjectData] for the index) so no row's
 *   `updatedAt` moves. None of these are content edits; bumping it would needlessly re-flag the file
 *   for backup.
 *
 * Reclamation requires a full `VACUUM`: shrinking a TEXT value in place leaves the freed bytes as
 * internal page fragmentation, which `incremental_vacuum` does not return to the OS. VACUUM
 * preserves SQLCipher encryption. It is only issued when at least one row changed.
 *
 * This whole object is transitional — see BACKLOG.md. [ImageCodec] and the nullable
 * [StrokePoint.timestamp] stay; this class and its DAO queries are removed once every device has
 * been swept.
 */
object NotebookCompactor {

    /** Per-notebook outcome: rows rewritten by each pass. [changed] gates the VACUUM / re-backup. */
    data class Result(
        val tsRows: Int,
        val imageRows: Int,
        val deadStrokeRows: Int = 0,
        val snapshotRows: Int = 0,
        val coverRows: Int = 0,
        val strokeBlobRows: Int = 0,
        val compositeRows: Int = 0,
        val orphanRows: Int = 0,
        val structuralRows: Int = 0,
        val orphanSubtreeRows: Int = 0,
    ) {
        val changed: Boolean
            get() = tsRows > 0 || imageRows > 0 || deadStrokeRows > 0 || snapshotRows > 0 ||
                coverRows > 0 || strokeBlobRows > 0 || compositeRows > 0 || orphanRows > 0 ||
                structuralRows > 0 || orphanSubtreeRows > 0
    }

    /**
     * Strip legacy `ts` and re-encode PNG/lossless-WEBP images in a single `.soil`, then VACUUM once
     * if anything changed. Runs its own DB work — call from `Dispatchers.IO`. [density] is used only
     * by the composite→child-row pass, and only to round-trip embedded line/shape dp values (the
     * conversion is density-neutral, so any consistent value works).
     */
    suspend fun compact(db: SoilDatabase, density: Float): Result {
        val dao = db.notebookDao()

        // Pass 1 — drop dead per-point ts from stroke rows.
        val tsRows = dao.strokeRowsWithLegacyTimestamp()
        for (r in tsRows) {
            val sd = StrokeData.fromJson(r.data)
            val stripped = sd.copy(
                points = sd.points.map { if (it.timestamp == null) it else it.copy(timestamp = null) },
            )
            dao.rewriteObjectDataKeepingTimestamp(r.id, stripped.toJson())
        }

        // Pass 2 — re-encode PNG / lossless-WEBP snapshots / templates / covers to WEBP q100.
        var imageRows = 0
        for (h in dao.imageRowHeads()) {
            if (!needsWebpReencode(h.head)) continue
            val data = dao.imageDataForId(h.id) ?: continue
            val newData = transcodeSoilRow(h.type, data) ?: continue
            dao.rewriteObjectDataKeepingTimestamp(h.id, newData)
            imageRows++
        }

        // Pass 3 — drop dead embedded strokes from recognized headings / converted text objects.
        var deadStrokeRows = 0
        for (r in dao.headingTextRowsWithStrokes()) {
            val newData = stripDeadStrokes(r.type, r.data) ?: continue
            dao.rewriteObjectDataKeepingTimestamp(r.id, newData)
            deadStrokeRows++
        }

        // Pass 4 — strip legacy per-page `snapshot` blobs. Snapshots are no longer stored per page
        // (the notebook cover on the library screen is captured to the global index on close). Re-
        // serializing through PageData drops the now-unknown key. `updatedAt` is preserved.
        var snapshotRows = 0
        for (r in dao.pageRowsWithSnapshot()) {
            dao.rewriteObjectDataKeepingTimestamp(r.id, PageData.fromJson(r.data).toJson())
            snapshotRows++
        }

        // Pass 5 — delete legacy custom-cover objects and drop the notebook row's `cover` pointer.
        val coverRows = dao.deleteCoverRows()
        val nbRow = dao.getNotebookObject()
        // Match the JSON key `"cover":` precisely (not a title value like "cover"). Re-serializing
        // through NotebookMetadata (which no longer has a `cover` field) drops the stale key; the key
        // is gone afterwards, so later seals skip this — no per-seal rewrite loop.
        if (nbRow != null && nbRow.data.contains("\"cover\":")) {
            dao.rewriteObjectDataKeepingTimestamp(nbRow.id, NotebookMetadata.fromJson(nbRow.id, nbRow.data).toJson())
        }

        // Pass 6 — convert legacy JSON stroke rows to the binary blob format (data-model-optimization
        // Phase 1). This is the lazy migration: a legacy notebook shrinks (~5×) on its next close.
        // Self-limiting (blob IS NULL filter) and not a content edit, so `updatedAt` is preserved.
        // Batched in one transaction — the first close of a big legacy notebook can convert ~10k rows.
        var strokeBlobRows = 0
        val legacyStrokes = dao.legacyStrokeRowsToConvert()
        if (legacyStrokes.isNotEmpty()) {
            db.withTransaction {
                for (r in legacyStrokes) {
                    val sd = try { StrokeData.fromJson(r.data) } catch (e: Exception) { continue }
                    if (sd.points.isEmpty()) continue
                    dao.convertStrokeToBlobKeepingTimestamp(
                        r.id, LiveStroke.packPoints(sd.toPointFs()), sd.color, sd.strokeWidth,
                    )
                    strokeBlobRows++
                }
            }
        }

        // Pass 7 — convert legacy composites (heading/text/link/sticky) that still hold their nested
        // content inline (data JSON or zlib(JSON) blob) into child-row subtrees (Phase 2c). Reads via
        // the format-agnostic renderers, re-persists via the subtree writers (which clear data + blob).
        // `updatedAt` is preserved by passing the row's own timestamp — a format change, not an edit.
        var compositeRows = 0
        val legacyComposites = dao.legacyBlobCompositeRows()
        if (legacyComposites.isNotEmpty()) {
            db.withTransaction {
                for (row in legacyComposites) {
                    when (row.type) {
                        TYPE_STICKY_NOTE -> dao.replaceStickyNoteSubtree(row.toStickyNoteRender(density) ?: continue, row.updatedAt, density)
                        TYPE_LINK        -> dao.replaceLinkSubtree(row.toLinkRender(density) ?: continue, row.updatedAt, density)
                        TYPE_HEADING     -> dao.replaceHeadingSubtree(row.toHeadingStroke() ?: continue, row.updatedAt)
                        TYPE_TEXT        -> dao.replaceTextSubtree(row.toTextRender() ?: continue, row.updatedAt)
                        else             -> continue
                    }
                    compositeRows++
                }
            }
        }

        // Pass 8 — sweep orphan stroke children left under a recognized heading/text by a
        // fallback→recognized transition (the parent now reads from its text column and ignores them).
        var orphanRows = 0
        val orphanParents = dao.recognizedCompositeParentIdsWithChildren()
        if (orphanParents.isNotEmpty()) {
            db.withTransaction {
                for (pid in orphanParents) { dao.hardDeleteDescendants(pid); orphanRows++ }
            }
        }

        // Pass 9 — convert legacy structural + leaf rows (page/layer/notebook/template/shape/line) to
        // the Phase 2b/1 columnar form, so an imported-from-stable notebook becomes fully JSON-free.
        // Each write keeps the row's own `updatedAt`; page/template keep their size in boundingBox.
        var structuralRows = 0
        val legacyStructural = dao.legacyStructuralRows()
        if (legacyStructural.isNotEmpty()) {
            db.withTransaction {
                for (row in legacyStructural) {
                    when (row.type) {
                        "page"     -> dao.updatePageTemplate(row.id, row.pageData().template, row.updatedAt)
                        "layer"    -> { val (t, f) = layerColumnsFromLegacy(row.data); dao.updateLayerColumnarKeepingTimestamp(row.id, t, f, row.updatedAt) }
                        "notebook" -> dao.upsertNotebookObject(row.notebookMetadata().writeOnto(row, row.updatedAt))
                        "template" -> { val td = row.templateDataOrNull() ?: continue; dao.updateTemplateColumnarKeepingTimestamp(row.id, td.name, templateImageBlob(td.image), row.updatedAt) }
                        "shape"    -> dao.updateColumns((row.toShapeRender(density) ?: continue).toRow("", 0, 0L, row.updatedAt, density))
                        "line"     -> dao.updateColumns((row.toLineRender(density) ?: continue).toRow("", 0, 0L, row.updatedAt, density))
                        else       -> continue
                    }
                    structuralRows++
                }
            }
        }

        // Pass 10 — sweep content rows orphaned by a purged composite parent (a deleted sticky/link/
        // heading/text whose parent row was hard-deleted, leaving its live child subtree dangling).
        // Loop to cascade through nesting (composite → heading → stroke). A soft-deleted parent still
        // exists, so its children are left intact — only truly parentless rows are removed.
        var orphanSubtreeRows = 0
        while (true) {
            val n = dao.hardDeleteOrphansOnce()
            if (n == 0) break
            orphanSubtreeRows += n
        }

        if (tsRows.isNotEmpty() || imageRows > 0 || deadStrokeRows > 0 || snapshotRows > 0 ||
            coverRows > 0 || strokeBlobRows > 0 || compositeRows > 0 || orphanRows > 0 ||
            structuralRows > 0 || orphanSubtreeRows > 0) {
            val raw: SupportSQLiteDatabase = db.openHelper.writableDatabase
            raw.execSQL("VACUUM")
        }
        return Result(tsRows.size, imageRows, deadStrokeRows, snapshotRows, coverRows, strokeBlobRows, compositeRows, orphanRows, structuralRows, orphanSubtreeRows)
    }

    /**
     * Strip the dead embedded `strokes` from a recognized `heading`/`text` row, keeping the rest of
     * the JSON intact. Returns null (skip) for unrecognized fallbacks — a stroke-only heading
     * (`recognizedText == null`) or a blank-text object — where the strokes are the visual content
     * and must stay. Empty strokes are omitted on re-encode (default field, `encodeDefaults = false`).
     */
    private fun stripDeadStrokes(type: String, data: String): String? = when (type) {
        "heading" -> {
            val h = HeadingObject.fromJson(data)
            if (h.recognizedText == null || h.strokes.isEmpty()) null
            else h.copy(strokes = emptyList()).toJson()
        }
        "text" -> {
            val t = TextObject.fromJson(data)
            if (t.text.isBlank() || t.strokes.isNullOrEmpty()) null
            else t.copy(strokes = null).toJson()
        }
        else -> null
    }

    /**
     * Transcode the image inside a `.soil` `template` row to WEBP q100, keeping the rest of the JSON
     * intact. Returns null (skip) when the row holds no image or fails to decode. Page snapshots and
     * cover objects no longer carry images, so only templates are transcoded.
     */
    private fun transcodeSoilRow(type: String, data: String): String? = when (type) {
        "template" -> {
            val td = TemplateData.fromJson(data) ?: return null
            val webp = ImageCodec.transcodeToWebpBase64(td.image) ?: return null
            td.copy(image = webp).toJson()
        }
        else -> null
    }

    /**
     * Re-encode the PNG/lossless-WEBP images in the global index (`notesprout.db`) — template-library
     * images and notebook cover snapshots — then VACUUM once if anything changed. The index is a
     * long-lived Room singleton, so this is invoked from the manual sweep rather than a per-notebook
     * seal. Returns the number of rows rewritten.
     */
    suspend fun compactIndex(): Int {
        val dao = NotesproutIndex.dao()
        var changed = 0
        for (h in dao.imageRowHeads()) {
            if (!needsWebpReencode(h.head)) continue
            val data = dao.imageDataForId(h.id) ?: continue
            val newData = transcodeIndexRow(h.type, data) ?: continue
            dao.rewriteObjectData(h.id, newData)
            changed++
        }
        if (changed > 0) {
            NotesproutIndex.db().openHelper.writableDatabase.execSQL("VACUUM")
        }
        return changed
    }

    /**
     * Bulk-convert the legacy JSON strokes in the global index's `calendar` + `scratchpad` tables to
     * the binary blob format (data-model-optimization Phase 3). Existing rows never convert on normal
     * use (their saveStrokes path is INSERT-OR-IGNORE, so an edit re-inserts nothing), so this one-shot
     * pass — invoked from the user's "Compact Notebooks" sweep — reclaims the backlog (~29MB → ~6MB
     * measured). Batched in one transaction; self-limiting via the blob-null filter; VACUUMs once.
     * Returns the number of strokes converted.
     */
    suspend fun compactCalendarScratchpadStrokes(): Int {
        val db = NotesproutIndex.db()
        val calDao = NotesproutIndex.calendarDao()
        val scratchDao = NotesproutIndex.scratchpadDao()
        var converted = 0
        db.withTransaction {
            for (r in calDao.legacyStrokeRowsToConvert()) {
                val sd = try { StrokeData.fromJson(r.data) } catch (e: Exception) { continue }
                if (sd.points.isEmpty()) continue
                calDao.convertStrokeToBlobKeepingTimestamp(r.id, LiveStroke.packPoints(sd.toPointFs()), sd.color, sd.strokeWidth)
                converted++
            }
            for (r in scratchDao.legacyStrokeRowsToConvert()) {
                val sd = try { StrokeData.fromJson(r.data) } catch (e: Exception) { continue }
                if (sd.points.isEmpty()) continue
                scratchDao.convertStrokeToBlobKeepingTimestamp(r.id, LiveStroke.packPoints(sd.toPointFs()), sd.color, sd.strokeWidth)
                converted++
            }
        }
        if (converted > 0) db.openHelper.writableDatabase.execSQL("VACUUM")
        return converted
    }

    private fun transcodeIndexRow(type: String, data: String): String? = when (type) {
        ObjectType.TEMPLATE -> {
            val t = TemplateObject.fromJson(data) ?: return null
            val webp = ImageCodec.transcodeToWebpBase64(t.image) ?: return null
            t.copy(image = webp).toJson()
        }
        ObjectType.NOTEBOOK -> {
            val n = try { Json.decodeFromString<NotebookObject>(data) } catch (e: Exception) { return null }
            val snap = n.snapshot ?: return null
            val webp = ImageCodec.transcodeToWebpBase64(snap) ?: return null
            Json.encodeToString(n.copy(snapshot = webp))
        }
        else -> null
    }

    /**
     * Decide from a row's JSON [head] (first ~4000 chars of `data`) whether its embedded image should
     * be re-encoded to WEBP q100: true for **PNG** and for **lossless WEBP** (a `VP8L` codec chunk),
     * false for already-lossy WEBP (`VP8 `), non-images, or undecodable input.
     *
     * The head is the row's `data` JSON (e.g. `{…,"snapshot":"UklGR…"}`), so we first locate the
     * embedded image base64 by its magic prefix — `iVBOR` (PNG) or `UklGR` (WEBP `RIFF`) — then decode
     * just that run. PNG needs re-encoding outright. WEBP is classified by walking the RIFF chunk list
     * (not a substring search, which would false-match `VP8L`-like bytes inside an ICC profile):
     * skip `VP8X`/`ICCP`/`ALPH`/… by their declared size until the codec chunk. **Degradation-safe** —
     * only a positively-identified `VP8L` returns true, so a lossy image is never re-encoded
     * lossy→lossy. Worst case (a lossless codec chunk beyond the window) it returns false, unshrunk.
     */
    private fun needsWebpReencode(head: String): Boolean {
        if (head.contains("iVBOR")) return true                 // embedded PNG → re-encode
        val webpStart = head.indexOf("UklGR")                   // embedded WEBP (base64 of "RIFF")
        if (webpStart < 0) return false
        val quote = head.indexOf('"', webpStart)
        var b64 = head.substring(webpStart, if (quote < 0) head.length else quote)
        b64 = b64.substring(0, b64.length - b64.length % 4)     // trim to a whole base64 quantum
        val b = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { return false }
        if (b.size < 16 || fourccAt(b, 0) != "RIFF" || fourccAt(b, 8) != "WEBP") return false
        var off = 12
        while (off + 8 <= b.size) {
            when (fourccAt(b, off)) {
                "VP8L" -> return true    // lossless → re-encode
                "VP8 " -> return false   // lossy → already compact, skip
                else -> {
                    val size = (b[off + 4].toInt() and 0xFF) or ((b[off + 5].toInt() and 0xFF) shl 8) or
                        ((b[off + 6].toInt() and 0xFF) shl 16) or ((b[off + 7].toInt() and 0xFF) shl 24)
                    if (size < 0) return false
                    off += 8 + size + (size and 1)   // chunk payload is padded to an even length
                }
            }
        }
        return false
    }

    private fun fourccAt(b: ByteArray, off: Int): String? {
        if (off + 4 > b.size) return null
        return String(b, off, 4, Charsets.US_ASCII)
    }
}
