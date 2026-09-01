package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.TagShowing

/**
 * What the notebook's tag doors aim at (arc 21 / W2) — the two questions that have an answer worth
 * pinning, kept out of the Activity so they can be tested.
 *
 * A page **number** is the host's to resolve and nobody else's: the extension is handed labels, not
 * a way to work them out, because it has no idea what a page is. That resolution happens at the tap
 * against the live page list — never from a snapshot taken earlier, which a page op would have made
 * a lie.
 */
object TagTargets {

    /**
     * The 1-based number of [pageId] in [pageIds], or null when it is not there. Null is a real
     * answer: during a page op the displayed page can briefly be one the list no longer holds, and
     * a "Page 0" would name nothing.
     */
    fun pageNumber(pageIds: List<String>, pageId: String): Int? {
        val i = pageIds.indexOf(pageId)
        return if (i < 0) null else i + 1
    }

    /**
     * The pages a MANAGE showing may carry: every one of them, until [TagShowing.MAX_PAGES].
     *
     * The bound is far past any real notebook, but the parcel **refuses** rather than allocates
     * above it — so a notebook that reached it would crash the tap instead of opening a screen
     * missing its tail. Listing what fits is the lesser of those two, and the caller logs the
     * difference.
     */
    fun listedPages(pageIds: List<String>): List<String> =
        if (pageIds.size <= TagShowing.MAX_PAGES) pageIds else pageIds.take(TagShowing.MAX_PAGES)
}
