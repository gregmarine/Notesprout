package com.symmetricalpalmtree.notesproutsn.data.soil

import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ImageHeader
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchGuideSettings
import java.util.UUID

/**
 * The guide rows' read and write rules (arc 51 "Guides" / J2) — [SketchRepository]'s shape, and the
 * **only** writer of a `guide_grid` or `guide_image` row. Pure `suspend` over the two DAOs, so
 * every rule is provable off-device.
 *
 * The rasters' rules, applied to tools:
 *
 *  - **Minted on first use, never on open.** [get] reads and never creates. The grid row is minted
 *    by the first [putSettings] whose kind is not off; the image row by the first [saveImage].
 *    After that each is rewritten in place (`setText` / `setBlob`), so `createdAt` keeps saying
 *    when the guide was first set.
 *  - **Off and Remove are soft deletes.** [SketchContract.GRID_OFF] soft-deletes the grid row; an
 *    empty image is the wire form for Remove and soft-deletes the image row. Nothing is ever hard
 *    deleted and nothing is overwritten to clear it.
 *  - **The header guard on the way in and out.** An image that is not a WebP of exactly the page's
 *    size is refused on save ([SketchContract.SKETCH_BAD_IMAGE], nothing written) and, if one is
 *    somehow stored, soft-deleted on read — never overwritten.
 *  - **`updatedAt` is sacred.** Settings equal to what is stored write nothing; identical image
 *    bytes write nothing, the blob-free digest asked first so an unequal length never pays for
 *    the blob read.
 *  - **[SketchContract.MAX_BYTES] is the image's hard cap** exactly as it is each raster's
 *    ([SketchContract.SKETCH_TOO_LARGE], nothing written); [SketchContract.WATCH_BYTES] is logged.
 *
 * **The order of a new image.** The host never knows the face's defaults, so a freshly minted image
 * row carries `GuideImage(opacity = 0, visible = true)` and the face's [putSettings], which follows
 * its image push, writes the real opacity a moment later. Settings pushed for a page with no image
 * row are ignored for the image — there is no row to hold them.
 *
 * **Pixels are never logged** — byte counts, ids and the five setting ints only.
 */
