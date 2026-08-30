package com.symmetricalpalmtree.notesproutsn.ext.soil

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException

/**
 * The one streamed copy both of this package's services deliver (the I2 review's dedup finding —
 * the seam doc says the two directions are the same copy, so the code is now one copy too):
 * read [src] to the end, write every byte to [dst], `fsync`, and return the count — verified
 * against the source's own length where the fd will say, because **a short copy that reports its
 * own short count would read as a success** on both sides. The host verifies the same number again
 * from the outside; this is the inside half of that check.
 *
 * A source that will not stat (a proxy fd from a cloud provider, a pipe: `statSize` −1 and an
 * unsized channel) has nothing to compare against — the host's own corroboration takes over, and
 * the stream is accepted on its own terms rather than refused for a number nobody can supply.
 *
 * The `fsync` before the close is what makes the returned count mean something durable: without it
 * the bytes may still be in a page cache when the far side reads them. Whether the sync is owed is
 * answered by what the fd **is** (`fstat`, the arc-18 D3 rule shared with `:ext-pdf`): a regular
 * file must sync, and a failure there is a real one — `ENOSPC`/`EIO` on the flash — reported as a
 * delivery failure rather than swallowed into a claimed success; a pipe from a streaming provider
 * has nothing to force to storage and the sync is skipped rather than attempted-and-excused.
 *
 * [what] names the direction in the short-copy message and the log line ("export" / "import") —
 * never a path, never a payload.
 */
internal object SoilStreams {

    /** One flash page-cluster's worth per hop — the size the family copies files at. */
    private const val BUFFER_BYTES = 64 * 1024

    fun streamCopy(src: ParcelFileDescriptor, dst: ParcelFileDescriptor, tag: String, what: String): Long {
        var total = 0L
        ParcelFileDescriptor.AutoCloseInputStream(src).use { input ->
            val expected = src.statSize.takeIf { it >= 0L }
                ?: runCatching { input.channel.size() }.getOrNull()?.takeIf { it > 0L }
            ParcelFileDescriptor.AutoCloseOutputStream(dst).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    total += n
                }
                output.flush()
                val regular = try {
                    OsConstants.S_ISREG(Os.fstat(output.fd).st_mode)
                } catch (e: Exception) {
                    Log.w(tag, "destination could not be stat'd: ${e.javaClass.simpleName}")
                    false
                }
                if (regular) {
                    try {
                        output.fd.sync()
                    } catch (e: IOException) {
                        throw IllegalStateException("syncing the $what failed (${e.javaClass.simpleName})")
                    }
                }
            }
            if (expected != null && total != expected) {
                throw IllegalStateException("short $what: $total of $expected bytes")
            }
        }
        return total
    }
}
