package com.symmetricalpalmtree.notesprout.extension

import android.os.SharedMemory
import android.system.OsConstants
import com.symmetricalpalmtree.notesprout.core.Bitmaps

/**
 * The host side of the [RenderedImage] `SharedMemory` handshake (arc 4 / H3), shared by
 * [MarkdownClient], `ObjectProviderClient.render` and `MarkdownProxyBinder`:
 *
 * - [copyOut] — map the extension's region read-only, verify mime + byte count ([RenderCaps.bytesProblem]),
 *   copy the bytes out, unmap, verify the WEBP header against the declared size and the edge cap
 *   ([RenderCaps.imageProblem]) — and always close the region. Any violation → [ExtensionCallException].
 * - [wrap] — the reverse, for the proxy: put verified bytes into a fresh region (`PROT_READ`) inside a
 *   new [RenderedImage] the proxy owns until the reply is marshalled.
 */
object RenderedImages {

    /** Verified bytes + the size both the header and the sender agree on. */
    class Copy(val bytes: ByteArray, val widthPx: Int, val heightPx: Int)

    fun copyOut(image: RenderedImage): Copy {
        val memory: SharedMemory = image.memory
        try {
            RenderCaps.bytesProblem(image.mimeType, image.byteCount, memory.size)?.let { throw ExtensionCallException(it) }
            val bytes = ByteArray(image.byteCount)
            val buffer = memory.mapReadOnly()
            try {
                buffer.get(bytes)
            } finally {
                SharedMemory.unmap(buffer)
            }
            RenderCaps.imageProblem(image.widthPx, image.heightPx, Bitmaps.imageSize(bytes))?.let { throw ExtensionCallException(it) }
            return Copy(bytes, image.widthPx, image.heightPx)
        } finally {
            memory.close()
        }
    }

    /** A fresh read-only region holding [copy]; the caller closes `memory` once the reply is marshalled. */
    fun wrap(copy: Copy): RenderedImage {
        val shared = SharedMemory.create(null, copy.bytes.size)
        val buffer = shared.mapReadWrite()
        try {
            buffer.put(copy.bytes)
        } finally {
            SharedMemory.unmap(buffer)
        }
        shared.setProtect(OsConstants.PROT_READ)
        return RenderedImage(shared, copy.bytes.size, ExtensionContract.MIME_WEBP, copy.widthPx, copy.heightPx)
    }
}
