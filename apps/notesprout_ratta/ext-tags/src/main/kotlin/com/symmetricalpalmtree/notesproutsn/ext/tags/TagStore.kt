package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.TagIndex

/** The extension cannot reach its storage (any store exception — the host's rule: treat all as unavailable). */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/**
 * There **is** a stored index and it cannot be read. Deleted with the blob in arc 22 / X3 — an
 * unreadable store is "unavailable" then, because there is no blob to be half-read.
 */
class IndexUnreadable(cause: Throwable) : Exception(cause.message, cause)

/**
 * The tag index's storage adapter over the host's `IExtensionStore`.
 *
 * **Arc 22 / X1 — an "unavailable" stub.** The host's store became real SQLite tables
 * (`IExtensionStore` v6) and the one-blob key/value layout this class was written over is gone.
 * Until X3 declares the `tag` / `assignment` schema and rewrites the index over statements, a read
 * answers an empty index and a write refuses with [StoreUnavailable] — and the tag service still
 * declares API version 5, so a version-6 host does not list it at all (the floor rule): nothing
 * reaches this code from a live device.
 *
 * TODO(X3): schema v1 + SQL builders; `tags()` / `assignmentsOf()` replace `snapshot`; delete
 * `TagCodec`, `CompactId`, [IndexUnreadable] and `TagWrites`' process-local lock.
 */
@Suppress("UNUSED_PARAMETER")
class TagStore(private val store: IExtensionStore) {

    /** X1 stub: no blob. */
    fun readBlob(): ByteArray? = null

    /** X1 stub: an empty index. */
    fun read(): TagIndex = TagIndex.EMPTY

    /** X1 stub: refuses. @throws StoreUnavailable */
    fun write(index: TagIndex) {
        throw StoreUnavailable(IllegalStateException("tag store: not on tables yet (arc 22 / X3)"))
    }

    companion object {
        const val KEY_INDEX = "index"
    }
}
