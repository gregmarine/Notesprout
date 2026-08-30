package com.symmetricalpalmtree.notesproutsn.ext.pdf

import java.io.OutputStream

/**
 * Counts what actually reached [out] (arc 18 / D1). `PdfDocument.writeTo` reports nothing, and the
 * host's verbatim `bytesWritten == streamBytes` check does not apply to a transforming exporter —
 * so the only honest number this side can report is the one measured on the way through. A guess
 * (the container's length, a file stat taken before the sync) would be a claim about work that may
 * not have happened.
 *
 * Pure `java.io`, hence JVM-tested. Ownership is the delegate's: [close] closes [out], and this
 * stream is never closed independently of the destination it wraps.
 */
internal class CountingOutputStream(private val out: OutputStream) : OutputStream() {

    /** Bytes written so far. */
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    // The one-arg write(ByteArray) of the base class delegates here, so a byte is counted once.
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}
