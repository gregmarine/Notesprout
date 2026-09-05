package com.symmetricalpalmtree.notesproutsn.ext.cloud

import java.io.InputStream
import java.io.OutputStream

/**
 * Stream exactly the number of bytes the host promised, and refuse anything else (arc 25 / V2).
 *
 * The seam's `upload` carries `expectedBytes` — what the host wrote into the fd it handed over —
 * and the whole point of it is that the provider can tell an interrupted export from a finished
 * one *before* the file lands in the user's Drive under a name that says it is complete. A short
 * source is the fd being closed early; a long one is the host and the provider disagreeing about
 * what was written. Either way the upload is refused rather than committed.
 *
 * The two messages are informative, not contractual: the host reads any message but the seam's two
 * verbatim refusals as "the provider didn't answer".
 */
object ExactCopy {

    const val SHORT_READ: String = "short read"
    const val LONG_READ: String = "long read"

    private const val BUFFER_BYTES = 32 * 1024

    /**
     * Copy exactly [expectedBytes] from [source] to [sink]. Throws `IllegalStateException` with
     * [SHORT_READ] if the source ended early, [LONG_READ] if it had more to give. Answers the count,
     * which is always [expectedBytes] when it returns at all.
     */
    fun copy(source: InputStream, sink: OutputStream, expectedBytes: Long): Long {
        require(expectedBytes >= 0) { "expectedBytes is negative ($expectedBytes)" }
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (total < expectedBytes) {
            val want = minOf(buffer.size.toLong(), expectedBytes - total).toInt()
            val n = source.read(buffer, 0, want)
            if (n < 0) throw IllegalStateException(SHORT_READ)
            sink.write(buffer, 0, n)
            total += n
        }
        if (source.read() >= 0) throw IllegalStateException(LONG_READ)
        sink.flush()
        return total
    }
}
