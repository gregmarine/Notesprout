package com.notesprout.android.data

import android.util.Base64
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
 * Three independent migrations share one pass so a single VACUUM reclaims them all:
 *
 * 1. **Legacy-`ts` strip.** Every [StrokePoint] used to carry a per-point `ts` that was never read
 *    (~40% of a stroke's JSON). New writes no longer emit it; this rewrites the rows that still have
 *    it. See [StrokePoint.timestamp] / [LiveStroke.toStrokeData].
 * 2. **Image → WEBP q100.** Page snapshots, embedded page templates, covers, and the index's
 *    template/cover images are re-encoded to WEBP q100 (see [ImageCodec]). This catches both legacy
 *    **PNG** and the earlier, mistaken **lossless-WEBP** blobs (Android's Skia lossless encoder
 *    bloated to 2–6× PNG), converting both to the compact q100 form. Already-q100 rows are skipped.
 * 3. **Dead heading/text strokes strip.** Recognized headings and converted text objects used to
 *    embed a full copy of their original handwriting strokes that was never read again (headings
 *    once supported un-heading; text never reverted). Both now drop those strokes on conversion;
 *    this rewrites the legacy rows that still carry them. Unrecognized fallbacks — where the strokes
 *    ARE the visual — keep theirs. See [HeadingObject] / [TextObject].
 *
 * Both are safe to run opportunistically:
 * - **Self-limiting.** ts rows are found by a `LIKE '%"ts":%'` scan; image rows are decided from a
 *   cheap 60-byte header ([needsWebpReencode]) — PNG or lossless-WEBP get re-encoded, lossy-WEBP is
 *   skipped. Once converted a notebook does no heavy work beyond those scans, so this can run on
 *   every seal.
 * - **`updatedAt` preserved.** Rewrites go through [NotebookDao.rewriteObjectDataKeepingTimestamp]
 *   (and [com.notesprout.android.data.index.ObjectDao.rewriteObjectData] for the index) so no row's
 *   `updatedAt` moves. Neither change is a content edit; bumping it would make
 *   [NotebookDao.getMaxContentUpdatedAt] exceed a page snapshot's timestamp and invalidate every
 *   page's fast-load snapshot — the opposite of what snapshots are for — and needlessly re-flag the
 *   file for backup.
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
    data class Result(val tsRows: Int, val imageRows: Int, val deadStrokeRows: Int = 0) {
        val changed: Boolean get() = tsRows > 0 || imageRows > 0 || deadStrokeRows > 0
    }

    /**
     * Strip legacy `ts` and re-encode PNG/lossless-WEBP images in a single `.soil`, then VACUUM once
     * if anything changed. Runs its own DB work — call from `Dispatchers.IO`.
     */
    suspend fun compact(db: SoilDatabase): Result {
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

        if (tsRows.isNotEmpty() || imageRows > 0 || deadStrokeRows > 0) {
            val raw: SupportSQLiteDatabase = db.openHelper.writableDatabase
            raw.execSQL("VACUUM")
        }
        return Result(tsRows.size, imageRows, deadStrokeRows)
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
     * Transcode the image inside a `.soil` `page`/`template`/`cover` row to WEBP q100, keeping the
     * rest of the JSON intact. Returns null (skip) when the row holds no image or fails to decode.
     * Reuses each type's own codec so the rewritten JSON is byte-shape-compatible.
     */
    private fun transcodeSoilRow(type: String, data: String): String? = when (type) {
        "page" -> {
            val pd = PageData.fromJson(data)
            val snap = pd.snapshot ?: return null
            val webp = ImageCodec.transcodeToWebpBase64(snap) ?: return null
            pd.copy(snapshot = webp).toJson()
        }
        "template" -> {
            val td = TemplateData.fromJson(data) ?: return null
            val webp = ImageCodec.transcodeToWebpBase64(td.image) ?: return null
            td.copy(image = webp).toJson()
        }
        "cover" -> {
            val co = CoverObject.fromJson(data) ?: return null
            val webp = ImageCodec.transcodeToWebpBase64(co.image) ?: return null
            Json.encodeToString(CoverObject(image = webp))
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
