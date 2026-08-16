package com.symmetricalpalmtree.notesprout.notebook

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.symmetricalpalmtree.notesprout.core.Bitmaps
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.crypto.KeySession
import com.symmetricalpalmtree.notesprout.data.index.IndexRepository
import com.symmetricalpalmtree.notesprout.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesprout.data.soil.NotebookMetaStore
import com.symmetricalpalmtree.notesprout.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesprout.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema
import com.symmetricalpalmtree.notesprout.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One page of the open notebook — geometry the paper is set to; strokes come from [StrokeStore]. */
data class PageRef(val id: String, val order: Int, val width: Int, val height: Int, val templateId: String)

/**
 * The open notebook: owns its [SoilDatabase], the page list, the current page and the decoded
 * template bitmap. Every function is `suspend` and does its work on IO. Created by
 * [NotebookActivity], one per screen; released via [seal].
 */
class NotebookSession(
    context: Context,
    val notebookId: String,
    private val repo: IndexRepository = IndexRepository(),
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
     * Open the file (raw-key path when cached; never creates), read the page list and the last-open
     * page, decode its template. A missing/empty file or a file with no pages is a [OpenResult.Failed]
     * — the caller toasts and finishes; nothing is ever fabricated here.
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
        pages = pageRows.map { it.toPageRef() }
        val lastOpen = root?.refId
        currentIndex = pages.indexOfFirst { it.id == lastOpen }.takeIf { it >= 0 } ?: 0
        loadTemplateFor(currentPage)
        Slog.d(TAG) { "opened $notebookId: ${pages.size} pages, at $currentIndex" }
        OpenResult.Ok
    }

    /** Point the notebook row at [pageId] as the last-open page (reopen restores it). */
    suspend fun saveLastOpened(pageId: String = currentPage.id) = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext
        db.dao().setRefId(notebookId, pageId, System.currentTimeMillis())
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

    private fun SoilObjectEntity.toPageRef() = PageRef(
        id = id, order = order,
        width = (width ?: 0f).toInt(), height = (height ?: 0f).toInt(),
        templateId = refId ?: "",
    )

    companion object {
        private const val TAG = "NotebookSession"
        const val MAX_TEMPLATE_EDGE = 4096
    }
}
