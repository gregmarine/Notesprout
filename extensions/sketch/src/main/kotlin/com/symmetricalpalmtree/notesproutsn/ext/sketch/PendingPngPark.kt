package com.symmetricalpalmtree.notesproutsn.ext.sketch

/**
 * The one place a page's pixels live when a save could not be delivered (arc 43 / K5) — **pure
 * Kotlin**, so the rules that decide whether parked pixels are eventually written or dropped are
 * pinned by test rather than by a device walk.
 *
 * A push fails when the host is not there to take it: its process died mid-sketch, its binder was
 * revoked, the transaction threw. The hand goes on drawing regardless, so the last snapshot that
 * failed is parked here, keyed by **the page it was for**, and `ISketch.end()` re-pushes it through
 * the host binder while that binder is still valid — the last moment it is.
 *
 * **Pixels have no other copy.** A notebook page's ink is rows in the `.soil` before the pen lifts;
 * a sketch is one image that exists nowhere until a save lands. That is why this class exists at all
 * and why [take] is unconditional: unlike the document editor's park, a sketch save is accepted for
 * **any live page** of the open notebook, so a park does not have to match what the host is showing
 * to be worth writing — it is re-pushed under its own key.
 *
 * **One slot, and a mismatched key displaces it.** Only one page is ever on the glass, and a page
 * turn flushes before it turns, so two pages can only be owed at once after two failures in a row.
 * When that happens the newer pixels — the ones the hand is closest to — win and the older park is
 * dropped ([park] says so, and the caller logs it). Keeping a queue of unwritable pages would be
 * keeping megabytes against a case that means the host is already gone.
 */
class PendingPngPark {

    private var key: String? = null
    private var png: ByteArray? = null

    /** True when something is waiting to be written. */
    @get:Synchronized
    val isParked: Boolean
        get() = key != null

    /** The parked page's key, or null when nothing is parked. Never the pixels — see [take]. */
    @get:Synchronized
    val parkedKey: String?
        get() = key

    /** How many bytes are parked; 0 when nothing is. Counts only — pixels are never logged. */
    @get:Synchronized
    val parkedBytes: Int
        get() = png?.size ?: 0

    /**
     * A push for [pageKey] failed: hold [pending] until someone can take it. Returns the key of a
     * park this one **displaced**, or null when it replaced nothing or replaced its own page's
     * earlier attempt — the caller logs a displacement, because it is pixels going away.
     */
    @Synchronized
    fun park(pageKey: String, pending: ByteArray): String? {
        val displaced = key?.takeIf { it != pageKey }
        key = pageKey
        png = pending
        return displaced
    }

    /** A push for [pageKey] landed, by whatever route: whatever was parked *for that page* is now on
     *  disk and goes. A park for another page is left alone — it is still owed a write. */
    @Synchronized
    fun clear(pageKey: String) {
        if (key == pageKey) {
            key = null
            png = null
        }
    }

    /**
     * Take whatever is parked, under its own key. Empties the park either way: this is a one-shot
     * read, and a park that survived its own resolution would be pushed twice.
     */
    @Synchronized
    fun take(): Parked? {
        val parkedKey = key ?: return null
        val parkedPng = png ?: ByteArray(0)
        key = null
        png = null
        return Parked(parkedKey, parkedPng)
    }

    /** Everything a re-push needs, and nothing else. */
    class Parked(val pageKey: String, val png: ByteArray)
}
