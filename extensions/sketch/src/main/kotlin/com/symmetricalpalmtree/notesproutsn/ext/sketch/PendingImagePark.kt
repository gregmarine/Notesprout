package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import java.util.EnumMap

/**
 * The one place a page's pixels live when a save could not be delivered (arc 43 / K5; **two slots
 * since arc 45 "Ink" / G3**) — **pure Kotlin**, so the rules that decide whether parked pixels are
 * eventually written or dropped are pinned by test rather than by a device walk.
 *
 * A push fails when the host is not there to take it: its process died mid-sketch, its binder was
 * revoked, the transaction threw. The hand goes on drawing regardless, so the last snapshot that
 * failed is parked here, keyed by **the page it was for and the raster it was of**, and
 * `ISketch.end()` re-pushes it through the host binder while that binder is still valid — the last
 * moment it is.
 *
 * **Pixels have no other copy.** A notebook page's ink is rows in the `.soil` before the pen lifts;
 * a sketch raster is one image that exists nowhere until a save lands. That is why this class exists
 * at all and why [take] is unconditional: unlike the document editor's park, a sketch save is
 * accepted for **any live page** of the open notebook, so a park does not have to match what the
 * host is showing to be worth writing — it is re-pushed under its own key and its own layer.
 *
 * **One slot per raster, because a page has two rows and each is owed separately.** Graphite and
 * ink are encoded, pushed, refused and committed independently — a pencil scribble never re-sends
 * the ink — so a failure on one says nothing about the other, and a single slot would make the
 * second failure of a two-raster page throw away the first. Within a slot the arc-43 rule is
 * unchanged: only one page is ever on the glass and a page turn flushes before it turns, so two
 * pages can only be owed on the *same* raster after two failures in a row; when that happens the
 * newer pixels — the ones the hand is closest to — win and the older park is dropped ([park] says
 * so, and the caller logs it). Keeping a queue of unwritable pages would be keeping megabytes
 * against a case that means the host is already gone.
 *
 * **Every caller drains** (`while (take() != null)`) rather than taking one item: what is owed
 * after a host restart may be both rasters of a page, and a retry that took only the graphite would
 * leave the ink parked until the next failure displaced it.
 */
class PendingImagePark {

    /** One raster's unwritten bytes, and the page they belong to. */
    private class Slot(val pageKey: String, val bytes: ByteArray)

    private val slots = EnumMap<RasterLayer, Slot>(RasterLayer::class.java)

    /** True when **anything** is waiting to be written, on either raster. */
    @get:Synchronized
    val isParked: Boolean
        get() = slots.isNotEmpty()

    /** The page key parked on [layer], or null when that raster owes nothing. Never the pixels —
     *  see [take]. */
    @Synchronized
    fun parkedKey(layer: RasterLayer): String? = slots[layer]?.pageKey

    /** How many bytes are parked on [layer]; 0 when none are. Counts only — pixels are never
     *  logged. */
    @Synchronized
    fun parkedBytes(layer: RasterLayer): Int = slots[layer]?.bytes?.size ?: 0

    /**
     * A push of [layer] for [pageKey] failed: hold [pending] until someone can take it. Returns the
     * key of a park **on that same layer** this one displaced, or null when it replaced nothing or
     * replaced its own page's earlier attempt — the caller logs a displacement, because it is
     * pixels going away. The other raster's slot is never touched.
     */
    @Synchronized
    fun park(pageKey: String, layer: RasterLayer, pending: ByteArray): String? {
        val displaced = slots[layer]?.pageKey?.takeIf { it != pageKey }
        slots[layer] = Slot(pageKey, pending)
        return displaced
    }

    /** A push of [layer] for [pageKey] landed, by whatever route: whatever was parked *for that
     *  page on that raster* is now on disk and goes. A park for another page, or for the other
     *  raster, is left alone — it is still owed a write. */
    @Synchronized
    fun clear(pageKey: String, layer: RasterLayer) {
        if (slots[layer]?.pageKey == pageKey) slots.remove(layer)
    }

    /**
     * Take one parked item, **graphite first** ([SketchLayers.all]'s order, so a drain always
     * re-pushes the two rasters the same way round). Empties that slot: this is a one-shot read,
     * and a park that survived its own resolution would be pushed twice. Null when nothing at all
     * is owed, which is what ends a caller's drain loop.
     */
    @Synchronized
    fun take(): Parked? {
        for (layer in SketchLayers.all) {
            val slot = slots.remove(layer) ?: continue
            return Parked(slot.pageKey, layer, slot.bytes)
        }
        return null
    }

    /** Everything a re-push needs, and nothing else. */
    class Parked(val pageKey: String, val layer: RasterLayer, val bytes: ByteArray)
}
