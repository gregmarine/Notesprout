package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.room.withTransaction
import com.symmetricalpalmtree.notesproutsn.core.Bitmaps
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMetaStore
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One page of the open notebook — the geometry the paper is set to; strokes come from [StrokeStore]. */
data class PageRef(val id: String, val order: Int, val width: Int, val height: Int, val templateId: String)

/**
 * The open notebook: owns its [SoilDatabase], the page list, the current page and the decoded
 * template bitmap. Every function is `suspend` and works on IO. Created by [NotebookActivity],
 * one per screen; left via [seal].
 *
 * Page structure — [insertBlank], [deleteCurrent], [reconcile] — is also here, because the page
 * list and the row writes have to move together. Each one does its row work inside a single
 * transaction, renumbers `"order"` to a dense 0..N-1, then mirrors the result into the index
 * (`pageCount` + `updatedAt`). Pages are soft-deleted like everything else in the family, which is
 * exactly what makes undo a [reconcile] rather than a re-creation.
 */
class NotebookSession(
    context: Context,
    val notebookId: String,
    private val repo: IndexRepository,
) {
    private val app = context.applicationContext
    val file: File = soilFile(app, notebookId)

    lateinit var db: SoilDatabase
        private set
    lateinit var store: StrokeStore
        private set

    var pages: List<PageRef> = emptyList()
        private set
    var currentIndex: Int = 0
        private set
    val currentPage: PageRef get() = pages[currentIndex]

    /** Decoded template of the current page (bounded decode), or null for a blank page. */
    var template: Bitmap? = null
        private set
    private var templateIdLoaded: String? = null

    val isOpen: Boolean get() = ::db.isInitialized && db.isOpen

    sealed class OpenResult {
        object Ok : OpenResult()
        class Failed(val reason: String) : OpenResult()
    }

    /**
     * Open the file (raw-key fast path when cached; **never creates** — the new-notebook flow is
     * the only creator), read the page list and the last-open page, decode its template. A
     * missing/empty file or a file with no pages is a [OpenResult.Failed] — the caller explains
     * and finishes; nothing is ever fabricated here.
     */
    suspend fun open(): OpenResult = withContext(Dispatchers.IO) {
        val passphrase = KeySession.get() ?: return@withContext OpenResult.Failed("No key session")
        if (!file.exists() || file.length() == 0L) return@withContext OpenResult.Failed("Notebook file is missing")
        try {
            db = SoilDatabase.open(app, notebookId, file, passphrase)
        } catch (e: Exception) {
            Log.e(TAG, "open failed for $notebookId", e)
            return@withContext OpenResult.Failed(e.message ?: "Could not open notebook")
        }
        store = StrokeStore(db.dao()) { repo.touch(notebookId) }
        val dao = db.dao()
        val root = dao.notebookRow()
        val pageRows = dao.childrenOfType(notebookId, SoilSchema.TYPE_PAGE)
        if (pageRows.isEmpty()) {
            runCatching { db.seal(file) }
            return@withContext OpenResult.Failed("Notebook has no pages")
        }
        pages = pageRows.mapIndexed { i, row -> row.toPageRef(i) }
        val lastOpen = root?.refId
        currentIndex = pages.indexOfFirst { it.id == lastOpen }.takeIf { it >= 0 } ?: 0
        loadTemplateFor(currentPage)
        Slog.d(TAG) { "opened $notebookId: ${pages.size} pages, at $currentIndex" }
        OpenResult.Ok
    }

    /** Point the notebook row at [pageId] as the last-open page (a reopen restores it). */
    suspend fun saveLastOpened(pageId: String = currentPage.id) = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext
        db.dao().setRefId(notebookId, pageId, System.currentTimeMillis())
    }

    // ── Page navigation & structure ──────────────────────────────────────────

    /** Move to [index] without changing structure; decodes the target page's template. */
    suspend fun goTo(index: Int): PageRef = withContext(Dispatchers.IO) {
        currentIndex = index.coerceIn(0, pages.lastIndex)
        loadTemplateFor(currentPage)
        currentPage
    }

    /**
     * One page insert or delete, described well enough to replay in either direction through
     * [reconcile]: the live page ids [before] and [after] (in order), the page the notebook was on
     * either side, and the stroke ids the operation soft-deleted (empty for an insert).
     */
    data class Structural(
        val before: List<String>,
        val after: List<String>,
        val strokeIds: List<String>,
        val beforeCurrentId: String,
        val afterCurrentId: String,
    )

    /**
     * Insert a blank page next to the current one and land on it. The new page inherits the current
     * page's template and authored size, so a notebook stays one consistent paper.
     */
    suspend fun insertBlank(after: Boolean): Structural = withContext(Dispatchers.IO) {
        val cur = currentPage
        val before = pages.map { it.id }
        val now = System.currentTimeMillis()
        val newId = java.util.UUID.randomUUID().toString()
        val pos = PageMath.insertPosition(currentIndex, after)
        val newRow = SoilObjectEntity(
            id = newId, parentId = notebookId, type = SoilSchema.TYPE_PAGE, order = pos,
            createdAt = now, updatedAt = now,
            refId = cur.templateId, width = cur.width.toFloat(), height = cur.height.toFloat(),
        )
        val newPages = pages.toMutableList().apply { add(pos, newRow.toPageRef(pos)) }
        db.withTransaction {
            db.dao().upsert(newRow)
            renumber(newPages, now)
        }
        pages = newPages.reindexed()
        currentIndex = pos
        loadTemplateFor(currentPage)
        mirror(now)
        Slog.d(TAG) { "inserted $newId at $pos (${pages.size} pages)" }
        Structural(before, pages.map { it.id }, emptyList(), cur.id, newId)
    }

    /**
     * Soft-delete the current page and its live strokes, then land on the previous page. Deleting
     * the **only** page puts a fresh blank in its place instead — a notebook always has ≥ 1 page,
     * and an empty one would have nothing to draw on and nothing to open next time.
     */
    suspend fun deleteCurrent(): Structural = withContext(Dispatchers.IO) {
        val victim = currentPage
        val before = pages.map { it.id }
        val now = System.currentTimeMillis()
        val strokeIds = db.dao().liveStrokeIds(victim.id)
        if (pages.size == 1) {
            val newId = java.util.UUID.randomUUID().toString()
            val replacement = SoilObjectEntity(
                id = newId, parentId = notebookId, type = SoilSchema.TYPE_PAGE, order = 0,
                createdAt = now, updatedAt = now,
                refId = victim.templateId, width = victim.width.toFloat(), height = victim.height.toFloat(),
            )
            db.withTransaction {
                db.dao().upsert(replacement)
                db.dao().softDelete(listOf(victim.id), now)
                if (strokeIds.isNotEmpty()) db.dao().softDelete(strokeIds, now)
            }
            pages = listOf(replacement.toPageRef(0))
            currentIndex = 0
            loadTemplateFor(currentPage)
            mirror(now)
            Slog.d(TAG) { "deleted the only page ${victim.id}, replaced with $newId" }
            return@withContext Structural(before, listOf(newId), strokeIds, victim.id, newId)
        }
        val remaining = pages.filter { it.id != victim.id }
        db.withTransaction {
            db.dao().softDelete(listOf(victim.id), now)
            if (strokeIds.isNotEmpty()) db.dao().softDelete(strokeIds, now)
            renumber(remaining, now)
        }
        pages = remaining.reindexed()
        currentIndex = PageMath.indexAfterDelete(before.indexOf(victim.id), before.size)
        loadTemplateFor(currentPage)
        mirror(now)
        Slog.d(TAG) { "deleted ${victim.id} + ${strokeIds.size} strokes (${pages.size} pages)" }
        Structural(before, pages.map { it.id }, strokeIds, victim.id, currentPage.id)
    }

    /**
     * Make the live page set exactly [targetAlive], in that order, restoring and soft-deleting the
     * given strokes with it, and land on [currentId]. This is the undo/redo primitive behind
     * [insertBlank] and [deleteCurrent] — both directions are the same call with the snapshot's two
     * sides swapped.
     */
    suspend fun reconcile(
        targetAlive: List<String>,
        restoreStrokeIds: List<String>,
        deleteStrokeIds: List<String>,
        currentId: String,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val alive = db.dao().childrenOfType(notebookId, SoilSchema.TYPE_PAGE).map { it.id }.toSet()
        val restorePages = PageMath.toRestore(alive, targetAlive)
        val deletePages = PageMath.toDelete(alive, targetAlive)
        db.withTransaction {
            if (restorePages.isNotEmpty()) db.dao().restore(restorePages, now)
            if (deletePages.isNotEmpty()) db.dao().softDelete(deletePages, now)
            if (restoreStrokeIds.isNotEmpty()) db.dao().restore(restoreStrokeIds, now)
            if (deleteStrokeIds.isNotEmpty()) db.dao().softDelete(deleteStrokeIds, now)
            targetAlive.forEachIndexed { i, id -> db.dao().setOrder(id, i, now) }
        }
        val rows = db.dao().byIds(targetAlive).associateBy { it.id }
        pages = targetAlive.mapIndexedNotNull { i, id -> rows[id]?.toPageRef(i) }
        currentIndex = pages.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        loadTemplateFor(currentPage)
        mirror(now)
        Slog.d(TAG) { "reconciled to ${pages.size} pages, at $currentIndex" }
    }

    /** Dense 0..N-1 `"order"`; only the rows that actually moved are written. */
    private suspend fun renumber(list: List<PageRef>, now: Long) {
        list.forEachIndexed { i, p -> if (p.order != i) db.dao().setOrder(p.id, i, now) }
    }

    private fun List<PageRef>.reindexed(): List<PageRef> = mapIndexed { i, p -> p.copy(order = i) }

    /** The index is the library's view of this notebook — keep its page count and clock honest. */
    private suspend fun mirror(now: Long) {
        repo.setPageCount(notebookId, pages.size)
        repo.touch(notebookId, now)
    }

    /** Refresh `notebook_meta` from the index (name, folder path) — the file stays self-describing. */
    suspend fun refreshMeta(appVersionCode: Int) = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext
        val row = repo.get(notebookId) ?: return@withContext
        val existing = NotebookMetaStore.read(db.raw())
        NotebookMetaStore.write(db.raw(), NotebookMeta(
            notebookId = notebookId, name = row.name,
            createdAt = existing?.createdAt ?: row.createdAt, updatedAt = row.updatedAt,
            folderPath = repo.ancestry(row.parentId), appVersionCode = appVersionCode,
        ))
    }

    /** Wait for queued stroke writes, then checkpoint + close. Idempotent; never throws. */
    suspend fun seal() = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext
        try { store.flushTouch() } catch (e: Exception) { Log.w(TAG, "flushTouch failed", e) }
        try { store.drain() } catch (e: Exception) { Log.w(TAG, "drain failed", e) }
        store.close()
        db.seal(file)
        template?.recycle()
        template = null
        Slog.d(TAG) { "sealed $notebookId" }
    }

    // ── Template ─────────────────────────────────────────────────────────────

    private suspend fun loadTemplateFor(page: PageRef) {
        if (page.templateId == templateIdLoaded) return
        template?.recycle()
        template = null
        templateIdLoaded = page.templateId
        if (page.templateId.isEmpty()) return
        val row = db.dao().byId(page.templateId) ?: return
        template = Bitmaps.decodeBounded(row.blob, MAX_TEMPLATE_EDGE)
    }

    private fun SoilObjectEntity.toPageRef(order: Int = this.order) = PageRef(
        id = id, order = order,
        width = (width ?: 0f).toInt(), height = (height ?: 0f).toInt(),
        templateId = refId ?: "",
    )

    companion object {
        private const val TAG = "NotebookSession"
        const val MAX_TEMPLATE_EDGE = 4096
    }
}
