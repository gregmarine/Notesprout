package com.symmetricalpalmtree.notesproutsn.data.soil

import java.io.File

/**
 * Which `.soil` files this process currently holds a connection to — **the door in, written down**
 * (arc 15 / E1).
 *
 * *One file, one connection* is a family-wide rule, and until the export screen every caller could
 * satisfy it by construction: a notebook is opened by its own session, a foreign read by the one
 * `readOnce` ritual. An export is the first operation that reads a notebook's **bytes** from
 * outside any of that, and its correctness depends on the file being **cold** — checkpointed,
 * sealed, and not about to be written under the copy. The library context guarantees that (there is
 * no notebook screen on the stack), but "guaranteed by where the button is" is not something the
 * code can check, so the code checks this instead.
 *
 * Claimed and released by [SoilDatabase] itself — [SoilDatabase.open] / [SoilDatabase.create] on
 * success, [SoilDatabase.seal] on the way out — so every door in the app is covered by construction
 * and no call site has to remember. Keyed on the file **path**, because the question an export asks
 * is about a file, not about a notebook id.
 *
 * A count rather than a set: the rule says one connection per file, and if that rule is ever broken
 * the registry must still be honest about when the *last* one goes rather than clearing on the
 * first seal. An unbalanced claim (a screen that never sealed — a bug elsewhere) makes an export of
 * that notebook refuse with a problem dialog until the process dies, which is the safe direction:
 * refusing to export beats copying a file with a live writer behind it.
 */
object SoilOpenFiles {

    private val open = HashMap<String, Int>()

    @Synchronized
    fun claim(file: File) {
        val k = key(file)
        open[k] = (open[k] ?: 0) + 1
    }

    @Synchronized
    fun release(file: File) {
        val k = key(file)
        val n = (open[k] ?: 0) - 1
        if (n <= 0) open.remove(k) else open[k] = n
    }

    /** True while any connection to [file] is open in this process. */
    @Synchronized
    fun isOpen(file: File): Boolean = key(file) in open

    /** Canonical where the filesystem will say (the Garden is a flat real dir), absolute otherwise. */
    private fun key(file: File): String =
        try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
}
