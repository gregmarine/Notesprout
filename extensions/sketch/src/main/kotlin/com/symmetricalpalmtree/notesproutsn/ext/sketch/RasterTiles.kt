package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.RasterLayer

/**
 * A square of a raster page's before-image, and where on the page it belongs (arc 43 / K5 — ported
 * from Paintsprout Onyx's `sketchbook/RasterTiles.kt`, which is where the design was proved).
 *
 * [pixels] is row-major ARGB, [width] to a row, exactly what `Bitmap.getPixels` writes and what
 * g-paper's `RasterPatch` takes back — so the array crosses into the engine as it stands, with no
 * format in between and no copy. The rect is carried as four numbers rather than as an
 * `android.graphics.Rect` so that everything about an undo entry except the swap itself can be
 * proved on a laptop with no tablet in the room.
 *
 * The array is **not** a snapshot the engine may keep: `swapPageRaster(layer, …)` writes that
 * raster's old pixels back into it, which is the whole trick that lets one entry serve undo and
 * redo. So nothing else may hold a second reference to it and read it later expecting the
 * before-image to still be there. A tile carries no layer of its own, which is why the entry does:
 * pixels read off one raster can only ever be swapped back into that one.
 */
class RasterTile(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    /** What this tile costs to keep, for the stack's byte budget. Four bytes a pixel, no allowance. */
    val bytes: Long get() = pixels.size * 4L
}

/** One square of the page's fixed grid, by column and row. Nothing but a name for a cell. */
data class CellKey(val col: Int, val row: Int)

