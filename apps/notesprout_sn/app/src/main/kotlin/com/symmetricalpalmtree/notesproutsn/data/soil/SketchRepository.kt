package com.symmetricalpalmtree.notesproutsn.data.soil

import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.PngHeader
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import java.util.UUID

/**
 * The `sketch` row's read and write rules (arc 43 / K3) — [DocumentRepository]'s shape, and
 * deliberately the **only** writer of a sketch row. Pure `suspend` over the two DAOs (no Android,
 * no `SoilDatabase`), so every rule below is provable off-device, which is the whole reason a
 * picture's rules were separated from the picture.
 *
 * Four rules carry it:
 *
 *  - **Blank means absent** ([DocumentRepository]'s rule, applied to pixels). An empty byte array
 *    is the wire form for "clear this page": [save] soft-deletes the live row and writes nothing
 *    else, which is exactly [clear]. A page nobody has drawn on has no row at all, so a Sketch
 *    notebook costs what its sketches cost.
 *  - **Minted on the first save, never on open.** [get] reads; it never creates. The first save
 *    upserts a whole row with a fresh id; every save after it rewrites the same row's pixels in
 *    place ([SoilDao.setBlob]), so `createdAt` keeps saying when the page was first drawn on.
 *  - **The header guard stands in front of every decode, on the way in and on the way out.** A PNG
 *    that is not exactly the page's size is refused on save ([SketchContract.SKETCH_BAD_PNG]) and,
 *    if one is somehow already stored — a foreign file, a page resized by a build that does not
 *    exist yet — **a refused row is soft-deleted, never overwritten**: the pixels are unreadable
 *    either way, and leaving them would hand them to the next reader as well.
 *  - **`updatedAt` is sacred** (the family rule): a save whose bytes are what is already stored
 *    writes nothing, so opening a sketch and closing it untouched cannot re-flag the notebook for
 *    backup. [SketchDao.sketchDigest] is asked first so an unequal length never pays for the blob
 *    read that would prove it.
 *
 * **[SketchContract.MAX_BYTES] is the one deliberate deviation from "never refuse"** in this app,
 * and it is written down in the contract as well as here. Everywhere else a size problem is
 * absorbed; a PNG above the 6 MiB SQLCipher cursor window can be *written* and then never *read
 * back*, so accepting one would trade a refusal the person can see for pixels that quietly stop
 * existing. The refusal throws with the contract's exact string, writes nothing, and leaves the
 * stored row exactly as it was. [SketchContract.WATCH_BYTES] is the earlier, silent line: logged,
 * never refused.
 *
 * **Pixels are never logged** — byte counts, ids and durations only, exactly as document text and
 * recognized text are not.
 */
class SketchRepository(
    private val dao: SketchDao,
    private val soil: SoilDao,
    /** Injected so the first save's id is assertable off-device; production is `UUID.randomUUID`. */
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** Does [pageId] carry a sketch? — blob-free, and the read every caller that is not about to
     *  draw should be asking. An empty blob is no sketch (blank means absent). */
    suspend fun has(pageId: String): Boolean = (dao.sketchDigest(pageId)?.blobLength ?: 0) > 0

    /**
     * [pageId]'s stored PNG, **after the header guard** — or null when there is none, when the row
     * is empty, or when what is stored is not a PNG of exactly [pageWidth] × [pageHeight].
     *
     * That last case soft-deletes the row on the way past. It is the only destructive read in the
     * class and it is the honest one: a mis-sized image cannot be composited over this page at all,
     * so the choice is between leaving it for every future reader to refuse again and dating it
     * out. Soft, like every delete in the family — the bytes are still in the file until the close
     * purge, and never overwritten, because overwriting is the one thing that would make the
     * refusal irreversible.
     */
    suspend fun get(pageId: String, pageWidth: Int, pageHeight: Int): ByteArray? {
        val row = dao.sketchFor(pageId) ?: return null
        val png = SketchRows.pngBytes(row) ?: return null
        if (!SketchRows.fitsPage(png, pageWidth, pageHeight)) {
            val size = PngHeader.size(png)
            Log.w(
                TAG,
                "sketch ${row.id} on $pageId refused: ${png.size} B, " +
                    "${size?.let { "${it.first}x${it.second}" } ?: "no PNG header"} " +
                    "for a ${pageWidth}x$pageHeight page — soft-deleting",
            )
            soil.softDelete(listOf(row.id), System.currentTimeMillis())
            return null
        }
        return png
    }

    /**
     * Store [png] as [pageId]'s sketch — the one write, and the one door the host's save
     * accumulator comes through.
     *
     * Empty [png] is the wire form for "clear" and routes to [clear]. Everything else is guarded
     * before anything is written: over [SketchContract.MAX_BYTES] and not a PNG of exactly
     * [pageWidth] × [pageHeight] both throw `IllegalStateException` carrying the contract's exact
     * string, and **nothing is written on either path** — the stored row is what it was. (The K4
     * accumulator refuses the first of those a chunk earlier, on the running total; this is the
     * second, structural line, and the only one a caller that is not the seam passes.)
     *
     * Past the guards: no live row mints one, a live row whose bytes are identical writes nothing,
     * and anything else rewrites the pixels in place with `createdAt` kept.
     */
    suspend fun save(
        pageId: String,
        png: ByteArray,
        pageWidth: Int,
        pageHeight: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        if (png.isEmpty()) {
            clear(pageId, now)
            return
        }
        if (png.size > SketchContract.MAX_BYTES) {
            // Nothing written, and the caller is told which refusal it was: a sketch above the
            // cursor window could never be read back, so a stored one is worse than a refused one.
            throw IllegalStateException(SketchContract.SKETCH_TOO_LARGE)
        }
        if (!PngHeader.matches(png, pageWidth, pageHeight)) {
            throw IllegalStateException(SketchContract.SKETCH_BAD_PNG)
        }
        if (png.size > SketchContract.WATCH_BYTES) {
            // Bytes only, never pixels. The quiet line that puts a page on its way to the cap into
            // a walk's log before it is a problem.
            Log.w(TAG, "sketch for $pageId is ${png.size} B (watch line ${SketchContract.WATCH_BYTES})")
        }
        val digest = dao.sketchDigest(pageId)
        if (digest == null) {
            soil.upsert(SketchRows.toRow(pageId, png, newId(), now))
            Slog.d(TAG) { "new sketch for $pageId (${png.size} B)" }
            return
        }
        // The digest first: an unequal length settles it without reading a megabyte of pixels back
        // out of the file only to compare them.
        if (digest.blobLength == png.size) {
            val stored = dao.sketchFor(pageId)?.blob
            if (stored != null && stored.contentEquals(png)) {
                Slog.d(TAG) { "sketch for $pageId unchanged (${png.size} B) — nothing written" }
                return
            }
        }
        soil.setBlob(digest.id, png, now)
        Slog.d(TAG) { "saved sketch ${digest.id} for $pageId (${png.size} B)" }
    }

    /** Clear [pageId]'s sketch: soft-delete the live row if there is one, and otherwise write
     *  nothing at all — a page with no sketch being cleared is not a change to the notebook. */
    suspend fun clear(pageId: String, now: Long = System.currentTimeMillis()) {
        val digest = dao.sketchDigest(pageId) ?: return
        soil.softDelete(listOf(digest.id), now)
        Slog.d(TAG) { "cleared the sketch of $pageId" }
    }

    private companion object {
        const val TAG = "SketchRepo"
    }
}
