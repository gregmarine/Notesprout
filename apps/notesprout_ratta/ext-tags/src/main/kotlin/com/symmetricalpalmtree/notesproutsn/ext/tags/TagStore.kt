package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.SharedBytes
import com.symmetricalpalmtree.notesproutsn.extension.TagCodec
import com.symmetricalpalmtree.notesproutsn.extension.TagIndex

/** The extension cannot reach its storage (any store exception — the host's rule: treat all as unavailable). */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/**
 * There **is** a stored index and it cannot be read (an unknown version line). Deliberately not the
 * same as "no index yet": the caller must say so and must not write over it, because a blank index
 * saved over a library's tags is a loss nobody can undo (the `ScratchPageCodec` rule).
 */
class IndexUnreadable(cause: Throwable) : Exception(cause.message, cause)

/**
 * The tag index's key layout over the host's `IExtensionStore` (arc 21 / W1). **Blocking** — every
 * call runs on `Dispatchers.IO` (the screen) or a Binder thread (the service's call-shaped methods),
 * never Main. The extension writes nothing to disk itself: this store is the host's, lent for the
 * showing.
 *
 * **One key, [KEY_INDEX], holding the whole index** as its [TagCodec] blob. Not a key per tag: the
 * index is read whole on every open and written whole on every edit, and a per-tag layout would turn
 * one write into a fan-out with no transaction around it — a half-applied edit is exactly what a
 * single value cannot produce. The caps are sized so the worst legal index fits one value
 * ([TagCodec.WORST_CASE_BYTES]).
 *
 * Values at or under `STORE_MAX_INLINE_BYTES` go through `put` / `get`; above that
 * `putLarge` / `getLarge` (the `SharedBytes` handshake — the region we create is closed after the
 * call returns; the one the host returns is closed in a `finally`). The fall to the large path is on
 * the **exact** `STORE_VALUE_LARGE` message, never a substring.
 */
class TagStore(private val store: IExtensionStore) {

    /** The stored blob, or null when there is none yet (first run). */
    fun readBlob(): ByteArray? = guard {
        try {
            store.get(KEY_INDEX)
        } catch (e: IllegalStateException) {
            if (e.message != ExtensionContract.STORE_VALUE_LARGE) throw e
            val v = store.getLarge(KEY_INDEX) ?: return@guard null
            SharedBytes.readAndClose(v)
        }
    }

    /**
     * The stored index. A missing value is [TagIndex.EMPTY] — a first run, not a failure.
     *
     * @throws IndexUnreadable there is a value and its version line is not [TagCodec.VERSION].
     * @throws StoreUnavailable the store could not be reached.
     */
    fun read(): TagIndex {
        val blob = readBlob()
        return try {
            TagCodec.decode(blob)
        } catch (e: IllegalArgumentException) {
            throw IndexUnreadable(e)
        }
    }

    /** Write the whole index. @throws StoreUnavailable */
    fun write(index: TagIndex) {
        val blob = TagCodec.encode(index)
        guard {
            if (blob.size <= ExtensionContract.STORE_MAX_INLINE_BYTES) {
                store.put(KEY_INDEX, blob)
            } else {
                val v = SharedBytes.write(blob)
                try { store.putLarge(KEY_INDEX, v) } finally { v.memory.close() }
            }
        }
    }

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            throw StoreUnavailable(e)
        }

    companion object {
        const val KEY_INDEX = "index"
    }
}
