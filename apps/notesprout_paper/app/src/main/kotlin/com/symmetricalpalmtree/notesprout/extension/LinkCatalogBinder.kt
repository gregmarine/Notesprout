package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import android.os.Binder
import androidx.room.withTransaction
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesprout.library.NewNotebookActivity
import java.util.UUID
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
    /** Create a page in the CURRENT notebook (arc 7 / L3): (anchor page id or null = append,
     *  insert-before) → the new page id, run under the host's page-op lock — the live session is
     *  never touched from a second connection, so its inserts go through the screen. Null = this
     *  source offers no create (the debug probe's). Blocking — called on the binder thread. */
    val createPage: ((String?, Boolean) -> String)? = null,
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

    /** After this every method throws `SecurityException`. The client's `finally`, beside the
     *  unbind. Also drops the New-notebook relay — nothing armed or created survives the showing. */
    fun revoke() {
        gate.revoke()
        LinkCreateRelay.clear()
    }

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

    // ── The create half (L3) — exactly the validation the library's own UI enforces; every
    // refusal is a typed IllegalArgumentException whose message the picker shows verbatim. ──

    override fun createPage(notebookId: String?, anchorPageId: String?, before: Boolean): String {
        gate.check()
        require(!notebookId.isNullOrBlank()) { "notebookId is blank" }
        val anchor = anchorPageId?.takeIf { it.isNotBlank() }
        return io {
            val id = if (notebookId == source.currentNotebookId) {
                // The live session is never touched from a second connection — the host screen
                // inserts under its own page-op lock (blocking bridge; L3 wizard Q4 note).
                val create = source.createPage ?: throw IllegalStateException("notebook not ready")
                create(anchor, before)
            } else {
                runBlocking(Dispatchers.IO) { foreignCreatePage(notebookId, anchor, before) }
            }
            Slog.d(TAG) { "createPage: ok (live=${notebookId == source.currentNotebookId}, anchored=${anchor != null}, before=$before)" }
            id
        }
    }

    override fun createFolder(parentFolderId: String?, name: String?): String {
        gate.check()
        val trimmed = name?.trim().orEmpty()
        val parent: String? = parentFolderId?.takeIf { it.isNotBlank() }
        return io {
            // The library's exact rules (L3 wizard Q3): charset / reserved names via the shared
            // validator, the contract's length cap, then the duplicate-sibling check.
            NewNotebookActivity.validateName(trimmed)?.let { throw IllegalArgumentException(it) }
            if (trimmed.length > ExtensionContract.MAX_NAME_CHARS) throw IllegalArgumentException("Name is too long")
            runBlocking(Dispatchers.IO) {
                val repo = IndexRepository()
                if (parent != null) {
                    val row = repo.alive(parent)
                    require(row != null && row.type == ObjectType.FOLDER) { "unknown folder" }
                }
                if (repo.nameTaken(parent, ObjectType.FOLDER, trimmed)) {
                    throw IllegalArgumentException(appContext.getString(R.string.new_folder_duplicate))
                }
                val folder = repo.createFolder(trimmed, parent)
                Slog.d(TAG) { "createFolder: ok (root=${parent == null})" }
                folder.id
            }
        }
    }

    /** Superseded by the L3 wizard Q2 answer (`prepareNewNotebook` + the host's own screen +
     *  `takeCreatedNotebook`); the slot stays forever — AIDL transaction order is fixed. */
    override fun createNotebook(parentFolderId: String?, name: String?): String {
        gate.check()
        throw UnsupportedOperationException("createNotebook is superseded — use prepareNewNotebook")
    }

    /** Arm the host's New-notebook screen (L3 wizard Q2): validate the folder, resolve the
     *  naming-scheme default for it exactly like the library (best-effort — null on any failure or
     *  at the root, where the library skips the namer too), park both in [LinkCreateRelay]. */
    override fun prepareNewNotebook(parentFolderId: String?): Unit {
        gate.check()
        val parent: String? = parentFolderId?.takeIf { it.isNotBlank() }
        io {
            runBlocking(Dispatchers.IO) {
                val repo = IndexRepository()
                if (parent != null) {
                    val row = repo.alive(parent)
                    require(row != null && row.type == ObjectType.FOLDER) { "unknown folder" }
                }
                val defaultName: String? = if (parent == null) null else try {
                    ExtensionRegistry.notebookNamer(appContext)?.let { ref ->
                        val siblings = repo.notebooks(parent).map { it.name }
                        NamerClient(appContext, ref).defaultName(parent, siblings)
                    }
                } catch (e: Exception) {
                    Slog.d(TAG) { "prepareNewNotebook: defaultName failed ${e.javaClass.simpleName}" }
                    null
                }
                LinkCreateRelay.prepare(parent, defaultName)
                Slog.d(TAG) { "prepareNewNotebook: armed (root=${parent == null}, named=${defaultName != null})" }
            }
        }
    }

    /** Drain what the armed screen created — read-and-clear; null = cancelled or nothing armed. */
    override fun takeCreatedNotebook(): CatalogEntry? {
        gate.check()
        val created = LinkCreateRelay.takeCreated()
        Slog.d(TAG) { "takeCreatedNotebook: ${if (created != null) "created" else "null"}" }
        return created?.let { gate.entry(it.id, ExtensionContract.CATALOG_NOTEBOOK, it.name) }
    }

    /** Insert a blank page into a notebook that is NOT the open session's — open, insert + renumber
     *  in one transaction, mirror the index pageCount, seal in `finally`. Template + geometry are
     *  inherited from the anchor (or the last page when appending) — the L3 wizard Q1 rule. */
    private suspend fun foreignCreatePage(notebookId: String, anchorPageId: String?, before: Boolean): String {
        val repo = IndexRepository()
        val row = repo.alive(notebookId)
        require(row != null && row.type == ObjectType.NOTEBOOK) { "unknown notebook" }
        val pass = KeySession.get() ?: throw IllegalStateException("no key in session")
        val file = soilFile(appContext, notebookId)
        val db = SoilDatabase.open(appContext, notebookId, file, pass)
        try {
            val nbRow = db.dao().notebookRow() ?: throw IllegalStateException("notebook row missing")
            val pageRows = db.dao().childrenOfType(nbRow.id, SoilSchema.TYPE_PAGE)
            if (pageRows.isEmpty()) throw IllegalStateException("notebook has no pages")
            val anchorIndex: Int
            if (anchorPageId == null) {
                anchorIndex = pageRows.lastIndex
            } else {
                anchorIndex = pageRows.indexOfFirst { it.id == anchorPageId }
                require(anchorIndex >= 0) { "unknown page" }
            }
            val anchor = pageRows[anchorIndex]
            val pos = when {
                anchorPageId == null -> pageRows.size
                before -> anchorIndex
                else -> anchorIndex + 1
            }
            val now = System.currentTimeMillis()
            val newId = UUID.randomUUID().toString()
            db.withTransaction {
                db.dao().upsert(SoilObjectEntity(
                    id = newId, parentId = nbRow.id, type = SoilSchema.TYPE_PAGE, order = pos,
                    createdAt = now, updatedAt = now,
                    refId = anchor.refId ?: "", width = anchor.width, height = anchor.height,
                ))
                val ordered = pageRows.mapTo(ArrayList(pageRows.size + 1)) { it.id }.apply { add(pos, newId) }
                ordered.forEachIndexed { i, id -> db.dao().setOrder(id, i, now) }
            }
            repo.setPageCount(notebookId, pageRows.size + 1)
            repo.touch(notebookId, now)
            return newId
        } finally {
            db.seal(file)
        }
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
