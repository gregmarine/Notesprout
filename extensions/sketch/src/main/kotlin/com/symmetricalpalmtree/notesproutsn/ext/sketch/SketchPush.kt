package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.notesproutsn.extension.ByteChunks
import com.symmetricalpalmtree.notesproutsn.extension.ISketchHost

/**
 * The one chunk stream a sketch save crosses on (arc 43 / K5) — written once because it has **two**
 * callers that must not disagree: [SketchSaver]'s ordinary push, and [SketchService]'s re-push of a
 * parked save at `end()`, which happens with no screen work left to do.
 *
 * The rules are the contract's and there is nothing clever here: chunks in order from 0, each one
 * [ByteChunks]' shape, `last` on the final one — and **an empty image is one empty chunk**, which is
 * the wire form of "this page has no sketch" and is what makes a cleared page ride the same call as
 * every other save. Blocking Binder calls, so **never from the main thread**.
 *
 * Any refusal ([com.symmetricalpalmtree.notesproutsn.extension.SketchContract.SKETCH_TOO_LARGE],
 * [com.symmetricalpalmtree.notesproutsn.extension.SketchContract.SKETCH_BAD_PNG], a dead binder)
 * comes back out of here as it crossed; the caller parks the pixels rather than losing them.
 */
object SketchPush {

    /** Push [png] to the host as the sketch of [pageKey]. Throws on any refusal. */
    fun push(host: ISketchHost, pageKey: String, png: ByteArray) {
        val chunks = ByteChunks.chunk(png)
        for (i in chunks.indices) host.saveSketchChunk(pageKey, i, chunks[i], i == chunks.lastIndex)
    }
}
