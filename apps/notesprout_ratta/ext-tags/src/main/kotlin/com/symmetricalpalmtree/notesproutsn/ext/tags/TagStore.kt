package com.symmetricalpalmtree.notesproutsn.ext.tags

import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.AssignmentRecord
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Row
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads
import com.symmetricalpalmtree.notesproutsn.extension.TagPages
import com.symmetricalpalmtree.notesproutsn.extension.TagRecord
import com.symmetricalpalmtree.notesproutsn.extension.TagRules
import java.util.UUID

/** The extension cannot reach its storage (any store exception — the host's rule: treat all as unavailable). */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/**
 * The tag index's tables over the host's `IExtensionStore` (arc 21 / W1, rewritten onto rows in
 * arc 22 / X3). **Blocking** — every call runs on `Dispatchers.IO` (the screen) or a Binder thread
 * (the service's calls), never Main. The extension writes nothing to disk itself: this store is the
 * host's, lent for the showing or for the call.
 *
 * The schema is [TagSchema.V1] and [load] applies it — the ONE door, because the host's gate refuses
 * `exec` / `query` on a binder that has not declared. Every public method applies it first: that is
 * idempotent, and a matching version costs one `SELECT` host-side, which is the price of never
 * having to reason about whether some other path declared first.
 *
 * **The transaction is the lock.** Arc 21's `TagWrites` held a process-local monitor around a
 * read-modify-write of one blob, because two writers each applying their change to the version they
 * happened to be holding is how one silently erases the other. There is no blob and no monitor now:
 * every write is a statement (or two, in one batch) that is correct whoever else is writing, and the
 * caps ride *inside* the inserts rather than being checked beforehand. That also covers the case the
 * monitor never could — the host restarting, or two host processes.
 *
 * Every exception that is not one of this class's own typed failures becomes [StoreUnavailable];
 * the one exception to that is the `IllegalStateException(TAG_INDEX_FULL)` [assign] raises, which is
 * a refusal, not a broken store, and both callers say it differently.
 */
