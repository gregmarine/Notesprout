package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.notesproutsn.extension.ByteChunks
import com.symmetricalpalmtree.notesproutsn.extension.ISketchHost

/**
 * The chunk stream a page's **reference image** crosses on (arc 51 "Guides" / J3) — [SketchPush]'s
 * shape on the guide accumulator (`saveGuideImageChunk`, independent of both raster ones): chunks
 * in order from 0, each one [ByteChunks]' shape, `last` on the final one, and **an empty image is
 * one empty chunk** — the wire form of Remove, which the host answers by soft-deleting the page's
 * image row. Blocking Binder calls, so **never from the main thread**; the caller holds
 * [SketchSaver]'s one push lock around it so a guide stream never interleaves with a raster one.
 *
 * Any refusal (`SKETCH_TOO_LARGE`, `SKETCH_BAD_IMAGE`, a dead binder) comes back out as it crossed.
 */
object GuidePush {

    /** Push [bytes] to the host as the reference image of [pageKey]. Throws on any refusal. */
    fun push(host: ISketchHost, pageKey: String, bytes: ByteArray) =
        forEachChunk(bytes) { i, chunk, last -> host.saveGuideImageChunk(pageKey, i, chunk, last) }

    /** The stream itself, host-free so it is pinned on the JVM: [send] once per chunk, in order,
     *  `last` true exactly once, on the final one. */
    fun forEachChunk(bytes: ByteArray, send: (index: Int, chunk: ByteArray, last: Boolean) -> Unit) {
        val chunks = ByteChunks.chunk(bytes)
        for (i in chunks.indices) send(i, chunks[i], i == chunks.lastIndex)
    }
}
