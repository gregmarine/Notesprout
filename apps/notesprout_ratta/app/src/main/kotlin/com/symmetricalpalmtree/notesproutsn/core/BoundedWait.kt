package com.symmetricalpalmtree.notesproutsn.core

/**
 * Poll a condition until it holds or a deadline passes — a **blocking** wait, so it belongs only
 * where blocking is allowed (a Binder thread, an IO dispatcher) and never on Main.
 *
 * It exists as its own object for one reason: the wait it implements is the kind of thing that is
 * wrong in ways no on-device run reveals (a poll that never sleeps, one that overshoots its
 * deadline, a happy path that pays a sleep it did not need), and pulled out here with its clock and
 * its sleep injected it is pinned by JVM tests instead. Its caller is
 * `notebook.DocumentHostHooks` — see the reasoning for the wait there.
 *
 * The happy path is free: [condition] is evaluated once before anything is slept on.
 */
object BoundedWait {

    /**
     * True as soon as [condition] holds; false if [timeoutMs] passes first. Sleeps in [pollMs]
     * steps, the last one clipped so the total wait never overshoots the deadline.
     *
     * [clock] is monotonic milliseconds and [sleep] is `Thread.sleep` — both are parameters only so
     * a test can drive them; no caller passes them.
     */
    fun until(
        timeoutMs: Long,
        pollMs: Long,
        clock: () -> Long = { System.nanoTime() / 1_000_000L },
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        condition: () -> Boolean,
    ): Boolean {
        if (condition()) return true
        val deadline = clock() + timeoutMs
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0L) return false
            sleep(minOf(pollMs, remaining))
            if (condition()) return true
        }
    }
}
