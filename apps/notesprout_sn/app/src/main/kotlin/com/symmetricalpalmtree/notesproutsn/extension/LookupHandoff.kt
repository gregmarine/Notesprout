package com.symmetricalpalmtree.notesproutsn.extension

import android.os.SystemClock

/**
 * The in-process park between the two halves of an editor-started Bible lookup (arc 39 "Lookup").
 *
 * The editor's `openReference` arrives on a Binder thread in the notebook's process, and the
 * notebook is **stopped** behind the editor — a screen that cannot launch a child for a result and
 * see that result come back until the editor closes (an `ActivityResult` is delivered before
 * `onResume`, and the notebook does not resume while the editor stands). So the notebook resolves
 * the words, parks the resolved wire **here**, and answers `REFERENCE_OPENED`; the editor then
 * starts [BibleLookupActivity] — a live host screen — which [take]s the park and walks the reader's
 * door itself. Once-only, package-checked, and it goes stale on its own: a park nobody collected
 * within [TTL_MS] is dropped rather than opened later on some unrelated launch.
 *
 * Holds a reference (never logged) for the few milliseconds between the two calls, and nothing else.
 */
object LookupHandoff {

    class Parked(
        /** The reader to open. */
        val reader: ProviderRef,
        /** The resolved reference's wire form — opaque to the host. */
        val wire: String,
        /** The editor package the park was made for — the only caller [take] answers. */
        val callerPackage: String,
        val at: Long,
    )

    private var parked: Parked? = null

    @Synchronized
    fun park(reader: ProviderRef, wire: String, callerPackage: String) {
        parked = Parked(reader, wire, callerPackage, SystemClock.elapsedRealtime())
    }

    /** The park for [callerPackage], if one is fresh — cleared on the way out either way. */
    @Synchronized
    fun take(callerPackage: String?): Parked? {
        val p = parked
        parked = null
        if (p == null || callerPackage == null || p.callerPackage != callerPackage) return null
        if (SystemClock.elapsedRealtime() - p.at > TTL_MS) return null
        return p
    }

    /** Generous: the editor launches the lookup screen the instant its call returns. */
    const val TTL_MS = 30_000L
}