/** Where a cell actually sits on the page, clipped — so the last column and row are narrower. */
data class CellRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * The grid an undo entry's before-image is kept on, and the builder that fills one in.
 *
 * ## Why a grid, and not the rectangles the engine reported
 *
 * g-paper reports a raster change once per batch, and an eraser sweep is dozens of batches a second
 * whose rectangles overlap heavily along the line the hand is travelling. Storing a tile per batch
 * — the obvious reading of "keep the before-image" — would let a minute of slow scrubbing pile up
 * tens of megabytes of near-identical pixels *inside a single entry*, and blow the stack's budget
 * from the inside, where evicting old entries cannot help.
 *
 * So the page is divided once, into [CELL]-pixel squares aligned to its own top-left corner, and
 * the first time a contact touches a cell that cell is read; every later batch that crosses it
 * reads nothing. Three things follow, and all three are the reason:
 *
 * - **An entry is bounded by the page.** The Nomad's page is 1404 × 1685 — about 9.5 MB whatever
 *   the hand does for however long — so one contact can never be the thing that overflows the
 *   48 MB history. (The Manta's 1860 × 2480 is ~18.4 MB, still one entry.)
 * - **The tiles are disjoint**, so they can be swapped back in any order and the replayer has no
 *   read order to remember.
 * - **A small mark stays small.** A hairline stroke touches one to four cells — 16 to 64 KB — which
 *   is hundreds of marks inside the budget.
 *
 * Sixty-four is the size at which both ends of that are true at once. Much larger and a dot in the
 * corner of a cell costs a quarter of a megabyte; much smaller and a page-wide sweep is tens of
 * thousands of separate reads and array allocations while the artist is rubbing.
 *
 * Everything here is pure arithmetic and `IntArray`s: the reading of actual pixels arrives as a
 * lambda from the screen, which is the only place that has a paper view to ask.
 */
object RasterTiles {

    /** The side of one cell, in page pixels. See the class note for why sixty-four. */
    const val CELL = 64

    /**
     * The cells a page-space rect touches, clipped to the page — empty when the rect is off the
     * page entirely, which is the honest answer rather than an error because a rect that misses is
     * simply a change there is nothing of ours to remember.
     *
     * Right and bottom are exclusive, as the engine's rects are.
     */
    fun cellsTouching(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        pageWidth: Int,
        pageHeight: Int,
    ): List<CellKey> {
        if (pageWidth <= 0 || pageHeight <= 0) return emptyList()
        val l = maxOf(left, 0)
        val t = maxOf(top, 0)
        val r = minOf(right, pageWidth)
        val b = minOf(bottom, pageHeight)
        if (l >= r || t >= b) return emptyList()
        val firstCol = l / CELL
        val lastCol = (r - 1) / CELL
        val firstRow = t / CELL
        val lastRow = (b - 1) / CELL
        val out = ArrayList<CellKey>((lastCol - firstCol + 1) * (lastRow - firstRow + 1))
        for (row in firstRow..lastRow) {
            for (col in firstCol..lastCol) out.add(CellKey(col, row))
        }
        return out
    }

    /**
     * Where a cell sits on the page, or null when the key names a cell past the page's edge.
     *
     * The last column and the last row are **partial**, and that is the whole of the clipping: a
     * cell read as a full square past the page's right-hand edge would come back from the engine
     * narrower than it was asked for, and the tile's rect and its pixel count would then disagree —
     * which the swap refuses, so the undo would silently skip that square of the page.
     */
    fun cellRect(key: CellKey, pageWidth: Int, pageHeight: Int): CellRect? {
        if (key.col < 0 || key.row < 0 || pageWidth <= 0 || pageHeight <= 0) return null
        val left = key.col * CELL
        val top = key.row * CELL
        if (left >= pageWidth || top >= pageHeight) return null
        return CellRect(
            left = left,
            top = top,
            width = minOf(CELL, pageWidth - left),
            height = minOf(CELL, pageHeight - top),
        )
    }
}

/**
 * One contact's before-image, gathered as it happens: opened at the first change the engine
 * announces and closed when the pen lifts — or, for the "Bring in ink" bake and the debug fill door,
 * closed by the door itself, because a composited bake never produces a pen-up.
 *
 * The screen feeds it every rect g-paper reports and hands it a way to read pixels; it decides which
 * of those actually need reading (see [RasterTiles] — a cell is read once and never again) and hands
 * back a single [SketchEdit.RasterChanged] at the end, or nothing when there is nothing to take back.
 *
 * **A builder belongs to one raster** (arc 45 / G3). [layer] is fixed at construction and stamped
 * on the entry, because a contact only ever changes one of the page's two images — g-paper's own
 * rule — and the tiles it reads are that image's, swapped back into that image and no other. The
 * screen opens a builder on the layer the engine names at the contact's first will-change; a
 * second layer arriving inside one contact would be the engine breaking its own rule, and the
 * screen closes the entry and opens a fresh one rather than mixing two images' pixels into a patch
 * list that carries no layer of its own.
 *
 * **The cap is a belt, and it is kept anyway.** With cells aligned to the page a single contact
 * cannot hold more than the page itself, which is well under the budget — so [tooBig] should never
 * happen. If it ever does, what happens is the honest thing rather than the clever one: the pixels
 * held are dropped, nothing is recorded, and the gesture does nothing for that one contact. An undo
 * that quietly half-restores a sweep would be worse than an undo that admits it cannot.
 */
class RasterEditBuilder(
    private val pageKey: String,
    private val pageIndex: Int,
    /** Which of the page's two rasters this contact is changing — read from, and swapped back into. */
    val layer: RasterLayer,
    private val pageWidth: Int,
    private val pageHeight: Int,
    private val capBytes: Long = SketchEdit.UNDO_BUDGET_BYTES,
) {

    /**
     * The cells read so far, in the order they were first touched. A map because the question asked
     * dozens of times a second is "have I already got this one", and insertion order because a
     * before-image read across a sweep reads better in a log when it is in the order the hand went.
     */
    private val held = LinkedHashMap<CellKey, RasterTile>()

    /** What the tiles held so far cost. */
    var bytes: Long = 0L
        private set

    /** This contact covered more than the whole history is allowed to hold, so it is not recorded. */
    var tooBig: Boolean = false
        private set

    /**
     * A rect the page is about to change inside. [read] is asked only for cells this contact has not
     * already got, and may answer null — a cell the paper will not give up is left out rather than
     * guessed at, because a tile of invented pixels swapped back onto the page would *paint* rather
     * than restore.
     *
     * Right and bottom are exclusive.
     */
    fun touch(left: Int, top: Int, right: Int, bottom: Int, read: (CellRect) -> IntArray?) {
        if (tooBig) return
        for (key in RasterTiles.cellsTouching(left, top, right, bottom, pageWidth, pageHeight)) {
            if (held.containsKey(key)) continue
            val rect = RasterTiles.cellRect(key, pageWidth, pageHeight) ?: continue
            val pixels = read(rect) ?: continue
            // A read that came back the wrong shape is dropped for the same reason a null one is:
            // the engine refuses a patch whose pixel count does not match its rect, so keeping it
            // would be keeping a tile that the undo will step over without saying so.
            if (pixels.size != rect.width * rect.height) continue
            val tile = RasterTile(rect.left, rect.top, rect.width, rect.height, pixels)
            if (bytes + tile.bytes > capBytes) {
                tooBig = true
                held.clear()
                bytes = 0L
                return
            }
            held[key] = tile
            bytes += tile.bytes
        }
    }

    /**
     * The entry for this contact, or null when there is nothing to record — a contact that changed
     * nothing the paper would give up, or one that was too big to take back.
     */
    fun build(): SketchEdit.RasterChanged? {
        if (tooBig || held.isEmpty()) return null
        return SketchEdit.RasterChanged(pageKey, pageIndex, layer, held.values.toList())
    }
}
