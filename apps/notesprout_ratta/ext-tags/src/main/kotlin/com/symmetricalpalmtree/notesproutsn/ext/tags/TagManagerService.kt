package com.symmetricalpalmtree.notesproutsn.ext.tags

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.AssignmentRecord
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.ITagManager
import com.symmetricalpalmtree.notesproutsn.extension.TagRecord
import com.symmetricalpalmtree.notesproutsn.extension.TagRules
import com.symmetricalpalmtree.notesproutsn.extension.TagShowing

/**
 * The TAG_MANAGER point (arc 21 / W1, on rows since arc 22 / X3). Every method:
 * `HostCallerCheck.enforce` first.
 *
 * **Two call patterns on one interface**, and the store is what tells them apart:
 *  - `begin` / `configureShowing` / `end` bracket a **showing** — the host holds one bind for the
 *    screen's whole life and the store is lent once, exactly as the scratch pad's is. `begin` parks
 *    the store and clears anything stale (a second `begin` while one is held replaces it — the host
 *    restarted); `configureShowing` parks what the screen is about; `end` clears both.
 *  - `tags` / `assignmentsOf` / `assign` are **call-shaped** — bind, call, unbind — so the store
 *    rides the call. They are what the host's search merge (W4) and the lasso's silent heading→tag
 *    (W3) use, and none of them shows anything.
 *
 * **The two reads are paged and carry no ashmem** (X3). They replace W4's `snapshot`, which handed
 * the whole index over as one `TagCodec` blob over a shared-memory region: a reply is an ordinary
 * parcel now, so `tags` answers at most `TAGS_PAGE` records and `assignmentsOf` at most
 * `ASSIGNMENTS_PAGE` rows, and the host loops until a short page. The search merge therefore reads
 * the tags, ranks them itself, and asks for the assignments of **only the ids that matched**.
 *
 * **The transaction is the lock.** There is no read-modify-write to serialize any more: `assign` is
 * two small reads and one two-statement batch whose caps ride inside the inserts, so the screen and
 * this service may write at the same moment — in this process or after a host restart — without a
 * monitor between them.
 *
 * Only `SecurityException` / `IllegalArgumentException` / `IllegalStateException` are thrown —
 * anything else kills the transaction **silently** and the host reads an empty reply as success.
 * Logs are counts, lengths and durations: **a tag is the user's own words and is never logged.**
 */
class TagManagerService : Service() {

    private val binder = object : ITagManager.Stub() {

        override fun begin(store: IExtensionStore?) {
            enforce()
            requireNotNull(store) { "store is null" }
            synchronized(TagSession) {
                TagSession.clear()
                TagSession.store = store
            }
            Slog.d(TAG) { "begin" }
        }

        override fun configureShowing(showing: TagShowing?) {
            enforce()
            // The parcel is already through `TagShowing`'s constructor at unmarshal — that IS the
            // validation. What is left is the ordering: a showing with no store behind it would
            // launch a screen that cannot read anything.
            requireNotNull(showing) { "showing is null" }
            synchronized(TagSession) {
                if (TagSession.store == null) throw IllegalStateException(STORE_UNAVAILABLE)
                TagSession.showing = showing
            }
            // Never the label or a tag: user content. The mode and the kind are structure.
            Slog.d(TAG) { "configureShowing: mode=${showing.mode} kind=${showing.targetKind} pages=${showing.pageIds.size}" }
        }

        override fun end() {
            enforce()
            synchronized(TagSession) { TagSession.clear() }
            Slog.d(TAG) { "end" }
        }

        override fun tags(store: IExtensionStore?, offset: Int): List<TagRecord> {
            enforce()
            requireNotNull(store) { "store is null" }
            require(offset >= 0) { "offset is negative ($offset)" }
            val t0 = SystemClock.elapsedRealtime()
            val page = try {
                TagStore(store).tagsPage(offset)
            } catch (e: StoreUnavailable) {
                throw IllegalStateException(STORE_UNAVAILABLE)
            }
            Slog.d(TAG) { "tags: ${page.size} from $offset in ${SystemClock.elapsedRealtime() - t0} ms" }
            return page
        }

        override fun assignmentsOf(
            store: IExtensionStore?,
            tagIds: List<String>?,
            offset: Int,
        ): List<AssignmentRecord> {
            enforce()
            requireNotNull(store) { "store is null" }
            requireNotNull(tagIds) { "tagIds is null" }
            require(offset >= 0) { "offset is negative ($offset)" }
            // The list becomes one `IN (…)`, so its length is SQLite's bind cap and not a taste.
            require(tagIds.size <= ExtensionContract.ASSIGNMENT_QUERY_TAGS) {
                "${tagIds.size} tag ids — at most ${ExtensionContract.ASSIGNMENT_QUERY_TAGS}"
            }
            for (id in tagIds) require(TagRules.isId(id)) { "tag id is not a UUID" }
            // An empty selection is a real answer and touches no store: the host's ranking simply
            // matched nothing, which is the common case for a query that finds only names.
            if (tagIds.isEmpty()) return emptyList()
            val t0 = SystemClock.elapsedRealtime()
            val page = try {
                TagStore(store).assignmentsOf(tagIds, offset)
            } catch (e: StoreUnavailable) {
                throw IllegalStateException(STORE_UNAVAILABLE)
            }
            Slog.d(TAG) {
                "assignmentsOf: ${page.size} rows for ${tagIds.size} tag(s) from $offset " +
                    "in ${SystemClock.elapsedRealtime() - t0} ms"
            }
            return page
        }

        override fun assign(
            store: IExtensionStore?,
            text: String?,
            notebookId: String?,
            pageId: String?,
        ): String {
            enforce()
            requireNotNull(store) { "store is null" }
            requireNotNull(text) { "text is null" }
            // A page may be absent — that is a notebook tag. A notebook may not: since W4 every
            // assignment names one, because it is the only way the library can find the page again.
            requireNotNull(notebookId) { "notebookId is null" }
            val t0 = SystemClock.elapsedRealtime()
            // `assign` itself throws for text that is not a tag (IllegalArgumentException) and for a
            // cap (IllegalStateException(TAG_INDEX_FULL)); both are marshalable, both leave the store
            // untouched, and both pass straight through. Everything else is "unavailable".
            val assigned = try {
                TagStore(store).assign(text, notebookId, pageId)
            } catch (e: StoreUnavailable) {
                throw IllegalStateException(STORE_UNAVAILABLE)
            }
            Slog.d(TAG) {
                "assign: ${text.length} chars, changed=${assigned.changed} " +
                    "in ${SystemClock.elapsedRealtime() - t0} ms"
            }
            return assigned.display
        }

        private fun enforce() = HostCallerCheck.enforce(this@TagManagerService, BuildConfig.HOST_PACKAGE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "TagManagerService"

        /** The store binder is gone (`begin` never ran, or the host revoked it), or the store could
         *  not be reached at all. One of the three exceptions that survive Binder marshalling. */
        const val STORE_UNAVAILABLE = "store unavailable"
    }
}
