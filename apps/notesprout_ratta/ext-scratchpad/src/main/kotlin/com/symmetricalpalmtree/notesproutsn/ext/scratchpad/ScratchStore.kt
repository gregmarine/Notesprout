package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import java.util.UUID

/** The extension cannot reach its storage (any store exception — the host's rule: treat all as unavailable). */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/** A page whose encoded ink would exceed the old value cap — deleted with the ceiling in arc 22 / X2. */
class PageFullException(val bytes: Int) : Exception("scratch page full ($bytes bytes)")

/**
 * The scratch pad's storage adapter over the host's `IExtensionStore`.
 *
 * **Arc 22 / X1 — an "unavailable" stub.** The host's store became real SQLite tables
 * (`IExtensionStore` v6: `applySchema` / `exec` / `query`) and the key/value methods this class was
 * written over are gone. Until X2 declares the pad's schema (`page` / `stroke` / `state`) and
 * rewrites this over statements, every read answers its default and every write refuses with
 * [StoreUnavailable] — and the pad's service still declares API version 1, so a version-6 host does
 * not list it at all (the floor rule): nothing reaches this code from a live device.
 *
 * The public surface is kept exactly so the screen and the service compile unchanged.
 * TODO(X2): schema v1 + SQL builders + op-log flush; delete [PageFullException] and `encodedSize`.
 */
@Suppress("UNUSED_PARAMETER")
class ScratchStore(private val store: IExtensionStore) {

    class Loaded(val ids: List<String>, val currentId: String)

    /** X1 stub: the pad has no store to load from. */
    fun load(): Loaded = throw unavailable()

    /** X1 stub: a page with no ink. */
    fun readPage(id: String): ByteArray? = null

    fun savePage(id: String, blob: ByteArray) { throw unavailable() }

    fun insertPage(ids: List<String>, afterId: String?): Pair<List<String>, String> = throw unavailable()

    fun insertPageAt(ids: List<String>, afterId: String?, id: String): Pair<List<String>, String> = throw unavailable()

    fun insertPageBefore(ids: List<String>, beforeId: String?): Pair<List<String>, String> = throw unavailable()

    fun deletePage(ids: List<String>, id: String): Pair<List<String>, String> = throw unavailable()

    fun setCurrent(id: String) { throw unavailable() }

    fun setPages(ids: List<String>) { throw unavailable() }

    fun removePageBlob(id: String) { throw unavailable() }

    class Received(val pageId: String, val strokeIds: List<String>, val newPage: Boolean, val pagesBefore: List<String>, val currentBefore: String)

    fun receive(strokes: List<Stroke>, pageWidth: Float, pageHeight: Float, newPage: Boolean): Received = throw unavailable()

    private fun unavailable() = StoreUnavailable(IllegalStateException("scratch pad store: not on tables yet (arc 22 / X2)"))

    companion object {
        const val KEY_PAGES = "pages"
        const val KEY_CURRENT = "current"
        const val PAGE_PREFIX = "page/"
        fun pageKey(id: String) = PAGE_PREFIX + id
        fun newId(): String = UUID.randomUUID().toString()

        /** Encode + size check without writing (the "would this stroke cross the cap" test). */
        fun encodedSize(pageWidth: Float, pageHeight: Float, strokes: List<Stroke>): Int =
            ScratchPageCodec.encode(pageWidth, pageHeight, strokes).size
    }
}
