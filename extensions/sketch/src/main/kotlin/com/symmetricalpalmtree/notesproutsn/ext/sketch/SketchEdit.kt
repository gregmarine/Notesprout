package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.RasterLayer

/**
 * One thing the hand did to a sketch page that can be taken back (arc 43 / K5).
 *
 * g-paper keeps no history of its own — that is stated in its host responsibilities and it is the
 * right call, because the component has no idea what a page is or what a file is. So the record is
 * ours, and on a raster page it is **pixels**, not ids.
 *
 * Every other SN screen records *references*: a stroke id, a page id, a row that is still in the
 * `.soil` and merely stamped. A raster page has no rows to un-stamp — the graphite went into the
 * page's graphite image at pen-up, the gel pen into its ink image, and the rubber took pixels off
 * the graphite one — so the only record of what was there a
 * moment ago is the pixels that were there. [RasterChanged] carries them, and [bytes] is what lets
 * [com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack] bound a history that is no longer
 * free to hold.
 *
 * **[Structural] is the one non-pixel kind** (K5b, the user's follow-up decision of 2026-09-15: the
 * face's own undo has to take back a page delete, because the person who deleted it is looking at
 * this screen and should not have to go back to the notebook to change their mind). A page is the
 * *notebook's* object: what a page insert or delete has to remember to be reversible — the live page
 * ids either side, the rows it soft-deleted, where the notebook was standing — is the notebook's own
 * undo record, kept on the notebook's own stack where an undo after Show pages can still reach it.
 * So a structural entry here carries **no state at all**, only the host's [Structural.token] for it:
 * it is a *reference into the host's ledger*, and asking the host to replay that edit is what keeps
 * the two histories one history rather than two that resemble each other. It costs no bytes, which
 * is also why the byte budget can never evict one.
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
     * The same entry, its page now at [index] (K5b) — what the history's re-index after a page
     * insert or delete hands back, for every kind.
     *
     * An entry re-indexed to where it already is hands back **the very same object**, which is most
     * of them on most inserts and costs nothing at all.
     */
    abstract fun withIndex(index: Int): SketchEdit

    /**
     * One of the page's two rasters as it was, over the patch of page one contact changed — a
     * pencil mark composited at pen-up, a gel-pen mark, a whole rubbing sweep from the moment the
     * rubber touched down to the moment it lifted, or one "Bring in ink" bake.
     *
     * **One contact is one entry, because it was one movement of the hand.** The engine reports an
     * erase once per batch and there are dozens of batches in a second of scrubbing; an entry each
     * would make taking back a rub a matter of tapping until it stopped.
     *
     * **One contact also touches exactly one raster, and that is why [layer] is a field rather than
     * a property of each tile** (arc 45 / G3). A pencil stroke announces graphite, a gel-pen stroke
     * and the ink bake announce ink, and a rub announces graphite and never so much as reads the
     * ink — g-paper's own rule, stated in `PaperListener.onRasterWillChange`. So the entry reads
     * its tiles from that one raster and **per-contact undo bytes do not double** when the page
     * gains a second image: a mark on a two-raster page costs exactly what the same mark cost on a
     * one-raster page.
     *
     * **It is still its own inverse, on that layer.** The tiles go onto that raster and come back
     * holding what it was holding (`swapPageRaster(layer, patches)`), so the entry that undid a
     * change is the entry that redoes it, with no second copy of the pixels and no second shape of
     * call. That is the whole reason this is a swap in the engine rather than a load: a page-wide
     * erase's before-image is the page, and a second one of those is ~9.5 MB the device does not
     * have to spare.
     *
     * [tiles] are disjoint — see [RasterTiles] — so they can be swapped in any order, which spares
     * the replayer from having to remember which order they were read in.
     */
    class RasterChanged(
        override val pageKey: String,
        override val pageIndex: Int,
        /** Which of the page's two rasters these tiles were read from, and the only one they may be
         *  swapped back into — a patch carries no layer of its own. */
        val layer: RasterLayer,
        val tiles: List<RasterTile>,
    ) : SketchEdit() {
        override val bytes: Long get() = tiles.sumOf { it.bytes }

        /**
         * **The tiles are shared, not copied**, and so is the layer. They are the whole cost of an
         * entry (megabytes on a page-wide rub) and nothing about them changes when a *different*
         * page is inserted or removed: the pixels still belong to the same raster of the same page,
         * which has merely moved. Copying them to change one integer would double the history's
         * footprint at the exact moment the device is least able to afford it.
         */
        override fun withIndex(index: Int): RasterChanged =
            if (index == pageIndex) this else RasterChanged(pageKey, index, layer, tiles)
    }

    /**
     * A page the hand made or took away from this screen (K5b) — **the notebook's edit, named**.
     *
     * The host made it, recorded it on the notebook's own undo stack, and kept the snapshot under
     * [token]; this entry is how the face says *that one* when its own undo gesture reaches it. The
     * replay is a Binder call, not a swap of pixels, and the host moves its own stack in step.
     *
     * [bytes] is **zero**, and that is a property worth stating rather than a consequence: the byte
     * budget evicts the oldest entry that costs something, so a structural entry can never be the
     * one thrown away to make room for pixels — which is right, because losing it would leave the
     * face unable to reverse a page the notebook's stack still thinks is reversible.
     *
     * [pageKey] is the page the edit is *about* — the one that arrived, or the one that went — and
     * [pageIndex] is where it sits (or sat, and where it comes back to). Neither is used to walk the
     * paper the way a [RasterChanged] entry's is; they are what the replay re-indexes the rest of the
     * history by, and what it drops that page's pixel entries by.
     */
    sealed class Structural : SketchEdit() {

        /** The host's opaque name for this edit — see
         *  [com.symmetricalpalmtree.notesproutsn.extension.SketchContract.MAX_STRUCTURAL_TOKEN_CHARS].
         *  Never parsed, never displayed; handed straight back. */
        abstract val token: String

        /** Nothing but a name is held, so nothing is owed to the budget. */
        final override val bytes: Long get() = 0L
    }

    /**
     * A page made from this screen — a swipe past the last page, or a two-finger swipe either way.
     * Taking it back removes that page again; putting it back makes it once more, with whatever was
     * drawn on it in between (the rows were soft-deleted, never destroyed).
     */
    data class PageInserted(
        override val token: String,
        override val pageKey: String,
        override val pageIndex: Int,
    ) : Structural() {
        override fun withIndex(index: Int): PageInserted =
            if (index == pageIndex) this else copy(pageIndex = index)
    }

    /**
     * A page deleted from this screen. Taking it back brings the page **and everything it owned**
     * — its handwriting, its document, its sketch — back at the position it had, which is the whole
     * point of the user's follow-up decision: the person who deleted it never has to leave this
     * screen to change their mind.
     */
    data class PageDeleted(
        override val token: String,
        override val pageKey: String,
        override val pageIndex: Int,
    ) : Structural() {
        override fun withIndex(index: Int): PageDeleted =
            if (index == pageIndex) this else copy(pageIndex = index)
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
