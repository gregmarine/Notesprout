package com.symmetricalpalmtree.notesproutsn.data.soil

import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ImageHeader
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import java.util.UUID

/**
 * The sketch rows' read and write rules (arc 43 / K3, per-layer since arc 45 / G2) —
 * [DocumentRepository]'s shape, and deliberately the **only** writer of a sketch row. Pure
 * `suspend` over the two DAOs (no Android, no `SoilDatabase`), so every rule below is provable
 * off-device, which is the whole reason a picture's rules were separated from the picture.
 *
 * **Every rule is per raster.** A page carries a **graphite** row and an **ink** row (decision 3),
 * each named by the seam's layer number and each entirely independent of the other: saving one
 * never reads, writes, dates or clears the other, and a page may live with either, both or neither.
 * [has] is the one deliberately un-layered answer — "is anything drawn on this page" — because that
 * is the question its callers ask.
 *
 * Four rules carry it, each now read "for this layer":
 *
 *  - **Blank means absent** ([DocumentRepository]'s rule, applied to pixels). An empty byte array
 *    is the wire form for "clear this layer": [save] soft-deletes that layer's live row and writes
 *    nothing else, which is exactly [clear]. A page nobody has drawn on has no row at all, so a
 *    Sketch notebook costs what its sketches cost.
 *  - **Minted on the first save, never on open.** [get] reads; it never creates. A layer's first
 *    save upserts a whole row with a fresh id; every save after it rewrites the same row's pixels in
 *    place ([SoilDao.setBlob]), so `createdAt` keeps saying when that raster was first drawn on.
 *  - **The header guard stands in front of every decode, on the way in and on the way out.** An
 *    image that is not a WebP of exactly the page's size is refused on save
 *    ([SketchContract.SKETCH_BAD_IMAGE]) and, if one is somehow already stored — a foreign file, a
 *    page resized by a build that does not exist yet, an arc-43 PNG left on a device (decision 4:
 *    no legacy, no sniffing) — **a refused row is soft-deleted, never overwritten**: the pixels are
 *    unreadable either way, and leaving them would hand them to the next reader as well.
 *  - **`updatedAt` is sacred** (the family rule): a save whose bytes are what is already stored
 *    writes nothing, so opening a sketch and closing it untouched cannot re-flag the notebook for
 *    backup. [SketchDao.sketchDigest] is asked first so an unequal length never pays for the blob
 *    read that would prove it.
 *
 * **[SketchContract.MAX_BYTES] is the one deliberate deviation from "never refuse"** in this app,
 * and it is written down in the contract as well as here. Everywhere else a size problem is
 * absorbed; an image above the 6 MiB SQLCipher cursor window can be *written* and then never *read
 * back*, so accepting one would trade a refusal the person can see for pixels that quietly stop
 * existing. The refusal throws with the contract's exact string, writes nothing, and leaves the
 * stored row exactly as it was. The cap is **per row** (decision 3), which is what makes a page of
 * two rasters cost two windows rather than share one. [SketchContract.WATCH_BYTES] is the earlier,
 * silent line: logged with the layer it belongs to, never refused.
 *
 * **Pixels are never logged** — byte counts, ids, layer names and durations only, exactly as
 * document text and recognized text are not.
 */