class GuideRepository(
    private val dao: GuideDao,
    private val soil: SoilDao,
    /** Injected so a minted id is assertable off-device; production is `UUID.randomUUID`. */
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** What [get] answers: either guide may be absent. [imageBytes] is null exactly when [image] is. */
    class Guides(val grid: GuideGrid?, val image: GuideImage?, val imageBytes: ByteArray?) {
        /** The seam's settings for this page — [GuideRows.toSettings]. */
        val settings: SketchGuideSettings get() = GuideRows.toSettings(grid, image)
    }

    /**
     * [pageId]'s guides. The image is read whole (the one page-sized read here) and passed through
     * the header guard; a stored image failing it is soft-deleted and answered absent. An image row
     * whose settings text is damaged keeps its pixels and answers `GuideImage(0, true)` — the
     * picture is good, and the face's next push rewrites the settings in place.
     */
    suspend fun get(pageId: String, pageWidth: Int, pageHeight: Int): Guides {
        val grid = dao.gridFor(pageId)?.let { GuideRows.gridOf(it) }
        val row = dao.imageFor(pageId)
        val bytes = row?.let { GuideRows.imageBytes(it) }
        if (row == null || bytes == null) return Guides(grid, null, null)
        if (!GuideRows.fitsPage(bytes, pageWidth, pageHeight)) {
            val size = ImageHeader.size(bytes)
            Log.w(
                TAG,
                "guide image ${row.id} on $pageId refused: ${bytes.size} B, " +
                    "${size?.let { "${it.first}x${it.second}" } ?: "no WebP header"} " +
                    "for a ${pageWidth}x$pageHeight page — soft-deleting",
            )
            soil.softDelete(listOf(row.id), System.currentTimeMillis())
            return Guides(grid, null, null)
        }
        return Guides(grid, GuideRows.imageOf(row) ?: NEW_IMAGE, bytes)
    }

    /**
     * Keep [settings] for [pageId]: the grid row follows the grid fields (off soft-deletes it, any
     * other kind mints or rewrites it), and the image fields go to the live image row's `text` if
     * there is one — otherwise they are ignored. Each half writes only if it changes something.
     */
    suspend fun putSettings(pageId: String, settings: SketchGuideSettings, now: Long = System.currentTimeMillis()) {
        val wanted = GuideRows.gridFrom(settings)
        val gridRow = dao.gridFor(pageId)
        when {
            wanted == null -> if (gridRow != null) clearGrid(pageId, now)
            gridRow == null -> {
                soil.upsert(GuideRows.toGridRow(pageId, wanted, newId(), now))
                Slog.d(TAG) { "new grid for $pageId" }
            }
            GuideRows.gridOf(gridRow) != wanted -> soil.setText(gridRow.id, GuideRows.encodeGrid(wanted), now)
        }
        val digest = dao.imageDigest(pageId) ?: return
        if ((digest.blobLength ?: 0) <= 0) return
        val image = GuideRows.imageFrom(settings)
        if (GuideRows.decodeImage(digest.text) != image) soil.setText(digest.id, GuideRows.encodeImage(image), now)
    }

    /**
     * Store [bytes] as [pageId]'s reference image. Empty is Remove ([clearImage]). Over
     * [SketchContract.MAX_BYTES] or not a WebP of exactly [pageWidth] × [pageHeight] throws the
     * contract's string and writes nothing. No live row mints one (settings `GuideImage(0, true)`
     * — see the class doc); identical bytes write nothing; anything else rewrites the pixels in
     * place, the settings text kept.
     */
    suspend fun saveImage(
        pageId: String,
        bytes: ByteArray,
        pageWidth: Int,
        pageHeight: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        if (bytes.isEmpty()) {
            clearImage(pageId, now)
            return
        }
        if (bytes.size > SketchContract.MAX_BYTES) throw IllegalStateException(SketchContract.SKETCH_TOO_LARGE)
        if (!GuideRows.fitsPage(bytes, pageWidth, pageHeight)) {
            throw IllegalStateException(SketchContract.SKETCH_BAD_IMAGE)
        }
        if (bytes.size > SketchContract.WATCH_BYTES) {
            Log.w(TAG, "the guide image of $pageId is ${bytes.size} B (watch line ${SketchContract.WATCH_BYTES})")
        }
        val digest = dao.imageDigest(pageId)
        if (digest == null) {
            soil.upsert(GuideRows.toImageRow(pageId, NEW_IMAGE, bytes, newId(), now))
            Slog.d(TAG) { "new guide image for $pageId (${bytes.size} B)" }
            return
        }
        if (digest.blobLength == bytes.size) {
            val stored = dao.imageFor(pageId)?.blob
            if (stored != null && stored.contentEquals(bytes)) {
                Slog.d(TAG) { "guide image of $pageId unchanged (${bytes.size} B) — nothing written" }
                return
            }
        }
        soil.setBlob(digest.id, bytes, now)
        Slog.d(TAG) { "saved guide image ${digest.id} for $pageId (${bytes.size} B)" }
    }

    /** Remove [pageId]'s reference image — soft-delete its live row, or write nothing if none. */
    suspend fun clearImage(pageId: String, now: Long = System.currentTimeMillis()) {
        val digest = dao.imageDigest(pageId) ?: return
        soil.softDelete(listOf(digest.id), now)
        Slog.d(TAG) { "cleared the guide image of $pageId" }
    }

    /** Turn [pageId]'s grid off — soft-delete its live row, or write nothing if none. */
    suspend fun clearGrid(pageId: String, now: Long = System.currentTimeMillis()) {
        val row = dao.gridFor(pageId) ?: return
        soil.softDelete(listOf(row.id), now)
        Slog.d(TAG) { "cleared the grid of $pageId" }
    }

    private companion object {
        const val TAG = "GuideRepo"

        /** A freshly minted image row's settings — the face's own follow the image push. */
        val NEW_IMAGE = GuideImage(opacity = 0, visible = true)
    }
}
