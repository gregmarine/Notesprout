package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A tag cap refused the operation (`TAG_INDEX_FULL`) — nothing was written. */
class TagIndexFullException(cause: Throwable) : ExtensionCallException(ExtensionContract.TAG_INDEX_FULL, cause)

/**
 * What one search run read out of the tag extension (arc 22 / X3): the library's tags, and the
 * assignments of **only the tags the host's own matching selected**. Two queries, no snapshot.
 */
class TagSearch(val tags: List<TagRecord>, val assignments: List<AssignmentRecord>)

/**
 * The host's client for the one tag manager (arc 21 / W1) — SN's **second** held bind, and the first
 * client to use both bind shapes against one interface.
 *
 * **The showing** ([open] / [finish]) is a held bind, the scratch pad's bracket: `ExtensionStores.open`
 * on IO (the pre-open rule — a cold KDF is seconds on the Nomad and must never sit inside a call
 * timeout) → mint one uid-bound [ExtensionStoreBinder] → [ExtensionBinder.hold] (signature re-checked
 * at bind) → `begin(store)` → `configureShowing(showing)` → the screen Intent, which carries the
 * action and the package and **nothing else**: the target, its label and any prefill went over the
 * bind, because a tag is the user's own words. Returns null on any failure (reason logged; everything
 * opened so far is released). The caller launches with an `ActivityResultLauncher` — a plain
 * `startActivity` leaves the extension's `callingPackage` null and its screen refuses it.
 *
 * **The two calls** ([search], [assign]) are bind-per-call, the recognizer's shape, because the
 * operation *is* the call and nothing is shown. [assign] is the lasso's silent heading→tag (W3);
 * [search] is what the library's search merge reads (W4, rewritten as two paged queries in arc 22 /
 * X3 — the whole-index `snapshot` is gone). Both still pre-open the store.
 *
 * Log tag [TAG] — counts, lengths and durations. **A tag is never logged.**
 */
class TagClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<ITagManager>? = null
    private var storeBinder: ExtensionStoreBinder? = null

    val isOpen: Boolean get() = held != null

    /** Pre-open the store, hold the bind, `begin` + `configureShowing`, and build the screen Intent — or null (logged). */
    suspend fun open(showing: TagShowing): Intent? {
        if (held != null) { Slog.d(TAG) { "open: already open" }; return null }
        val t0 = System.currentTimeMillis()
        val store = openStore() ?: return null
        val binding = try {
            ExtensionBinder.hold(appContext, ref, ExtensionContract.ACTION_TAG_MANAGER, TAG,
                asInterface = { ITagManager.Stub.asInterface(it) })
        } catch (e: CancellationException) {
            store.revoke(); throw e
        } catch (e: ExtensionCallException) {
            store.revoke()
            Slog.d(TAG) { "open failed: hold ${e.message}" }
            return null
        }
        held = binding
        storeBinder = store
        try {
            binding.call(CALL_TIMEOUT_MS) { it.begin(store) }
            binding.call(CALL_TIMEOUT_MS) { it.configureShowing(showing) }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "open failed: begin/configure ${e.message}" }
            finish()
            return null
        }
        Slog.d(TAG) { "open: ready in ${System.currentTimeMillis() - t0} ms (mode=${showing.mode})" }
        return Intent(ExtensionContract.ACTION_TAG_MANAGER_SCREEN).setPackage(ref.packageName)
    }

    /** `end()` (best effort, ≤ [CALL_TIMEOUT_MS]), then unbind + revoke in `finally`. Idempotent. */
    suspend fun finish() {
        val binding = held ?: return
        held = null
        val store = storeBinder
        storeBinder = null
        try {
            if (!binding.isDead) binding.call(CALL_TIMEOUT_MS) { it.end() }
            Slog.d(TAG) { "finish: end ok" }
        } catch (e: CancellationException) {
            throw e   // the caller's scope is gone — the finally below still releases the bind
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "finish: end failed ${e.message}" }
        } finally {
            binding.close()
            store?.revoke()
        }
    }

    /** Open the extension's store on IO and wrap it in a uid-bound binder, or null (logged). */
    private suspend fun openStore(): ExtensionStoreBinder? =
        try {
            val db = withContext(Dispatchers.IO) { ExtensionStores.open(appContext, ref.packageName) }
            val extUid = appContext.packageManager.getPackageUid(ref.packageName, 0)
            ExtensionStoreBinder(db, extUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageManager.NameNotFoundException) {
            Slog.d(TAG) { "store open failed: package gone ${ref.packageName}" }
            null
        } catch (e: Exception) {
            Slog.d(TAG) { "store open failed: ${e.javaClass.simpleName}: ${e.message}" }
            null
        }

    companion object {
        const val TAG = "TagClient"
        const val CALL_TIMEOUT_MS = 2_000L

        /**
         * [search]'s budget, and the first cut only (arc 22 / X3).
         *
         * The work it has to cover is bounded by the caps: at worst ten pages of tags plus fifty
         * pages of assignments, each one Binder round trip against SQLCipher. Ten seconds is a
         * generous ceiling for that on the Nomad and a deliberately loose one, because a timeout
         * here does not undo anything — a Binder call cannot be cancelled, so an orphaned call
         * finishes on its own thread while the shelf has already fallen back to names only.
         * **To be re-measured on the Nomad** against the two counts this call logs; the W6 rule
         * stands, a budget is sized by the work and not by taste.
         */
        const val SEARCH_TIMEOUT_MS = 10_000L

        /**
         * [assign]'s budget. It is smaller than it was (8 s) because the work is smaller: arc 21's
         * assign decoded the whole index, edited it, re-encoded it and wrote up to four megabytes
         * back through the large-value path; X3's is two small indexed reads and one two-statement
         * transaction.
         *
         * The W6 rule still decides the number, not the shrinkage: a budget is sized by the work,
         * and a timeout does not undo anything — a Binder call cannot be cancelled, so the orphaned
         * call finishes on its own thread and the tag lands **after** the host has already told the
         * user "Nothing has been changed". Four seconds is the honest budget for two reads and a
         * transaction on a cold-ish store, and keeping it honest is what keeps that sentence true.
         */
        const val ASSIGN_TIMEOUT_MS = 4_000L

        /** The runaway guards on the two paging loops — one page more than each cap can fill. */
        private val TAG_PAGES: Int = ExtensionContract.MAX_TAGS / ExtensionContract.TAGS_PAGE + 1
        private val ASSIGNMENT_PAGES: Int =
            ExtensionContract.MAX_TAG_ASSIGNMENTS / ExtensionContract.ASSIGNMENTS_PAGE + 1

        /**
         * **W4's door, on rows** (arc 22 / X3): the library's tags, and the assignments of the ones
         * [select] picks out of them.
         *
         * One pre-opened store and **one bind** for both halves. Inside it:
         *  1. `tags(store, offset)` is paged until a short page — the whole tag list, which is small
         *     (5 000 records at most) and is what the host's `FuzzyRank` runs over;
         *  2. [select] is the host's own matching, run **here**, on the IO thread the call block
         *     already occupies — it is pure CPU over strings and it decides how little step 3 reads;
         *  3. `assignmentsOf(store, chunk, offset)` for each `ASSIGNMENT_QUERY_TAGS`-sized chunk of
         *     the selection, each chunk paged the same way. An empty selection asks nothing at all,
         *     which is the common case for a query that only matches names.
         *
         * That is the whole point of the two-query shape: arc 21 read every assignment in the
         * library — up to fifty thousand — through ashmem on every keystroke-free search run, and
         * threw nearly all of them away.
         *
         * @throws ExtensionCallException the bind, a call or a reply failed.
         */
        suspend fun search(
            context: Context,
            ref: ProviderRef,
            select: (List<TagRecord>) -> Collection<String>,
        ): TagSearch {
            val client = TagClient(context, ref)
            val store = client.openStore() ?: throw ExtensionCallException("store unavailable")
            val t0 = System.currentTimeMillis()
            try {
                val result = ExtensionBinder.call(
                    client.appContext, ref, ExtensionContract.ACTION_TAG_MANAGER, TAG,
                    asInterface = { ITagManager.Stub.asInterface(it) },
                    callTimeoutMs = SEARCH_TIMEOUT_MS,
                ) { iface ->
                    val tags = TagPages.collect(ExtensionContract.TAGS_PAGE, TAG_PAGES) { offset ->
                        iface.tags(store, offset) ?: emptyList()
                    }
                    val selected = select(tags).toList()
                    val assignments = ArrayList<AssignmentRecord>()
                    for (chunk in selected.chunked(ExtensionContract.ASSIGNMENT_QUERY_TAGS)) {
                        assignments += TagPages.collect(
                            ExtensionContract.ASSIGNMENTS_PAGE, ASSIGNMENT_PAGES,
                        ) { offset -> iface.assignmentsOf(store, chunk, offset) ?: emptyList() }
                    }
                    TagSearch(tags, assignments)
                }
                Slog.d(TAG) {
                    "search: ${result.tags.size} tags, ${result.assignments.size} assignments " +
                        "in ${System.currentTimeMillis() - t0} ms"
                }
                return result
            } finally {
                store.revoke()
            }
        }

        /**
         * Create-if-absent and attach [text] to one target, answering with the tag's canonical
         * display form (the casing it was first entered in) — **W3's door**, the lasso's silent
         * heading→tag.
         *
         * The target is a pair: [notebookId] always, [pageId] only for a tag on one page of it
         * (arc 21 / W4). Both must be canonical UUIDs.
         *
         * @throws TagIndexFullException a cap refused it; nothing was written.
         * @throws ExtensionCallException anything else, including text that is not a tag.
         */
        suspend fun assign(
            context: Context,
            ref: ProviderRef,
            text: String,
            notebookId: String,
            pageId: String?,
        ): String {
            val client = TagClient(context, ref)
            val store = client.openStore() ?: throw ExtensionCallException("store unavailable")
            val t0 = System.currentTimeMillis()
            try {
                val display = ExtensionBinder.call(
                    client.appContext, ref, ExtensionContract.ACTION_TAG_MANAGER, TAG,
                    asInterface = { ITagManager.Stub.asInterface(it) },
                    callTimeoutMs = ASSIGN_TIMEOUT_MS,
                ) { iface ->
                    try {
                        iface.assign(store, text, notebookId, pageId)
                    } catch (e: IllegalStateException) {
                        // Compared verbatim, never as a substring (the family rule).
                        if (e.message == ExtensionContract.TAG_INDEX_FULL) throw TagIndexFullException(e)
                        throw e
                    }
                } ?: throw ExtensionCallException("assign returned nothing")
                Slog.d(TAG) { "assign: ${text.length} chars in ${System.currentTimeMillis() - t0} ms" }
                return display
            } finally {
                store.revoke()
            }
        }
    }
}