class SketchRepository(
    private val dao: SketchDao,
    private val soil: SoilDao,
    /** Injected so the first save's id is assertable off-device; production is `UUID.randomUUID`. */
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** Does [pageId] carry a sketch at all? — **either raster**, blob-free, and the read every
     *  caller that is not about to draw should be asking. An empty blob is no raster (blank means
     *  absent), and a page with only ink is a drawn page. */
    suspend fun has(pageId: String): Boolean =
        SketchContract.LAYERS.any { (digest(pageId, it)?.blobLength ?: 0) > 0 }

    /**
     * [pageId]'s stored image for [layer], **after the header guard** — or null when there is none,
     * when the row is empty, or when what is stored is not a WebP of exactly [pageWidth] ×
     * [pageHeight].
     *
     * That last case soft-deletes **that layer's** row on the way past, and only that one. It is
     * the only destructive read in the class and it is the honest one: a mis-sized image cannot be
     * composited over this page at all, so the choice is between leaving it for every future reader
     * to refuse again and dating it out. Soft, like every delete in the family — the bytes are
     * still in the file until the close purge, and never overwritten, because overwriting is the
     * one thing that would make the refusal irreversible.
     *
     * A row of the dead arc-43 type is not reached by this read at all ([SketchDao] never names it),
     * so it is neither returned nor soft-deleted: no legacy means no opinion.
     */
    suspend fun get(pageId: String, layer: Int, pageWidth: Int, pageHeight: Int): ByteArray? {
        val row = dao.sketchFor(pageId, SketchRows.typeFor(layer)) ?: return null
        val bytes = SketchRows.imageBytes(row) ?: return null
        if (!SketchRows.fitsPage(bytes, pageWidth, pageHeight)) {
            val size = ImageHeader.size(bytes)
            Log.w(
                TAG,
                "${name(layer)} ${row.id} on $pageId refused: ${bytes.size} B, " +
                    "${size?.let { "${it.first}x${it.second}" } ?: "no WebP header"} " +
                    "for a ${pageWidth}x$pageHeight page — soft-deleting",
            )
            soil.softDelete(listOf(row.id), System.currentTimeMillis())
            return null
        }
        return bytes
    }

    /**
     * Store [bytes] as [pageId]'s [layer] raster — the one write, and the one door the host's save
     * accumulator comes through.
     *
     * Empty [bytes] is the wire form for "clear this layer" and routes to [clear]; the other layer
     * is untouched either way. Everything else is guarded before anything is written: over
     * [SketchContract.MAX_BYTES] and not a WebP of exactly [pageWidth] × [pageHeight] both throw
     * `IllegalStateException` carrying the contract's exact string, and **nothing is written on
     * either path** — the stored row is what it was. (The K4 accumulator refuses the first of those
     * a chunk earlier, on that layer's running total; this is the second, structural line, and the
     * only one a caller that is not the seam passes.)
     *
     * Past the guards: no live row for this layer mints one, a live row whose bytes are identical
     * writes nothing, and anything else rewrites that row's pixels in place with `createdAt` kept.
     */
    suspend fun save(
        pageId: String,
        layer: Int,
        bytes: ByteArray,
        pageWidth: Int,
        pageHeight: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        val type = SketchRows.typeFor(layer)
        if (bytes.isEmpty()) {
            clear(pageId, layer, now)
            return
        }
        if (bytes.size > SketchContract.MAX_BYTES) {
            // Nothing written, and the caller is told which refusal it was: a raster above the
            // cursor window could never be read back, so a stored one is worse than a refused one.
            throw IllegalStateException(SketchContract.SKETCH_TOO_LARGE)
        }
        if (!ImageHeader.matches(bytes, pageWidth, pageHeight)) {
            throw IllegalStateException(SketchContract.SKETCH_BAD_IMAGE)
        }
        if (bytes.size > SketchContract.WATCH_BYTES) {
            // Bytes and the layer, never pixels. The quiet line that puts a raster on its way to
            // the cap into a walk's log before it is a problem — and says which of the two it is.
            Log.w(
                TAG,
                "the ${name(layer)} of $pageId is ${bytes.size} B " +
                    "(watch line ${SketchContract.WATCH_BYTES})",
            )
        }
        val digest = dao.sketchDigest(pageId, type)
        if (digest == null) {
            soil.upsert(SketchRows.toRow(pageId, layer, bytes, newId(), now))
            Slog.d(TAG) { "new ${name(layer)} for $pageId (${bytes.size} B)" }
            return
        }
        // The digest first: an unequal length settles it without reading a megabyte of pixels back
        // out of the file only to compare them.
        if (digest.blobLength == bytes.size) {
            val stored = dao.sketchFor(pageId, type)?.blob
            if (stored != null && stored.contentEquals(bytes)) {
                Slog.d(TAG) { "${name(layer)} of $pageId unchanged (${bytes.size} B) — nothing written" }
                return
            }
        }
        soil.setBlob(digest.id, bytes, now)
        Slog.d(TAG) { "saved ${name(layer)} ${digest.id} for $pageId (${bytes.size} B)" }
    }

    /** Clear [pageId]'s [layer] raster: soft-delete that layer's live row if there is one, and
     *  otherwise write nothing at all — a layer with no row being cleared is not a change to the
     *  notebook. The other raster is never touched. */
    suspend fun clear(pageId: String, layer: Int, now: Long = System.currentTimeMillis()) {
        val digest = digest(pageId, layer) ?: return
        soil.softDelete(listOf(digest.id), now)
        Slog.d(TAG) { "cleared the ${name(layer)} of $pageId" }
    }

    /** The blob-free row for one layer, or null. */
    private suspend fun digest(pageId: String, layer: Int): SketchDigest? =
        dao.sketchDigest(pageId, SketchRows.typeFor(layer))

    /** What a layer is called in a log line — never a number on its own, which reads as an index
     *  into something the reader of the log cannot see. */
    private fun name(layer: Int): String =
        if (layer == SketchContract.LAYER_INK) "sketch ink" else "sketch graphite"

    private companion object {
        const val TAG = "SketchRepo"
    }
}
