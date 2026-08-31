package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.TextChunks

/**
 * Where one save's chunks go. The production sink is one line around `IDocumentHost.saveChunk`; a
 * test's is a list. Keeping the seam this narrow is what lets the push order — the part that is
 * actually easy to get wrong — be pinned without a Binder.
 *
 * An implementation reports failure by **throwing**. That is the host stub's own vocabulary
 * (`SecurityException` / `IllegalStateException` / `IllegalArgumentException`, plus the transport's
 * `DeadObjectException`), and swapping it for a boolean here would only mean translating it twice.
 */
fun interface ChunkSink {
    fun accept(pageKey: String, index: Int, chunk: String, last: Boolean, drafted: Boolean)
}

/**
 * Push one whole document through a [ChunkSink].
 *
 * The rules the host's accumulator depends on, all of them structural rather than remembered:
 * chunks go **in order from 0**, the final one carries `last` (which is what commits), and empty
 * text is **one empty chunk** ([TextChunks]' rule — an empty value is a value: clearing a document
 * has to be a save like any other, not an absence of one).
 *
 * A sink that throws part-way aborts the push and the exception reaches the caller unchanged, so
 * nothing advances [AutosaveGovernor.savedText]. The host resets its accumulation on a refused
 * chunk; the next attempt starts again from chunk 0, which is precisely what this function does.
 */
object ChunkPush {

    /** @param drafted marks the one save that anchors a seed/merge (M6/M7); an ordinary edit is false. */
    fun push(sink: ChunkSink, pageKey: String, text: String, drafted: Boolean = false) {
        val chunks = TextChunks.chunk(text)
        for ((index, chunk) in chunks.withIndex()) {
            sink.accept(pageKey, index, chunk, index == chunks.lastIndex, drafted)
        }
    }
}
