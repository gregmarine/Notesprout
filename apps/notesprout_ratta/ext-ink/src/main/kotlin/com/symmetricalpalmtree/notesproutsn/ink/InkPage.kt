package com.symmetricalpalmtree.notesproutsn.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * What [InkScreenActivity] needs of the page a paper-owning extension is showing (arc 23) — the
 * *ink* half of a consumer's document and nothing else. Everything structural around it stays the
 * consumer's own: the pad's page list and its inserts and deletes, the calendar's periods, its
 * bookmark and its navigation.
 *
 * The two mutations that answer a g-paper callback are synchronous and run on Main (they touch only
 * the in-memory page, so a pen-up never waits on IO); [flushUntilClean] is `suspend` and hops to IO
 * for the store call. The screen serialises the suspending half behind its page-op lock.
 */
interface InkPage {

    /** The showing page's id — what a recorded [InkAction] names. */
    val pageId: String

    /** The page's ink, in writing order. */
    val strokes: List<Stroke>

    val pageWidth: Float
    val pageHeight: Float

    /** Take one committed stroke, at the end of the page's writing order. */
    fun addStroke(stroke: Stroke)

    /** Drop [ids]; null when nothing of ours was in the set. */
    fun erase(ids: Collection<String>): InkAction.Erased?

    /** Translate [ids] by ([dx], [dy]); null when nothing moved. */
    fun move(ids: Collection<String>, dx: Float, dy: Float): InkAction.Moved?

    /**
     * Write the page until it stays written. [InkDocument.MAX_FLUSH_PASSES] is the **debounced**
     * save's bound — what it leaves behind, the next debounce picks up — and
     * [InkDocument.UNBOUNDED] (the default) is every **leave** path's, which has no next debounce
     * to leave anything to.
     */
    suspend fun flushUntilClean(maxPasses: Int = InkDocument.UNBOUNDED): Boolean
}
