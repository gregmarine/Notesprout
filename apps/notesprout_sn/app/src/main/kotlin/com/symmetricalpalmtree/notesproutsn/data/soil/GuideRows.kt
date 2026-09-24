package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.extension.ImageHeader
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchGuideSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** [NotebookMeta]'s codec settings: a field this build does not know is skipped, a default is
 *  written out so the row says what it means on its own. */
private val codec = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * A page's grid, as the `guide_grid` row's `text` carries it (arc 51 / J2). [kind] is
 * [SketchContract.GRID_LINES] or [SketchContract.GRID_DOTS] — "off" is no row at all, never a
 * stored kind — and [count] the cells across the page's width, the face's own number.
 */
@Serializable
data class GuideGrid(val kind: Int, val count: Int, val visible: Boolean = true)

/** The reference image's settings, as the `guide_image` row's `text` carries them (arc 51 / J2):
 *  [opacity] a percent, 0..[SketchContract.MAX_OPACITY_PERCENT]. The pixels are the row's blob. */
@Serializable
data class GuideImage(val opacity: Int, val visible: Boolean = true)

/**
 * The border between a page's **guides** and the `.soil` (arc 51 "Guides" / J2) — [SketchRows]'
 * sibling for the two rows that are tools rather than marks. Pure, like it: text and bytes in and
 * out, no `Bitmap`, so every rule here is proved on a laptop.
 *
 * **Never a stored row that throws.** A row is read by [gridOf] / [imageOf], which answer null for
 * a row of any other type, for text that does not parse, and for values outside what the seam's
 * [SketchGuideSettings] would accept — so a foreign or damaged row reads as "no guide" rather than
 * as a crash on a Binder thread, and the next save rewrites it in place.
 *
 * **Never routed through [SketchRows.typeFor] or the layer list.** Those drive the flatten, the
 * export, the cover and the raster saves; a guide in any of them would be a guide drawn into the
 * picture, which is the one thing the user's decision says it must never be.
 *
 * The size guard is [SketchRows.fitsPage]'s question asked of [ImageHeader]: the reference image is
 * a *lossy* WebP with alpha, which is `VP8X`-headed, and the parse already reads that form — so
 * exactly the rasters' guard applies, unchanged.
 */
object GuideRows {

    /** The grid as a row of the notebook; [id] is the caller's (the live row's, or fresh). */
    fun toGridRow(pageId: String, grid: GuideGrid, id: String, now: Long): SoilObjectEntity =
        SoilObjectEntity(
            id = id,
            parentId = pageId,
            type = SoilSchema.TYPE_GUIDE_GRID,
            order = SoilSchema.SKETCH_ORDER,
            createdAt = now,
            updatedAt = now,
            text = encodeGrid(grid),
        )

    /** The reference image as a row of the notebook: its settings in `text`, its WebP in `blob`. */
    fun toImageRow(pageId: String, image: GuideImage, bytes: ByteArray, id: String, now: Long): SoilObjectEntity =
        SoilObjectEntity(
            id = id,
            parentId = pageId,
            type = SoilSchema.TYPE_GUIDE_IMAGE,
            order = SoilSchema.SKETCH_ORDER,
            createdAt = now,
            updatedAt = now,
            text = encodeImage(image),
            blob = bytes,
        )

    fun encodeGrid(grid: GuideGrid): String = codec.encodeToString(GuideGrid.serializer(), grid)

    fun encodeImage(image: GuideImage): String = codec.encodeToString(GuideImage.serializer(), image)

    /** The grid a row carries, or null — another type, or [decodeGrid]'s null. */
    fun gridOf(row: SoilObjectEntity): GuideGrid? =
        if (row.type == SoilSchema.TYPE_GUIDE_GRID) decodeGrid(row.text) else null

    /** The image settings a row carries, or null — another type, or [decodeImage]'s null. */
    fun imageOf(row: SoilObjectEntity): GuideImage? =
        if (row.type == SoilSchema.TYPE_GUIDE_IMAGE) decodeImage(row.text) else null

    /** A grid row's `text` read back, or null — no text, unparsable text, or a kind / count the
     *  seam would refuse (off is no row, never a stored kind). */
    fun decodeGrid(text: String?): GuideGrid? {
        if (text == null) return null
        val grid = decode { codec.decodeFromString(GuideGrid.serializer(), text) } ?: return null
        val kindOk = grid.kind == SketchContract.GRID_LINES || grid.kind == SketchContract.GRID_DOTS
        val countOk = grid.count in 1..SketchContract.MAX_TOOL_SETTING_INDEX
        return if (kindOk && countOk) grid else null
    }

    /** An image row's `text` read back, or null — no text, unparsable text, or an opacity outside
     *  the percent. Blob-free on purpose: [GuideDao.imageDigest] carries the text alone. */
    fun decodeImage(text: String?): GuideImage? {
        if (text == null) return null
        val image = decode { codec.decodeFromString(GuideImage.serializer(), text) } ?: return null
        return if (image.opacity in 0..SketchContract.MAX_OPACITY_PERCENT) image else null
    }

    /** The WebP an image row carries, or null — only a `guide_image` row's blob is a picture of the
     *  page, and an empty one is none (blank means absent). */
    fun imageBytes(row: SoilObjectEntity): ByteArray? {
        if (row.type != SoilSchema.TYPE_GUIDE_IMAGE) return null
        val blob = row.blob ?: return null
        return if (blob.isEmpty()) null else blob
    }

    /** Is this image exactly this page's size? [SketchRows.fitsPage]'s rule, for the same reason —
     *  a zero page size fails, and nothing is decoded to answer. */
    fun fitsPage(bytes: ByteArray, pageWidth: Int, pageHeight: Int): Boolean =
        pageWidth > 0 && pageHeight > 0 && ImageHeader.matches(bytes, pageWidth, pageHeight)

    /**
     * The seam's settings for a page carrying [grid] and [image] — either may be absent.
     * [SketchGuideSettings.NONE] when neither is: no grid, and an image that is not there. An
     * absent image answers opacity 0 / visible; the state's byte count is what says there is none.
     */
    fun toSettings(grid: GuideGrid?, image: GuideImage?): SketchGuideSettings {
        if (grid == null && image == null) return SketchGuideSettings.NONE
        return SketchGuideSettings(
            gridKind = grid?.kind ?: SketchContract.GRID_OFF,
            gridCount = grid?.count ?: 0,
            gridVisible = grid?.visible ?: true,
            imageOpacity = image?.opacity ?: 0,
            imageVisible = image?.visible ?: true,
        )
    }

    /** The grid [settings] ask for, or null for [SketchContract.GRID_OFF] — no row. */
    fun gridFrom(settings: SketchGuideSettings): GuideGrid? =
        if (settings.hasGrid) GuideGrid(settings.gridKind, settings.gridCount, settings.gridVisible) else null

    /** The image settings [settings] carry — meaningful only to a page with a live image row. */
    fun imageFrom(settings: SketchGuideSettings): GuideImage =
        GuideImage(settings.imageOpacity, settings.imageVisible)

    /** Any decode failure is "no value" — a stored row never throws. */
    private inline fun <T> decode(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            null
        }
}