class TagStore(
    private val store: IExtensionStore,
    /** The tag cap. The contract's, overridden only by tests that would otherwise have to build five
     *  thousand rows to reach it — it is bound **into the insert**, so a test that lowers it is
     *  exercising the real statement. */
    private val maxTags: Int = ExtensionContract.MAX_TAGS,
    /** The assignment cap, on the same terms. */
    private val maxAssignments: Int = ExtensionContract.MAX_TAG_ASSIGNMENTS,
) {

    /** How much of the library one tag reaches — the numbers the delete confirm names. */
    class Usage(val notebooks: Int, val pages: Int) {
        val total: Int get() = notebooks + pages
    }

    /** What [assign] did: the tag's canonical display form, and whether anything was written. */
    class Assigned(val display: String, val changed: Boolean)

    // ── Declaring ────────────────────────────────────────────────────────────

    /** Declare the schema. Idempotent, and the only door — nothing may reach the store before it. */
    fun load() = guard { store.applySchema(TagSchema.V1) }

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * Every tag in the library, in browse order. Paged through [TagPages] because a page is bounded
     * by `LIMIT`, not by the reply's size: at [ExtensionContract.MAX_TAGS] and 64 characters apiece
     * the whole list is well under half a megabyte, so this never comes near the host's result cap —
     * the paging is here so that one loop, and one page size, serve the store side and the Binder
     * side alike.
     */
    fun tags(): List<TagRecord> = guard {
        store.applySchema(TagSchema.V1)
        TagPages.collect(ExtensionContract.TAGS_PAGE, MAX_TAG_PAGES) { offset -> readTags(offset) }
    }

    /** One page of [tags], for the service's `ITagManager.tags`. */
    fun tagsPage(offset: Int): List<TagRecord> = guard {
        store.applySchema(TagSchema.V1)
        readTags(offset)
    }

    /** Every assignment in one notebook — its own and every one of its pages'. The screen's read:
     *  a notebook's rows are few, so BROWSE and ADD filter them in memory rather than asking again. */
    fun assignmentsOfNotebook(notebookId: String): List<AssignmentRecord> = guard {
        store.applySchema(TagSchema.V1)
        decodeAssignments(StoreReads.all(store, TagSql.selectAssignmentsOfNotebook(notebookId)))
    }

    /** One page of the assignments of [tagIds], for the service's `ITagManager.assignmentsOf`. */
    fun assignmentsOf(tagIds: List<String>, offset: Int): List<AssignmentRecord> {
        if (tagIds.isEmpty()) return emptyList()
        return guard {
            store.applySchema(TagSchema.V1)
            decodeAssignments(
                StoreReads.all(
                    store,
                    TagSql.selectAssignmentsOf(tagIds, ExtensionContract.ASSIGNMENTS_PAGE, offset),
                ),
            )
        }
    }

    /** The blast radius of deleting [tagId]. `SUM` over no rows is NULL, which reads as 0. */
    fun usageOf(tagId: String): Usage = guard {
        store.applySchema(TagSchema.V1)
        val row = StoreReads.all(store, TagSql.selectUsage(tagId)).rows.firstOrNull()
        Usage(
            notebooks = (row?.longOrNull("notebooks") ?: 0L).toInt(),
            pages = (row?.longOrNull("pages") ?: 0L).toInt(),
        )
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Normalize [text], create the tag if the library has never seen it, and attach it to the
     * target — the whole of `assign` in two small reads and one two-statement transaction.
     *
     * Idempotent: a tag already on the target writes **nothing** and answers `changed = false`, so a
     * double tap on e-ink costs one read. Creation keeps the **first** casing (the wizard's rule),
     * and the answer is always the **stored** display — which is the honest one even when a
     * concurrent writer won the create with a different spelling.
     *
     * The post-write re-read is what turns a cap refusal back into a failure: `INSERT OR IGNORE`
     * reports a cap that refused it as silence, so the only way to know is to look.
     *
     * @throws IllegalArgumentException [text] is not a tag, or an id is not a canonical UUID.
     * @throws IllegalStateException [ExtensionContract.TAG_INDEX_FULL] — a cap refused it.
     * @throws StoreUnavailable the store could not be reached.
     */
    fun assign(text: String, notebookId: String, pageId: String?): Assigned {
        require(TagRules.isValid(text)) { "not a tag" }
        require(TagRules.isId(notebookId)) { "notebook id is not a UUID" }
        require(pageId == null || TagRules.isId(pageId)) { "page id is not a UUID" }
        val display = TagRules.display(text)
        val key = TagRules.identityKey(text)
        val page = pageId ?: ""

        val assigned = guard {
            store.applySchema(TagSchema.V1)
            val before = identity(key, notebookId, page)
            if (before != null && before.attached) {
                return@guard Assigned(before.display, changed = false)
            }
            val now = System.currentTimeMillis()
            val batch = ArrayList<Statement>(2)
            // Only when the identity is not there yet — and even then the insert may lose to a
            // concurrent creator, which is why the assignment below resolves the id by identity.
            if (before == null) {
                batch += TagSql.insertTag(UUID.randomUUID().toString(), display, key, now, maxTags, maxAssignments)
            }
            batch += TagSql.insertAssignment(key, notebookId, page, now, maxAssignments)
            StoreReads.exec(store, batch)

            val after = identity(key, notebookId, page)
            // No tag at all: a cap refused the insert (the tag's, or the assignment's — a new tag is
            // gated on both, so a refused attachment never leaves an orphan tag). A tag that is
            // still not attached: the assignment cap did. Neither wrote anything, and both are the
            // same sentence to a user.
            if (after == null || !after.attached) null else Assigned(after.display, changed = true)
        }
        return assigned ?: throw IllegalStateException(ExtensionContract.TAG_INDEX_FULL)
    }

    /**
     * Detach [tagId] from one target; true when a row went. **The tag itself stays** — the wizard's
     * lifecycle call: a tag persists until it is explicitly deleted, so removing its last assignment
     * leaves it in the suggestion list, ready to be used again.
     */
    fun unassign(tagId: String, notebookId: String, pageId: String?): Boolean = guard {
        store.applySchema(TagSchema.V1)
        StoreReads.exec(store, TagSql.deleteAssignment(tagId, notebookId, pageId ?: "")) > 0L
    }

    /** Delete a tag and every assignment of it (the declared cascade); true when a row went. */
    fun deleteTag(tagId: String): Boolean = guard {
        store.applySchema(TagSchema.V1)
        StoreReads.exec(store, TagSql.deleteTag(tagId)) > 0L
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** A tag identity as the store has it, plus whether it is already on the target asked about. */
    private class Identity(val id: String, val display: String, val attached: Boolean)

    private fun identity(key: String, notebookId: String, pageId: String): Identity? {
        val row = StoreReads.all(store, TagSql.selectTagByIdentity(key, notebookId, pageId))
            .rows.firstOrNull() ?: return null
        return Identity(row.text("id"), row.text("display"), row.long("attached") != 0L)
    }

    private fun readTags(offset: Int): List<TagRecord> {
        val rows = StoreReads.all(store, TagSql.selectTags(ExtensionContract.TAGS_PAGE, offset))
        val decoded = TagRows.tags(rows)
        if (decoded.dropped > 0) Log.w(TAG, "${decoded.dropped} tag row(s) dropped")
        return decoded.records
    }

    private fun decodeAssignments(rows: Iterable<Row>): List<AssignmentRecord> {
        val decoded = TagRows.assignments(rows)
        if (decoded.dropped > 0) Log.w(TAG, "${decoded.dropped} assignment row(s) dropped")
        return decoded.records
    }

    /**
     * Every failure is [StoreUnavailable] — the host's own rule, and the extension's whole answer to
     * a store it cannot reach. The one thing that passes through is the cap refusal, which is a
     * decision the store made on purpose and is compared **verbatim**, never as a substring.
     */
    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: StoreUnavailable) {
            throw e
        } catch (e: IllegalStateException) {
            if (e.message == ExtensionContract.TAG_INDEX_FULL) throw e else throw StoreUnavailable(e)
        } catch (e: Exception) {
            throw StoreUnavailable(e)
        }

    companion object {
        private const val TAG = "TagStore"

        /** The runaway guard on [tags]' paging — one page more than the cap can fill. */
        private val MAX_TAG_PAGES: Int = ExtensionContract.MAX_TAGS / ExtensionContract.TAGS_PAGE + 1
    }
}
