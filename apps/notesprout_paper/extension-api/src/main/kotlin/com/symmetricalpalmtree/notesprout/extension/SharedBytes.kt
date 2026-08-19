package com.symmetricalpalmtree.notesprout.extension

import android.os.SharedMemory
import android.system.OsConstants

/**
 * The ashmem handshake behind [LargeValue], written once for both sides (arc 6 / S0).
 *
 * [write] — the sender: create a region of exactly `bytes.size`, map RW, copy in, unmap,
 * `setProtect(PROT_READ)`, wrap. The sender still owns the handle and **must close it** once the
 * transaction carrying it is marshalled (a Binder stub: in `onTransact`'s `finally`; a client: after
 * the call returns) — `SharedMemory` dups the descriptor into the parcel.
 *
 * [read] — the receiver: map read-only, copy out exactly `byteCount` bytes, unmap. The receiver
 * closes the region in its own `finally` ([readAndClose] does both).
 */
object SharedBytes {

    /** Wrap [bytes] (1..STORE_MAX_VALUE_BYTES) in a fresh read-only region. Throws on an empty / oversized input. */
    fun write(bytes: ByteArray): LargeValue {
        LargeValue.requireValid(bytes.size, bytes.size)
        val region = SharedMemory.create(null, bytes.size)
        try {
            val buf = region.mapReadWrite()
            try {
                buf.put(bytes)
            } finally {
                SharedMemory.unmap(buf)
            }
            region.setProtect(OsConstants.PROT_READ)
        } catch (t: Throwable) {
            region.close()
            throw t
        }
        return LargeValue(region, bytes.size)
    }

    /** Copy exactly `byteCount` bytes out of [value] (the region stays open — the caller closes it). */
    fun read(value: LargeValue): ByteArray {
        val buf = value.memory.mapReadOnly()
        try {
            val out = ByteArray(value.byteCount)
            buf.get(out)
            return out
        } finally {
            SharedMemory.unmap(buf)
        }
    }

    /** [read], then close the region — the receiver's one-liner. */
    fun readAndClose(value: LargeValue): ByteArray =
        try { read(value) } finally { value.memory.close() }
}
