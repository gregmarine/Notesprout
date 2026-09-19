package com.symmetricalpalmtree.notesproutsn.ext.sketch

import kotlinx.coroutines.Job

/**
 * Which pages still have a save **in the air**, so that a read of a page can wait for its own write
 * and for nobody else's (2026-09-19, the page-turn change in [SketchSaver.flushForTurn]).
 *
 * **Why it exists.** Until 2026-09-19 a page turn awaited the whole save — the Main-thread pixel
 * copy, the WebP encode and the chunked push — before it asked the host to move. The encode of a
 * real pencil page measured 0.5–3.5 s on the Nomad, so a flip made immediately after drawing paid
 * all of it and a flip after a pause paid none: "a few seconds" against "instant", which is what
 * the user reported. A turn now awaits **only the copy**; the encode and the push go on in the
 * background, under the saver's one push lock, while the next page loads.
 *
 * That makes one thing untrue that used to be true for free: *when the host answers a page, its
 * rows are what the face last pushed.* A push still in the air has not committed, so a read of
 * **that** page would hand back the row as it was before the flush — the drawing would appear to
 * have lost its last few marks until the next save. This class is the whole of the fix: the saver
 * records every background push under **the page key the copy was taken on** (never the page that
 * happens to be showing when it lands), and any read of a page's raster awaits that key first. A
 * *different* page's read finds nothing to wait for and never waits — which is the entire point of
 * keying it rather than draining everything.
 *
 * **Pure Kotlin — no Android types at all** ([SketchSaveGovernor]'s and [PendingImagePark]'s rule),
 * so the bookkeeping that decides whether a read waits is pinned by a plain JUnit test rather than
 * by a device walk. [Job] is `kotlinx.coroutines`, which runs on a laptop.
 *
 * **One monitor, because the two sides are different threads.** Jobs are tracked from Main (the
 * turn flush) and complete on IO, and [await] is called from Main; every method here is
 * `@Synchronized` and none of them blocks while holding the lock — [await] takes the lock only long
 * enough to pick the next job and lets it go before joining.
 *
 * Completed jobs are pruned lazily, on every call that looks at a key, rather than through an
 * `invokeOnCompletion` handler: a handler would be a second thread writing the map for the sake of
 * memory that is one `Job` reference per raster, and the pruning is what makes [await] terminate
 * (each turn of its loop joins a job that the next turn then drops).
 *
 * **Nothing here is logged and nothing here names a pixel** — a page key is the page's own id.
 */
class PushTracker {

    /** Page key → the pushes still outstanding for it. A key with no outstanding push is removed,
     *  so an empty map is "nothing is in the air". */
    private val jobs = HashMap<String, MutableList<Job>>()

    /** Record [job] as a push of the page [key] names. Called on Main, from the turn flush. */
    @Synchronized
    fun track(key: String, job: Job) {
        val list = jobs.getOrPut(key) { mutableListOf() }
        list.removeAll { it.isCompleted }
        list += job
    }

    /** Whether anything is still in the air for [key] — the question a page load asks before it
     *  decides whether it has to wait at all. Prunes what has finished on the way past. */
    @Synchronized
    fun isPending(key: String): Boolean = next(key) != null

    /** How many pages have a push outstanding; 0 when nothing does. Counts only. */
    @Synchronized
    fun pendingKeys(): Int {
        prune()
        return jobs.size
    }

    /**
     * Suspend until every push taken for [key] has finished — landed, failed and parked, either
     * way *finished*. A key with nothing outstanding returns at once, which is the ordinary case
     * and the reason a turn to a page nobody just drew on costs nothing.
     *
     * Terminates because each turn of the loop joins a job that the next turn prunes, and because
     * the only writer of new jobs is the turn flush of the page currently on the glass — never the
     * page being awaited.
     */
    suspend fun await(key: String) {
        while (true) (next(key) ?: return).join()
    }

    /**
     * Suspend until **everything** outstanding has finished, whatever page it was for — the leave
     * flush's form of the question, so a screen never hands the pipeline back with pixels still in
     * the air on a binder the host is about to revoke.
     *
     * Its termination rests on [SketchSaver] setting `leaving` before it calls this: with `leaving`
     * true no completion can start another push, so the map only ever shrinks.
     */
    suspend fun awaitAll() {
        while (true) (anyJob() ?: return).join()
    }

    /** The next outstanding job for [key], pruning what has finished; null when that page owes
     *  nothing. */
    @Synchronized
    private fun next(key: String): Job? {
        val list = jobs[key] ?: return null
        list.removeAll { it.isCompleted }
        if (list.isEmpty()) {
            jobs.remove(key)
            return null
        }
        return list[0]
    }

    /** Any outstanding job at all, pruning what has finished; null when nothing is in the air. */
    @Synchronized
    private fun anyJob(): Job? {
        prune()
        return jobs.values.firstOrNull()?.firstOrNull()
    }

    /** Drop every finished job, and every key left with none. Call under the monitor. */
    private fun prune() {
        val empty = ArrayList<String>(jobs.size)
        for ((key, list) in jobs) {
            list.removeAll { it.isCompleted }
            if (list.isEmpty()) empty += key
        }
        for (key in empty) jobs.remove(key)
    }
}
