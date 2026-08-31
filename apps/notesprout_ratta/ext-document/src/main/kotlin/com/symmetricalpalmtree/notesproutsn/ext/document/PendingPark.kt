package com.symmetricalpalmtree.notesproutsn.ext.document

/**
 * The one place text lives when a save could not be delivered — **pure Kotlin**, so the rules that
 * decide whether parked words are eventually written or dropped are pinned by test.
 *
 * A push fails when the host is not there to take it: its process died mid-edit, its binder was
 * revoked, the transaction threw. The editor keeps typing regardless (the writer neither knows nor
 * should care), so the last snapshot that failed is parked here, keyed by **the target it was for**.
 * Two things then have to be true, and they pull in opposite directions:
 *
 * - Text that failed to save must not be lost. A restarted host must be able to take it.
 * - Text must never land on the **wrong** document. Page keys are globally unique, so a park whose
 *   key is not what the reconnected host is showing is not a save waiting to happen — it is a
 *   stranger's words, and writing them would be corruption. It is dropped.
 *
 * Losing an unwritable snapshot is a bad minute; writing it into someone else's page is a bad file.
 * The park always resolves that trade the same way.
 */
class PendingPark {

    private var key: String? = null
    private var text: String? = null

    /** True when something is waiting to be written. */
    @get:Synchronized
    val isParked: Boolean
        get() = key != null

    /** The parked target's key, or null when nothing is parked. Never the text — see [resolve]. */
    @get:Synchronized
    val parkedKey: String?
        get() = key

    /** A push for [pageKey] failed: hold [pending] until someone can take it. A newer park for the
     *  same target replaces the older one — only the last snapshot is worth anything. */
    @Synchronized
    fun park(pageKey: String, pending: String) {
        key = pageKey
        text = pending
    }

    /** A push for [pageKey] landed, by whatever route: whatever was parked *for that target* is now
     *  on disk and goes. A park for another target is left alone — it is still owed a write. */
    @Synchronized
    fun clear(pageKey: String) {
        if (key == pageKey) {
            key = null
            text = null
        }
    }

    /**
     * The host is back and says it is showing [currentKey]. Empties the park either way — this is a
     * one-shot decision, and a park that survived its own resolution would be pushed twice.
     */
    @Synchronized
    fun resolve(currentKey: String): Resolution {
        val parkedKey = key ?: return Resolution.Nothing
        val parkedText = text.orEmpty()
        key = null
        text = null
        return if (parkedKey == currentKey) Resolution.Push(parkedKey, parkedText) else Resolution.Drop
    }

    /** Take whatever is parked without asking about keys — the teardown backstop's read, where the
     *  binder in hand is the one the park was made against. */
    @Synchronized
    fun take(): Pair<String, String>? {
        val parkedKey = key ?: return null
        val parkedText = text.orEmpty()
        key = null
        text = null
        return parkedKey to parkedText
    }

    /** What [resolve] decided. */
    sealed interface Resolution {
        /** Nothing was parked. */
        data object Nothing : Resolution

        /** Write these words to this target. */
        data class Push(val pageKey: String, val text: String) : Resolution

        /** The park was for another document: it is discarded, unwritten and deliberately. */
        data object Drop : Resolution
    }
}
