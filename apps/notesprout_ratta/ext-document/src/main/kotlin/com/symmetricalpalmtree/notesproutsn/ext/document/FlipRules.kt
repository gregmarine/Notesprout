package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract

/**
 * Whether a page flip may start, and what to say when it may not — **pure Kotlin**, so the whole
 * decision table is pinned by test rather than by tapping an arrow on a device.
 *
 * It is a small function guarding an expensive, dangerous act: a flip pushes the outgoing page's
 * text, moves the host's target and swaps the buffer. Starting a second one over the first, or
 * starting one while the screen is leaving, would put a save in flight with no page to land on.
 *
 * The edge check is **local** — it reads the last state the host answered with. A tap past the last
 * page must not cost a Binder round trip to be told there is nothing there.
 */
object FlipRules {

    /**
     * @param busy a flip or a Bring in is already running.
     * @param leaving the screen is on its way out (Done / Close).
     * @param hasTarget a state has been adopted, so there is something to flip from.
     * @param direction [DocumentContract.PAGE_PREV] or [DocumentContract.PAGE_NEXT].
     * @param pageIndex the adopted state's 0-based page, or −1 for the notebook scope (M7).
     * @param pageCount the adopted state's page count.
     */
    fun check(
        busy: Boolean,
        leaving: Boolean,
        hasTarget: Boolean,
        direction: Int,
        pageIndex: Int,
        pageCount: Int,
    ): Outcome {
        if (busy || leaving || !hasTarget) return Outcome.BLOCKED
        // The notebook scope is not a page and has no neighbours (M7's chords no-op there).
        if (pageIndex < 0) return Outcome.BLOCKED
        return when (direction) {
            DocumentContract.PAGE_PREV -> if (pageIndex <= 0) Outcome.AT_FIRST else Outcome.GO
            DocumentContract.PAGE_NEXT -> if (pageIndex >= pageCount - 1) Outcome.AT_LAST else Outcome.GO
            // Not a direction: nothing to move towards.
            else -> Outcome.BLOCKED
        }
    }

    /** What a flip request should cause. */
    enum class Outcome {
        /** Say nothing and do nothing — the screen is busy, going, or has nothing to flip. */
        BLOCKED,

        /** Already at the first page: the arrow stays, and says so. */
        AT_FIRST,

        /** Already at the last page. */
        AT_LAST,

        /** Flip. */
        GO,
    }
}
