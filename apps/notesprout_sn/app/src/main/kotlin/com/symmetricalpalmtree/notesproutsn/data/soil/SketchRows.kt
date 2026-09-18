package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.extension.ImageHeader
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract

/**
 * The border between the picture on a page and a row in the `.soil`, crossed in both directions
 * (arc 43 / K3, made per-layer at arc 45 / G2) — the port of Paintsprout's `RasterRows`,
 * [StrokeRows]' sibling and here for the same reason that one is.
 *
 * Everything in this file is pure: bytes in, bytes out, **no `Bitmap` anywhere**. That is what lets
 * the part of a sketch page which can actually be *proved* be proved on a laptop with no tablet in
 * the room — that the image which goes down comes back up, and that an image which does not belong
 * to this page is refused before anything tries to open it. The encoding and decoding themselves
 * need Android and live on the extension's side of the seam; the rules about them live here.
 *
 * Four decisions carry it — three from Paintsprout, in SN's words, and one the user's:
 *
 * **Two rows, named by layer** (arc 45, decision 3). A sketch is a **graphite** raster and an **ink**
 * raster, and which is which is the *row's type* rather than a column or a bit: [typeFor] is the one
 * place the two names are chosen, so nothing downstream can invent a third. The layer numbers are
 * the seam's ([SketchContract.LAYER_GRAPHITE] / [SketchContract.LAYER_INK]) rather than an enum of
 * this file's, because a number that crosses Binder and a number that picks a row must be the same
 * number or there is a mapping to get wrong.
 *
 * **One row per page per layer, replaced in place.** The id of an existing picture is passed in
 * rather than minted here, because a save is a *new picture of the same page*, not a new object. A
 * row per save would make a notebook's size a function of how long the person worked on it rather
 * than of how much they drew, and an evening's sketching would weigh more than the drawing does.
 * Only [SketchRepository] can ask the table what is already there, so only it decides the id.
 *
 * **The size guard reads the header, never the image.** [fitsPage] answers from the first 30 bytes
 * through [ImageHeader], so a row whose picture is the wrong shape for its page is refused before
 * anything has allocated anything. A page image at the Nomad's size is about 9.5 MB decoded (19.7
 * on the Manta), and a corrupt or foreign header claiming a larger one is a way to take the process
 * down while the person is drawing. The parse is [ImageHeader]'s and not this file's on purpose: it
 * is the same question on both sides of the seam, and two parsers that must agree are one parser.
 * The container is a **lossless WebP with alpha** since G2 — `RIFF`/`WEBP` and a first chunk that is
 * either `VP8L` (the simple lossless form) or `VP8X` (the extended one); a PNG is simply not a WebP
 * and fails the guard, which is all "no legacy" (decision 4) needs to mean here.
 *
 * **The kind lives in one place.** Which notebooks keep pixels is [com.symmetricalpalmtree.notesproutsn.data.index.NotebookKind]'s
 * to say, not this file's — two places that both know which bit means sketch is one place that can
 * be changed and one that will not be, and the failure that follows is a notebook created as a
 * sketchbook which opens as paper: an empty page where a drawing was.
 */
object SketchRows {

    /**
     * The row type the raster [layer] names — [SketchContract.LAYER_GRAPHITE] →
     * [SoilSchema.TYPE_SKETCH_GRAPHITE], [SketchContract.LAYER_INK] → [SoilSchema.TYPE_SKETCH_INK],
     * anything else an `IllegalArgumentException`.
     *
     * Unknown is refused rather than defaulted to graphite: a layer number the host does not know
     * arrived from somewhere, and quietly filing a stranger's pixels under the pencil's row is how
     * a drawing gets overwritten by something nobody drew.
     */
    fun typeFor(layer: Int): String = when (layer) {
        SketchContract.LAYER_GRAPHITE -> SoilSchema.TYPE_SKETCH_GRAPHITE
        SketchContract.LAYER_INK -> SoilSchema.TYPE_SKETCH_INK
        else -> throw IllegalArgumentException("Unknown layer $layer")
    }

    /**
     * The page's picture of one raster, as a row of the notebook.
     *
     * [id] is the caller's: the live row's, so the picture is replaced where it already sits, or a
     * fresh UUID for a layer being saved for the first time. It is not decided here because only
     * [SketchRepository] can ask the table what is already there, and a border that guessed would
     * either mint a second row per save or reuse an id it had not checked.
     *
     * `"order"` is [SoilSchema.SKETCH_ORDER] for **both** layers — out of the marks' stacking space
     * entirely, since neither image is one of the marks, and equal because neither is above the
     * other (the flatten is order-independent; see [SoilSchema.TYPE_SKETCH_GRAPHITE]).
     */
    fun toRow(pageId: String, layer: Int, bytes: ByteArray, id: String, now: Long): SoilObjectEntity =
        SoilObjectEntity(
            id = id,
            parentId = pageId,
            type = typeFor(layer),
            order = SoilSchema.SKETCH_ORDER,
            createdAt = now,
            updatedAt = now,
            blob = bytes,
        )

    /**
     * The WebP a row is carrying, or null when the row is not one of ours or is carrying nothing.
     *
     * **Either live raster type is ours; the dead name is not** (decision 4). A row of any other
     * type is refused rather than trusted for its blob: every other type in this file puts something
     * else in that column — a stroke's geometry, a template's WEBP, a cover — and handing one of
     * those to an image decoder is how a page comes back as something nobody drew. The arc-43 row
     * name [SoilSchema.TYPE_SKETCH_DEAD] is refused with them, and for a sharper reason: its blob
     * *is* an image, but a PNG, which no reader on either side of the seam will accept any more.
     * An empty blob is the same answer as no blob: blank means absent.
     */
    fun imageBytes(row: SoilObjectEntity): ByteArray? {
        if (row.type != SoilSchema.TYPE_SKETCH_GRAPHITE && row.type != SoilSchema.TYPE_SKETCH_INK) {
            return null
        }
        val blob = row.blob ?: return null
        return if (blob.isEmpty()) null else blob
    }

    /**
     * Is this picture the picture of *this* page? The bounded-decode guard, and the one rule that
     * stands between a damaged row and the process.
     *
     * Exactly, in both directions — not "no larger than", not "close enough". A page image registers
     * with the page one-to-one, top-left to top-left, so an image of any other size is not this page
     * drawn at the wrong scale: it is some other page, or a blob that is not a page at all. Nothing
     * good can be done with it.
     *
     * A page with no recorded size (zero) fails the guard too: there is nothing to check against,
     * and an unbounded decode is exactly what this exists to prevent.
     */
    fun fitsPage(bytes: ByteArray, pageWidth: Int, pageHeight: Int): Boolean =
        pageWidth > 0 && pageHeight > 0 && ImageHeader.matches(bytes, pageWidth, pageHeight)
}
