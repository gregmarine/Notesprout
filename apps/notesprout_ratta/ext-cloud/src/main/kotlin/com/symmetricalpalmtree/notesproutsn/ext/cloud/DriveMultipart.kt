package com.symmetricalpalmtree.notesproutsn.ext.cloud

/**
 * Drive's `uploadType=multipart` body (arc 25 / V2) — metadata part, then bytes part, one request.
 *
 * **Why two upload shapes at all.** A multipart upload is one round trip and is what Drive itself
 * recommends up to about 5 MiB; above that a dropped connection costs the whole transfer, so a
 * resumable session is worth its extra round trip. [MULTIPART_MAX_BYTES] is that line, and
 * [useMultipart] is the only place it is read.
 *
 * The body is assembled as **prefix + the source's bytes + suffix**, never as one buffer: the source
 * is a `ParcelFileDescriptor` the host opened on a `.soil` that can be hundreds of megabytes, and
 * this process must never hold it. That is also why the length has to be computable up front
 * ([length]) — `setFixedLengthStreamingMode` needs it, and it is what lets a short read be a
 * refusal instead of a hang.
 *
 * `\r\n` is MIME's line ending and every one here is a Kotlin escape, never a raw control byte.
 */
object DriveMultipart {

    /** At or below this, one multipart request; above it, a resumable session. */
    const val MULTIPART_MAX_BYTES: Long = 5L * 1024L * 1024L

    /** Fixed, and safe: it cannot occur in the JSON metadata part, and the bytes part is delimited
     *  by length rather than by scanning, so a file containing this text is not a problem. */
    const val BOUNDARY: String = "notesproutsn-drive-boundary"

    fun useMultipart(expectedBytes: Long): Boolean = expectedBytes <= MULTIPART_MAX_BYTES

    fun contentType(): String = "multipart/related; boundary=$BOUNDARY"

    /** Everything before the file's first byte. */
    fun prefix(metaJson: String, mime: String): ByteArray =
        (
            "--$BOUNDARY\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                metaJson + "\r\n" +
                "--$BOUNDARY\r\n" +
                "Content-Type: $mime\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)

    /** Everything after the file's last byte. */
    fun suffix(): ByteArray = "\r\n--$BOUNDARY--\r\n".toByteArray(Charsets.UTF_8)

    /** What `Content-Length` must be. */
    fun length(prefix: ByteArray, suffix: ByteArray, expectedBytes: Long): Long =
        prefix.size.toLong() + expectedBytes + suffix.size.toLong()
}
