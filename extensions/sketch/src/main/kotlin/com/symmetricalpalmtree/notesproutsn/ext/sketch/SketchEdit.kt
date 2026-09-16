package com.symmetricalpalmtree.notesproutsn.ext.sketch

/**
 * One thing the hand did to a sketch page that can be taken back (arc 43 / K5).
 *
 * g-paper keeps no history of its own — that is stated in its host responsibilities and it is the
 * right call, because the component has no idea what a page is or what a file is. So the record is
 * ours, and on a raster page it is **pixels**, not ids.
 *
 * Every other SN screen records *references*: a stroke id, a page id, a row that is still in the
 * `.soil` and merely stamped. A raster page has no rows to un-stamp — the graphite went into one
 * page image at pen-up and the rubber took pixels off it — so the only record of what was there a
 * moment ago is the pixels that were there. [RasterChanged] carries them, and [bytes] is what lets
 * [com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack] bound a history that is no longer
 * free to hold.
 *
 * **Every edit knows which page it happened on, and that is the whole design.** The history is
 * screen-level, not page-level: someone who draws on page three, turns to page four and taps undo
 * means "take back the last thing I did", and the last thing they did was on page three — so
 * undoing it has to turn back to page three first. A per-page stack would silently do nothing on
 * page four, which reads as a broken gesture rather than as a rule nobody explained.
 *
 * [pageIndex] rides along beside [pageKey] because the key is an opaque host token that says
 * nothing about *where* the page is: the replay has to know which way to turn and how far, and the
 * index at the moment of the edit is the only honest answer this side of the seam has.
 */
sealed class SketchEdit {

    /** The page the edit happened on — the page a replay turns back to before it lands. */
    abstract val pageKey: String

    /** Where that page sat in the notebook when the edit was made: the replay's direction and bound. */
    abstract val pageIndex: Int

    /** What keeping this entry costs, for the stack's byte budget. */
    abstract val bytes: Long

    /**
     * The page image as it was, over the patch of page one contact changed — a mark composited at
     * pen-up, a whole eraser sweep from the moment the rubber touched down to the moment it lifted,
     * or one "Bring in ink" bake.
     *
     * **One contact is one entry, because it was one movement of the hand.** The engine reports an
     * erase once per batch and there are dozens of batches in a second of scrubbing; an entry each
     * would make taking back a rub a matter of tapping until it stopped.
     *
     * **It is its own inverse.** The tiles go onto the page and come back holding what the page was
     * holding (`swapPageRaster`), so the entry that undid a change is the entry that redoes it, with
     * no second copy of the pixels and no second shape of call. That is the whole reason this is a
     * swap in the engine rather than a load: a page-wide erase's before-image is the page, and a
     * second one of those is ~9.5 MB the device does not have to spare.
     *
     * [tiles] are disjoint — see [RasterTiles] — so they can be swapped in any order, which spares
     * the replayer from having to remember which order they were read in.
     */
    class RasterChanged(
        override val pageKey: String,
        override val pageIndex: Int,
        val tiles: List<RasterTile>,
    ) : SketchEdit() {
        override val bytes: Long get() = tiles.sumOf { it.bytes }
    }

    companion object {
        /**
         * How many bytes of before-image the undo side may hold — 48 MB, Paintsprout's number and
         * for its reason: five page-wide entries on the Nomad's 9.5 MB page, and still room for the
         * hundreds of small ones a sitting of sketching actually produces. The stack evicts the
         * **oldest costed entry** over it, never the newest.
         */
        const val UNDO_BUDGET_BYTES: Long = 48L * 1024L * 1024L
    }
}
