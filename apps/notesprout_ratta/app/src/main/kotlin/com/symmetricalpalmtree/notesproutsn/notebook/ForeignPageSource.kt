package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A **near-read-only** open of a browsed notebook's `.soil` for the link picker's foreign page grid
 * (arc 6 / K2) — previews and labels of a notebook that is not the open one. The current notebook
 * must never come through here (the picker hides it in both Notebook modes; its file is already
 * open by the session, and one file never has two connections — the family rule).
 *
 * Opened lazily on the first read, through the one [SoilDatabase.open] door (global key from
 * [KeySession], cached-raw-key fast path; **never creates** the file). The picker holds at most one
 * instance at a time (per drilled notebook) and MUST [sealAsync] it when the drill is left — mode
 * switch, a different notebook, or the screen's destroy. The seal runs on a process-scoped IO job
 * under [NonCancellable], because the destroy path's lifecycle scope is already dead when it fires;
 * an unsealed open would strand the connection and its WAL sidecar for the process lifetime (the R6
 * lesson).
 *
 * [createPage] (K3) is the **one sanctioned write**: the picker's New page, in the notebook the user
 * has drilled into. It runs through the same [withDb] discipline as every read, so it can never race
 * the seal, and it still goes through this single open — never a parallel connection to a file this
 * source already holds.
 *
 * A failed open (file gone, no key session, bad file) is remembered and every read answers empty
 * — the picker shows its empty state; the honest "target is gone" moment belongs to the follow
 * (K4), not to browsing.
 */
class ForeignPageSource(
    context: Context,
    private val notebookId: String,
    private val repo: IndexRepository = IndexRepository(),
) : PickerPageSource {

    private val app = context.applicationContext
    private val lock = Mutex()
    private var db: SoilDatabase? = null
    private var failed = false
    /** Volatile: flipped synchronously by [sealAsync] on the caller's thread, read under [lock]. */
    @Volatile private var sealed = false

    override suspend fun pages(): List<PickerPage> =
        withDb { PageReads.pages(it.dao(), notebookId) } ?: emptyList()

    override suspend fun content(pageId: String): PageContent? =
        withDb { PageReads.content(it.dao(), pageId) }

    /**
     * Insert a blank page in this notebook (K3), by the same anchor rule as the session's
     * `insertAt`: before / after the selected card, appending when nothing is selected or the anchor
     * has vanished, inheriting template and authored size from the anchor (or the last page).
     * Upsert + renumber ride one transaction, then the index is mirrored so the library's page count
     * and clock stay honest about a notebook that is not open.
     *
     * Null on **any** failure — a sealed or failed source, a notebook with no page rows, a write
     * that threw — and the picker explains rather than pretending something happened.
     */
    suspend fun createPage(anchorPageId: String?, before: Boolean): PickerPage? = withDb { soil ->
        val dao = soil.dao()
        val rows = dao.childrenOfType(notebookId, SoilSchema.TYPE_PAGE)
        if (rows.isEmpty()) return@withDb null
        val ids = rows.map { it.id }
        val pos = LinkPickerModel.insertIndexFor(ids, anchorPageId, before)
        val inherit = rows[LinkPickerModel.inheritIndexFor(ids, anchorPageId)]
        val now = System.currentTimeMillis()
        val newRow = SoilObjectEntity(
            id = java.util.UUID.randomUUID().toString(),
            parentId = notebookId, type = SoilSchema.TYPE_PAGE, order = pos,
            createdAt = now, updatedAt = now,
            refId = inherit.refId, width = inherit.width, height = inherit.height,
        )
        val ordered = rows.toMutableList().apply { add(pos, newRow) }
        soil.withTransaction {
            dao.upsert(newRow)
            // Only the rows that actually moved are written; the new row was upserted at `pos`.
            ordered.forEachIndexed { i, row -> if (row.order != i) dao.setOrder(row.id, i, now) }
        }
        repo.setPageCount(notebookId, ordered.size)
        repo.touch(notebookId, now)
        Slog.d(TAG) { "picker inserted a page at $pos in $notebookId (${ordered.size} pages)" }
        PickerPage(newRow.id, (newRow.width ?: 0f).toInt(), (newRow.height ?: 0f).toInt())
    }

    /** Checkpoint + close, fire-and-forget, idempotent — see the class KDoc for why not a
     *  lifecycle scope. Reads after this answer empty — [sealed] flips synchronously, so a read
     *  racing the queued seal can never re-open. The launched job is published as [lastSeal] so
     *  the **next** instance's open waits behind it (see [openLocked]). */
    fun sealAsync() {
        sealed = true
        lastSeal = sealScope.launch {
            withContext(NonCancellable) {
                lock.withLock {
                    db?.seal(soilFile(app, notebookId))   // never throws (its own contract)
                    db = null
                }
            }
        }
    }

    private suspend fun <T> withDb(block: suspend (SoilDatabase) -> T): T? = lock.withLock {
        if (sealed || failed) return@withLock null
        val open = db ?: openLocked() ?: return@withLock null
        try {
            block(open)
        } catch (e: Exception) {
            Log.w(TAG, "foreign read failed for $notebookId", e)
            null
        }
    }

    private suspend fun openLocked(): SoilDatabase? {
        // Order this open behind the previous instance's still-pending seal: leave-drill →
        // immediate re-drill into the same notebook would otherwise hold two live connections to
        // one .soil (and K3's createPage a concurrent writer) — the sticky-lock crash family.
        // Cheap and safe across different notebooks too, so it is not keyed. Never our own seal:
        // sealAsync flips [sealed] synchronously, and withDb answers null before reaching here.
        lastSeal?.join()
        val passphrase = KeySession.get() ?: run { failed = true; return null }
        val file = soilFile(app, notebookId)
        if (!file.exists() || file.length() == 0L) { failed = true; return null }
        return try {
            withContext(Dispatchers.IO) { SoilDatabase.open(app, notebookId, file, passphrase) }
                .also { db = it }
        } catch (e: Exception) {
            Log.w(TAG, "foreign open failed for $notebookId", e)
            failed = true
            null
        }
    }

    private companion object {
        const val TAG = "ForeignPageSource"

        /** Outlives any Activity so a destroy-time seal always completes. */
        val sealScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The most recently queued seal, awaited by the next open — the cross-instance ordering
         *  the per-instance [lock] cannot provide (K5 review). */
        @Volatile var lastSeal: Job? = null
    }
}
