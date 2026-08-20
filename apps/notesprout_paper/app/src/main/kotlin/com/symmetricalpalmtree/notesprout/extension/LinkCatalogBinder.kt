package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import android.os.Binder
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.crypto.KeySession
import com.symmetricalpalmtree.notesprout.data.index.IndexRepository
import com.symmetricalpalmtree.notesprout.data.index.ObjectSummary
import com.symmetricalpalmtree.notesprout.data.index.ObjectType
import com.symmetricalpalmtree.notesprout.data.prefs.SortField
import com.symmetricalpalmtree.notesprout.data.prefs.SortOrder
import com.symmetricalpalmtree.notesprout.data.prefs.SortPrefs
import com.symmetricalpalmtree.notesprout.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema
import com.symmetricalpalmtree.notesprout.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The live-session lens `LinkCatalogBinder` reads the *current* notebook's pages through — the origin
 * notebook's `.soil` is open in the notebook session and is never touched from a second connection.
 * [currentPages] returns (page id, display label) in page order, or null while the session isn't
 * ready. The labels are composed host-side for the current notebook (L2 Q2 — "Page n", plus the
 * page's outline heading where the Contents has one) and the *current page is already excluded*
 * (L2 Q1 — a self-link is a no-op trap; excluding here keeps the "Page n" numbering true). Other
 * notebooks' pages leave with blank labels — the picker shows "Page n" from position.
 */
class LinkCatalogSource(
    val currentNotebookId: String,
    val currentPages: () -> List<Pair<String, String>>?,
)

/**
 * The host-implemented `ILinkCatalog` (arc 7 / L0) — the first host-implemented multi-method callback
 * binder: a per-showing, uid-gated lens over the library the picker browses through (rule 29). Minted
 * by `LinkClient.openPick` beside the store binder and **revoked in the same `finally` as the unbind**.
 *
 * Outward = names, ids and labels of alive rows only — never keys, paths, covers or blobs. The reads
 * run synchronously on the host's Binder thread (`runBlocking` on IO — the proxy precedent, never
 * Main): [listFolder] is the index listing in library order (folders first, then notebooks, under the
 * user's own sort); [listPages] answers the current notebook from the live session ([source]) and any
 * other notebook by a read-only `SoilDatabase.open` closed (sealed) in `finally` — one live *session*
 * at a time still holds, this is a bounded read of a closed file. The create half is L3
 * (`UnsupportedOperationException` until then). Failures leave only as the Binder-marshalable set —
 * an unexpected exception is rethrown as `IllegalStateException` (the arc-2 silent-failure lesson).
 */
class LinkCatalogBinder(
    context: Context,
    extUid: Int,
    private val source: LinkCatalogSource,
) : ILinkCatalog.Stub() {

    private val appContext = context.applicationContext
    private val gate = LinkCatalogGate(extUid, Binder::getCallingUid)

    /** After this every method throws `SecurityException`. The client's `finally`, beside the unbind. */
    fun revoke() = gate.revoke()

    override fun listFolder(folderId: String?): MutableList<CatalogEntry> {
        gate.check()
        val fid = folderId ?: ""
        return io {
            runBlocking(Dispatchers.IO) {
                val repo = IndexRepository()
                val parent: String? = if (fid.isEmpty()) null else fid
                if (parent != null) {
                    val row = repo.alive(parent)
                    require(row != null && row.type == ObjectType.FOLDER) { "unknown folder" }
                }
                val prefs = SortPrefs(appContext)
                val folders = sort(repo.folders(parent), prefs.field, prefs.order)
                    .map { gate.entry(it.id, ExtensionContract.CATALOG_FOLDER, it.name) }
                val notebooks = sort(repo.notebooks(parent), prefs.field, prefs.order)
                    .map { gate.entry(it.id, ExtensionContract.CATALOG_NOTEBOOK, it.name) }
                val entries = gate.cap(folders + notebooks)
                Slog.d(TAG) { "listFolder: ${entries.size} entries (root=${parent == null})" }
                entries.toMutableList()
            }
        }
    }

    override fun listPages(notebookId: String?): MutableList<CatalogEntry> {
        gate.check()
        require(!notebookId.isNullOrBlank()) { "notebookId is blank" }
        return io {
            runBlocking(Dispatchers.IO) {
                val pages: List<Pair<String, String>> = if (notebookId == source.currentNotebookId) {
                    source.currentPages() ?: throw IllegalStateException("notebook not ready")
                } else {
                    // Blank labels: Paper pages carry no names — the picker shows "Page n" from position.
                    foreignPageIds(notebookId).map { it to "" }
                }
                val entries = gate.cap(pages.map { (id, label) -> gate.entry(id, ExtensionContract.CATALOG_PAGE, label) })
                Slog.d(TAG) { "listPages: ${entries.size} pages (live=${notebookId == source.currentNotebookId})" }
                entries.toMutableList()
            }
        }
    }

    /** Page ids of a notebook that is NOT the open session's — a read-only open, sealed in `finally`. */
    private suspend fun foreignPageIds(notebookId: String): List<String> {
        val repo = IndexRepository()
        val row = repo.alive(notebookId)
        require(row != null && row.type == ObjectType.NOTEBOOK) { "unknown notebook" }
        val pass = KeySession.get() ?: throw IllegalStateException("no key in session")
        val file = soilFile(appContext, notebookId)
        val db = SoilDatabase.open(appContext, notebookId, file, pass)
        try {
            val nbRow = db.dao().notebookRow() ?: throw IllegalStateException("notebook row missing")
            return db.dao().childrenOfType(nbRow.id, SoilSchema.TYPE_PAGE).map { it.id }
        } finally {
            db.seal(file)
        }
    }

    /** The alive folder chain to a notebook, root-first, ending with the notebook itself (label =
     *  its name) — the Edit prefill's way of opening the browse where the target actually lives
     *  (L2 fix: a `DEST_NOTEBOOK` target inside a folder was invisible at the root). Empty for an
     *  unknown / dead / non-notebook id — prefill is best-effort, never an error. */
    override fun pathTo(notebookId: String?): MutableList<CatalogEntry> {
        gate.check()
        require(!notebookId.isNullOrBlank()) { "notebookId is blank" }
        return io {
            runBlocking(Dispatchers.IO) {
                val repo = IndexRepository()
                val row = repo.alive(notebookId)
                if (row == null || row.type != ObjectType.NOTEBOOK) {
                    Slog.d(TAG) { "pathTo: no alive notebook" }
                    return@runBlocking mutableListOf()
                }
                val folders = repo.ancestry(row.parentId)
                    .map { gate.entry(it.id, ExtensionContract.CATALOG_FOLDER, it.name) }
                val entries = gate.cap(folders + gate.entry(row.id, ExtensionContract.CATALOG_NOTEBOOK, row.name))
                Slog.d(TAG) { "pathTo: ${entries.size} entries" }
                entries.toMutableList()
            }
        }
    }

    // ── The create half (L3) ─────────────────────────────────────────────────

    override fun createPage(notebookId: String?, anchorPageId: String?, before: Boolean): String {
        gate.check()
        throw UnsupportedOperationException("createPage is not available yet")
    }

    override fun createFolder(parentFolderId: String?, name: String?): String {
        gate.check()
        throw UnsupportedOperationException("createFolder is not available yet")
    }

    override fun createNotebook(parentFolderId: String?, name: String?): String {
        gate.check()
        throw UnsupportedOperationException("createNotebook is not available yet")
    }

    /** The library's own ordering (`LibraryActivity.sortItems`) — one sort model everywhere. */
    private fun sort(list: List<ObjectSummary>, field: SortField, order: SortOrder): List<ObjectSummary> {
        val comparator: Comparator<ObjectSummary> = when (field) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.MODIFIED -> compareBy { it.updatedAt }
        }
        return if (order == SortOrder.DESC) list.sortedWith(comparator.reversed()) else list.sortedWith(comparator)
    }

    /** Only the Binder-marshalable set leaves the stub; anything else would fail the transaction silently. */
    private inline fun <T> io(block: () -> T): T =
        try {
            block()
        } catch (e: SecurityException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("catalog: ${e.javaClass.simpleName}: ${e.message}")
        }

    private companion object {
        const val TAG = "LinkCatalogBinder"
    }
}
