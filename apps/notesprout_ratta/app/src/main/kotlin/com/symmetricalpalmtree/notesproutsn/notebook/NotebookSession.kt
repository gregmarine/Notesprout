package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.room.withTransaction
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Bitmaps
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags
import com.symmetricalpalmtree.notesproutsn.data.soil.DocumentRepository
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMetaStore
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilCompactor
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.data.template.PagePaper
import com.symmetricalpalmtree.notesproutsn.data.template.PaperSource
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    /** The single serial write queue both stores share — see [SoilWriter]. */
    lateinit var writer: SoilWriter
        private set
    lateinit var store: StrokeStore
        private set
    lateinit var headings: HeadingStore
        private set
    lateinit var links: LinkStore
        private set

    /**
     * The `document` rows' reader and writer (arc 19 / M3 — M2 built it). Not a `*Store` like its
     * three neighbours because a document is not page content and has no in-memory working copy on
     * this screen: the extension's editor holds the live text and the host writes what it pushes
     * back. Reads go straight through it; **writes go through [writeDocument]**, which is what puts
     * them on the session's one serial queue with everything else.
     */
    lateinit var documents: DocumentRepository
        private set

    // @Volatile: the Contents gather reads this on an IO thread outside the page-op mutex — the
    // list itself is immutable and swapped whole, but without the fence its publication to that
    // reader is a JMM data race (unsafe publication, not just staleness).
    @Volatile
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

    /**
     * Whether this notebook is a **text document** (arc 19 / M8) — the index bit, read once at
     * [open] and never again: the flag is set when the notebook is created or imported and nothing
     * flips it afterwards, while the screen asks about it on every route it takes (the open's
     * routing, the seal's cover, the editor's rename and close hooks). False before [open], which
     * is the honest answer for a session that has not read the index yet.
     */
    var isTextDocument: Boolean = false
        private set

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
        writer = SoilWriter { repo.touch(notebookId) }
        store = StrokeStore(db.dao(), writer)
        headings = HeadingStore(db.dao(), writer)
        links = LinkStore(db.dao(), writer) { block -> db.withTransaction { block() } }
        documents = DocumentRepository(db.documentDao(), db.dao())
        try {
            // The index bit, once (M8): blob-free, and before anything can ask — the screen's very
            // first decision after this call is which route the open takes.
            isTextDocument = textDocumentBit(repo.summary(notebookId)?.flags)
            val dao = db.dao()
            val root = dao.notebookRow()
            val pageRows = dao.childrenOfType(notebookId, SoilSchema.TYPE_PAGE)
            if (pageRows.isEmpty()) {
                runCatching { withContext(NonCancellable) { seal() } }
                return@withContext OpenResult.Failed("Notebook has no pages")
            }
            pages = pageRows.mapIndexed { i, row -> row.toPageRef(i) }
            val lastOpen = root?.refId
            currentIndex = pages.indexOfFirst { it.id == lastOpen }.takeIf { it >= 0 } ?: 0
            loadTemplateFor(currentPage)
            Slog.d(TAG) { "opened $notebookId: ${pages.size} pages, at $currentIndex" }
            OpenResult.Ok
        } catch (t: Throwable) {
            // The handle is open but the caller will never see this session — most commonly a back
            // press during the KDF window cancelling the scope, so the very next suspension throws
            // CancellationException. Seal here (NonCancellable — we ARE being cancelled) or the
            // connection + un-checkpointed WAL sidecar outlive the screen for the process lifetime.
            runCatching { withContext(NonCancellable) { seal() } }
            throw t
        }
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
     * either side, and the content ids — strokes, headings (N2), links and the links' wrapped
     * children (K1) — the operation soft-deleted (empty for an insert). Restore/delete by id is
     * type-agnostic, so one list.
     */
    data class Structural(
        val before: List<String>,
        val after: List<String>,
        val objectIds: List<String>,
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
     * Insert a blank page **without moving the notebook off the page on screen** (arc 6 / K3) — the
     * link picker creating the page a link will point at, while the user is still looking at the
     * page they are writing on.
     *
     * [anchorPageId] is the page card the picker had selected: the new page goes before or after it
     * ([LinkPickerModel.insertIndexFor]), and inherits its template and authored size
     * ([LinkPickerModel.inheritIndexFor]) so the notebook stays one consistent paper. A null or
     * vanished anchor appends and inherits from the last page.
     *
     * Everything else is [insertBlank]'s shape — one transaction (upsert + renumber), the page list
     * swapped whole, the index mirrored — with two deliberate differences: [currentIndex] is
     * re-anchored **by id** (the page on the paper does not change, but its index may have shifted
     * under it), and neither the loaded template nor the paper is touched. Records no undo entry:
     * picker creations are not undoable (the og rule), and the host clears its stack on return.
     */
    suspend fun insertAt(anchorPageId: String?, before: Boolean): PageRef = withContext(Dispatchers.IO) {
        val ids = pages.map { it.id }
        val pos = LinkPickerModel.insertIndexFor(ids, anchorPageId, before)
        // -1 is the empty-notebook case, which open() already refuses — never fabricate a size.
        val inherit = pages.getOrNull(LinkPickerModel.inheritIndexFor(ids, anchorPageId))
            ?: error("insertAt on a notebook with no pages")
        val keptId = currentPage.id
        val now = System.currentTimeMillis()
        val newId = java.util.UUID.randomUUID().toString()
        val newRow = SoilObjectEntity(
            id = newId, parentId = notebookId, type = SoilSchema.TYPE_PAGE, order = pos,
            createdAt = now, updatedAt = now,
            refId = inherit.templateId, width = inherit.width.toFloat(), height = inherit.height.toFloat(),
        )
        val newPages = pages.toMutableList().apply { add(pos, newRow.toPageRef(pos)) }
        db.withTransaction {
            db.dao().upsert(newRow)
            renumber(newPages, now)
        }
        pages = newPages.reindexed()
        currentIndex = pages.indexOfFirst { it.id == keptId }.coerceAtLeast(0)
        mirror(now)
        Slog.d(TAG) { "picker inserted $newId at $pos (${pages.size} pages, still on $keptId)" }
        pages[pos]
    }

    // ── Clipboard (arc 7) ────────────────────────────────────────────────────

    /**
     * Snapshot the current page and everything on it into a clipboard payload (arc 7). Reads the
     * page's own row, its template row, and its live descendants — two levels deep since arc 6, so
     * a link's wrapped children ride along.
     *
     * **The caller must drain the writer first** (`store.drain()`): a stroke commit still queued
     * would land after this read and be silently missing from the copy. Null only when the page row
     * has vanished under us.
     */
    suspend fun capturePage(): ClipEnvelope? = withContext(Dispatchers.IO) {
        val page = currentPage
        val pageRow = db.dao().byId(page.id) ?: return@withContext null
        val templateRow = page.templateId.takeIf { it.isNotEmpty() }?.let { db.dao().byId(it) }
        val content = db.dao().liveDescendantIds(page.id)
            .chunked(ID_CHUNK)
            .flatMap { db.dao().byIds(it) }
        PageClip.capture(pageRow, templateRow, content, notebookId, System.currentTimeMillis())
    }

    /**
     * Paste [env]'s page next to the current one and land on it — [insertBlank]'s shape (one
     * transaction, dense renumber, index mirror) with the payload's rows in place of a single blank
     * row. Throws when the payload holds no page row; the caller checks the clipboard first.
     *
     * The page's `width`/`height` come across **verbatim** — ink is never resampled, so a
     * Manta-authored page stays its own size inside a Nomad notebook (og's rule).
     *
     * The returned [Structural] is the paste's undo record. Its `objectIds` are the rows the paste
     * *created*, which is the opposite direction from a delete's — see `Action.PagePasted`.
     */
    suspend fun pasteAt(env: ClipEnvelope, before: Boolean): Structural = withContext(Dispatchers.IO) {
        val cur = currentPage
        val beforeIds = pages.map { it.id }
        val now = System.currentTimeMillis()
        val pos = PageMath.insertPosition(currentIndex, after = !before)
        val plan = PageClip.plan(env, notebookId, pos, resolveTemplate(env), now) {
            java.util.UUID.randomUUID().toString()
        } ?: error("clipboard payload has no page row")
        val pageRow = plan.rows.first { it.type == SoilSchema.TYPE_PAGE }
        val newPages = pages.toMutableList().apply { add(pos, pageRow.toPageRef(pos)) }
        db.withTransaction {
            plan.rows.chunked(ROW_CHUNK).forEach { db.dao().upsertAll(it) }
            renumber(newPages, now)
        }
        pages = newPages.reindexed()
        currentIndex = pos
        loadTemplateFor(currentPage)
        mirror(now)
        Slog.d(TAG) { "pasted ${plan.pageId} at $pos (${plan.contentIds.size} objects, ${pages.size} pages)" }
        Structural(beforeIds, pages.map { it.id }, plan.contentIds, cur.id, plan.pageId)
    }

    /**
     * Snapshot a lasso selection into a clipboard payload (arc 8). [topIds] are the selected rows —
     * strokes, headings and links — and every selected **link**'s live children ride along with it
     * (a link copies whole; nothing ever reaches inside one).
     *
     * Reads only: the rows are the truth, so a copy carries exactly what a reopen would show. **The
     * caller must drain the writer first** — the arc's standing trap. Null when nothing selected is
     * still on the page (the selection can go stale between the bar's tap and this read).
     */
    suspend fun captureObjects(topIds: List<String>): ClipEnvelope? = withContext(Dispatchers.IO) {
        if (topIds.isEmpty()) return@withContext null
        val top = topIds.chunked(ID_CHUNK)
            .flatMap { db.dao().byIds(it) }
            .filter { it.deletedAt == null }
        if (top.isEmpty()) return@withContext null
        val children = top.filter { it.type == SoilSchema.TYPE_LINK }.flatMap { link ->
            db.dao().childrenOfType(link.id, SoilSchema.TYPE_STROKE) +
                db.dao().childrenOfType(link.id, SoilSchema.TYPE_HEADING)
        }
        ObjectClip.capture(top, children, notebookId, System.currentTimeMillis())
    }

    /**
     * Write [env]'s objects onto [pageId] — one transaction, the rows through the same
     * [SoilObjectEntity] door every other write uses, then the index clock mirrored.
     *
     * The `"order"` bases are read **inside** the same transaction as the writes: two pastes racing
     * would otherwise both read the same max and stack their rows on identical order numbers.
     * [place] decides where the payload lands from its own bounding box ([ObjectPlacement]). A
     * payload from **another** notebook has its links re-pointed on the way in (O2 —
     * `ObjectClip.rewriteLink`); the source `.soil` is never opened, here or anywhere.
     *
     * Null when the payload holds nothing this build can place — the caller explains and writes
     * nothing. `pageCount` is untouched: a paste of objects adds no page.
     */
    suspend fun pasteObjects(
        env: ClipEnvelope,
        pageId: String,
        place: (Bounds) -> ObjectPlacement.Offset,
    ): ObjectClip.Plan? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var plan: ObjectClip.Plan? = null
        db.withTransaction {
            // [ObjectClip.plan] is pure and synchronous, so its `baseOrder` lambda cannot suspend:
            // the three bases a selection can need are read here, still inside the transaction.
            val bases = ORDERED_TYPES.associateWith { db.dao().maxOrder(pageId, it) }
            val built = ObjectClip.plan(
                env = env,
                notebookId = notebookId,
                pageId = pageId,
                baseOrder = { type -> bases[type] ?: -1 },
                now = now,
                newId = { java.util.UUID.randomUUID().toString() },
                place = place,
            ) ?: return@withTransaction
            built.rows.chunked(ROW_CHUNK).forEach { db.dao().upsertAll(it) }
            plan = built
        }
        val built = plan ?: return@withContext null
        repo.touch(notebookId, now)
        Slog.d(TAG) {
            "pasted ${built.contentIds.size} rows onto $pageId " +
                "(${built.strokes.size} strokes, ${built.headings.size} headings, ${built.links.size} links)"
        }
        built
    }

    /**
     * Write [strokes] onto [pageId] — the scratch pad's ink coming home (arc 11 / J5). One
     * transaction, the same [StrokeRows] door every other stroke write uses, `"order"` rebased after
     * the page's current max **inside** that transaction (two pastes racing would otherwise both
     * read the same max and stack their rows on identical numbers — `pasteObjects`' rule).
     *
     * The ids are the caller's: [com.symmetricalpalmtree.notesproutsn.extension.TransferCaps.toStrokes]
     * minted them on this side, and no id ever crosses the wire. Coordinates are kept **1:1** — the
     * pad page and the notebook page are both this device's screen, and a cross-size page clips the
     * ink like any other. Relative order is preserved: writing order is load-bearing (a later
     * lasso-convert reads the strokes as a sequence — the arc-3 / N3 lesson).
     *
     * Not routed through the session's serial [SoilWriter]: the caller has drained it and holds the
     * page-op lock, and one transaction is the atomicity a paste needs.
     */
    suspend fun pasteStrokes(pageId: String, strokes: List<Stroke>) = withContext(Dispatchers.IO) {
        if (strokes.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        db.withTransaction {
            var order = db.dao().maxOrder(pageId, SoilSchema.TYPE_STROKE)
            val rows = strokes.map { StrokeRows.toRow(it, pageId, ++order, now) }
            rows.chunked(ROW_CHUNK).forEach { db.dao().upsertAll(it) }
        }
        repo.touch(notebookId, now)
        Slog.d(TAG) { "pasted ${strokes.size} strokes from the scratch pad onto $pageId" }
    }

    /**
     * How this file should reach the payload's template, in three tries:
     *
     *  1. **The row is already here under that id** — always so for a same-notebook paste, and for
     *     a repeat paste of the same source page (an [PageClip.Template.Insert] brings the row in
     *     under its *source* id, which is free here, so the next one finds it).
     *  2. **The same paper under a different id** — the destination was created with the same
     *     built-in template, so its WEBP is byte-identical under its own UUID
     *     ([PageClip.matchTemplate]). Without this every notebook pair would stack its own copy of
     *     the same pixels.
     *  3. Otherwise bring the carried row in; and if the payload names a template it does not
     *     carry, the page pastes blank rather than pointing at nothing.
     */
    private suspend fun resolveTemplate(env: ClipEnvelope): PageClip.Template {
        val pageRow = env.rows.firstOrNull { it.type == SoilSchema.TYPE_PAGE }
        val wanted = pageRow?.refId?.takeIf { it.isNotEmpty() } ?: return PageClip.Template.None
        if (db.dao().byId(wanted) != null) return PageClip.Template.Reuse(wanted)
        val carried = env.rows.firstOrNull { it.type == SoilSchema.TYPE_TEMPLATE && it.id == wanted }
            ?: return PageClip.Template.None
        // Shortlist blob-free, then load only the rows whose pixels could match at all.
        val size = carried.blobBytes()?.size
        val shortlist = db.dao().templateDigests(notebookId)
            .filter { it.text == carried.text && it.width == carried.width && it.height == carried.height && it.blobLength == size }
            .map { it.id }
        for (chunk in shortlist.chunked(ID_CHUNK)) {
            val hit = PageClip.matchTemplate(carried, db.dao().byIds(chunk))
            if (hit != null) {
                Slog.d(TAG) { "paste reuses matching template $hit" }
                return PageClip.Template.Reuse(hit)
            }
        }
        return PageClip.Template.Insert(wanted)
    }

    /**
     * Soft-delete the current page and its live content (strokes + headings), then land on the
     * previous page. Deleting the **only** page puts a fresh blank in its place instead — a
     * notebook always has ≥ 1 page, and an empty one would have nothing to draw on and nothing to
     * open next time.
     */
    suspend fun deleteCurrent(): Structural = withContext(Dispatchers.IO) {
        val victim = currentPage
        val before = pages.map { it.id }
        val now = System.currentTimeMillis()
        // Deep since K1: the page's strokes, headings AND links, plus the links' wrapped children
        // (grandchildren) — a wrapped selection rides its page through delete and undo.
        val contentIds = db.dao().liveDescendantIds(victim.id)
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
                if (contentIds.isNotEmpty()) db.dao().softDelete(contentIds, now)
            }
            pages = listOf(replacement.toPageRef(0))
            currentIndex = 0
            loadTemplateFor(currentPage)
            mirror(now)
            Slog.d(TAG) { "deleted the only page ${victim.id}, replaced with $newId" }
            return@withContext Structural(before, listOf(newId), contentIds, victim.id, newId)
        }
        val remaining = pages.filter { it.id != victim.id }
        db.withTransaction {
            db.dao().softDelete(listOf(victim.id), now)
            if (contentIds.isNotEmpty()) db.dao().softDelete(contentIds, now)
            renumber(remaining, now)
        }
        pages = remaining.reindexed()
        currentIndex = PageMath.indexAfterDelete(before.indexOf(victim.id), before.size)
        loadTemplateFor(currentPage)
        mirror(now)
        Slog.d(TAG) { "deleted ${victim.id} + ${contentIds.size} objects (${pages.size} pages)" }
        Structural(before, pages.map { it.id }, contentIds, victim.id, currentPage.id)
    }

    /**
     * Make the live page set exactly [targetAlive], in that order, restoring and soft-deleting the
     * given strokes with it, and land on [currentId]. This is the undo/redo primitive behind
     * [insertBlank] and [deleteCurrent] — both directions are the same call with the snapshot's two
     * sides swapped.
     */
    suspend fun reconcile(
        targetAlive: List<String>,
        restoreObjectIds: List<String>,
        deleteObjectIds: List<String>,
        currentId: String,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val alive = db.dao().childrenOfType(notebookId, SoilSchema.TYPE_PAGE).map { it.id }.toSet()
        val restorePages = PageMath.toRestore(alive, targetAlive)
        val deletePages = PageMath.toDelete(alive, targetAlive)
        db.withTransaction {
            if (restorePages.isNotEmpty()) db.dao().restore(restorePages, now)
            if (deletePages.isNotEmpty()) db.dao().softDelete(deletePages, now)
            if (restoreObjectIds.isNotEmpty()) db.dao().restore(restoreObjectIds, now)
            if (deleteObjectIds.isNotEmpty()) db.dao().softDelete(deleteObjectIds, now)
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

    /** Refresh `notebook_meta` from the index (name, folder path) — the file stays self-describing.
     *
     *  `textDocument` is read from the **index bit** and never from [existing] (arc 19 / M2): the
     *  index is the authority and the meta field only mirrors it, so a refresh that carried the
     *  previous meta forward would wipe the flag the first time the file was written by anything
     *  that had not seen it (og's meta-refresh-wipe trap). Every meta writer in this app sources it
     *  the same way. */
    suspend fun refreshMeta(appVersionCode: Int) = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext
        val row = repo.get(notebookId) ?: return@withContext
        val existing = NotebookMetaStore.read(db.raw())
        NotebookMetaStore.write(db.raw(), NotebookMeta(
            notebookId = notebookId, name = row.name,
            createdAt = existing?.createdAt ?: row.createdAt, updatedAt = row.updatedAt,
            folderPath = repo.ancestry(row.parentId), appVersionCode = appVersionCode,
            textDocument = textDocumentBit(row.flags),
        ))
    }

    /** The one reading of [NotebookFlags.TEXT_DOCUMENT] (M8) — [open]'s and [refreshMeta]'s, so the
     *  session's own answer and the one written into the file can never drift apart. */
    private fun textDocumentBit(flags: Int?): Boolean =
        ((flags ?: 0) and NotebookFlags.TEXT_DOCUMENT) != 0

    /**
     * Persist a document (arc 19 / M3) — the editor's save, arriving from the extension over the
     * host callback binder. [parentId] is a page id (a page document) or the notebook root row's id
     * (the notebook document, M7); blank [text] deletes the row, which is [DocumentRepository]'s
     * blank-means-absent rule and not a special case here.
     *
     * **Through the writer, then drained.** The enqueue is what orders this write against the
     * strokes and headings the same page may still be committing — one serial queue, the whole
     * session's rule. The [SoilWriter.drain] after it is the seam's half: the extension's
     * `saveChunk` is a blocking Binder call and its return is the editor's only "it landed", so a
     * fire-and-forget enqueue would let the editor finish (and the host seal) over a write still
     * sitting in the queue. Drain costs one round trip through a queue this screen is not otherwise
     * hammering, and buys the flush-before-seal invariant across a process boundary.
     *
     * [draftWatermark] non-null routes to [DocumentRepository.saveDrafted] — the one write that
     * moves the source watermark (a seed or a "bring in" refresh, M6/M7). An ordinary edit passes
     * null and the watermark stays exactly where it was.
     */
    suspend fun writeDocument(parentId: String, text: String, draftWatermark: Long?) {
        check(isOpen) { "notebook closed" }
        writer.enqueue {
            if (draftWatermark == null) documents.save(parentId, text)
            else documents.saveDrafted(parentId, text, draftWatermark)
        }
        writer.drain()
    }

    /** Wait for queued writes (both stores), then purge + checkpoint + close. Idempotent; never throws. */
    suspend fun seal() = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext
        try { writer.flushTouch() } catch (e: Exception) { Log.w(TAG, "flushTouch failed", e) }
        try { writer.drain() } catch (e: Exception) { Log.w(TAG, "drain failed", e) }
        writer.close()
        // The arc-17 purge, exactly here: after the writer is closed (no queued write can race the
        // deletes), before db.seal (the checkpoint absorbs the VACUUM and the connection is still
        // ours). Undo dies with this session, so its soft-deleted rows are unreachable from now on.
        // compact() never throws, and it never touches `updatedAt` — this close must not re-flag
        // the notebook for backup.
        SoilCompactor.compact(db.raw())
        db.seal(file)
        // Reference drop only — the paper view can outlive the seal by a frame (recycle() here
        // would race a final repaint; see loadTemplateFor).
        template = null
        Slog.d(TAG) { "sealed $notebookId" }
    }

    // ── Template ─────────────────────────────────────────────────────────────

    /**
     * What one re-papering did, in the two ids it moved between — enough to replay in either
     * direction ([applyTemplate]). Both are template-row ids, `""` for blank paper.
     */
    data class TemplateChange(val pageId: String, val from: String, val to: String)

    /**
     * Re-paper the **current** page with a built-in [kind] (arc 12): find or mint the template row,
     * then point the page's `refId` at it. Null when the page already has that paper — nothing is
     * written and the caller records no undo step.
     *
     * Only this page moves. A notebook created as one paper stays one paper until someone says
     * otherwise, and the pages either side of this one are not touched — the same rule the rest of
     * the page sheet follows (copy, cut and delete are all the page you long-pressed).
     *
     * The template is rendered at the **page's own** width/height, never the screen's: a page
     * pasted in from a larger device keeps its authored size (og's ink-is-never-resampled rule), and
     * ruling it to the screen would print a template that stops short of its own edge. [dpi] is
     * this panel's, which is the only density available — see [PageTemplate] for why that is safe.
     *
     * The old row is left exactly where it is. Nothing else may still point at it, but a template
     * is cheap, deleting one is not undoable, and leaving it is what makes the change back free.
     *
     * Throws [PaperRenderFailed] when the paper will not draw — a stored picture whose bytes will
     * not decode, or an allocation the device refused. **Nothing is written on that path.** Arc 12
     * had no live case for it (the only null was a page with no size), so a failed render fell
     * through to `""` and blanked the page; arc 13's imported pixels made it reachable, and wiping
     * the paper a user can see because we could not redraw it is the one outcome the whole
     * vanished-template rule exists to prevent.
     */
    suspend fun changeTemplate(paper: PaperSource, dpi: Float): TemplateChange? = withContext(Dispatchers.IO) {
        val page = currentPage
        val token = PagePaper.token(paper)
        val target = if (token.isEmpty()) "" else mintOrReuse(paper, token, page, dpi)
        if (target == page.templateId) return@withContext null
        val change = TemplateChange(page.id, page.templateId, target)
        applyTemplate(page.id, target)
        Slog.d(TAG) { "page ${page.id} re-papered ${token.ifEmpty { "blank" }} (${change.from.ifEmpty { "blank" }} → ${change.to.ifEmpty { "blank" }})" }
        change
    }

    /**
     * Point [pageId]'s row at [templateId] (`""` = blank) and mirror it into the page list. The
     * undo/redo primitive behind [changeTemplate] — both directions are this call with the change's
     * two ids swapped.
     *
     * The **decode is deliberately left to the caller's page refresh**: [loadTemplateFor] compares
     * against `templateIdLoaded`, so the id changing here is exactly what makes the following
     * `navigateTo` reload the bitmap — one decode, on the swap that paints it, in one EPD refresh.
     * Decoding here as well would cost a second read for a bitmap the page swap throws away.
     *
     * Safe on a page that has since been deleted: the row write lands on a soft-deleted row (which
     * a restore would then honour), the page list has no entry to update, and the caller's
     * `refreshToPage` finds no index and stays put.
     */
    suspend fun applyTemplate(pageId: String, templateId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.dao().setRefId(pageId, templateId, now)
        pages = pages.map { if (it.id == pageId) it.copy(templateId = templateId) else it }
        repo.touch(notebookId, now)
    }

    /** The current page's paper as its [TemplateToken], `""` for blank, or null when the row has
     *  vanished ([PageTemplate.tokenOf]). Reads digests, never pixels — this runs at a sheet's tap,
     *  and it is what the browser ticks the current card from. */
    suspend fun currentTemplateToken(): String? = withContext(Dispatchers.IO) {
        PageTemplate.tokenOf(db.dao().templateDigests(notebookId), currentPage.templateId)
    }

    /** Thrown when paper that is *not* blank will not draw at the page's size. See [changeTemplate]. */
    class PaperRenderFailed : IllegalStateException("template render failed")

    /** The id of a row already holding this paper at this page's size, or a freshly stored one.
     *  A render that comes back null throws rather than falling back to blank: this is only ever
     *  called with a non-empty token, so "nothing to draw" here means the paper is broken, not
     *  absent, and the page must keep what it has. */
    private suspend fun mintOrReuse(paper: PaperSource, token: String, page: PageRef, dpi: Float): String {
        PageTemplate.reusableId(db.dao().templateDigests(notebookId), token, page.width, page.height, prefer = page.templateId)
            ?.let { Slog.d(TAG) { "re-paper reuses template $it" }; return it }
        val bitmap = PagePaper.render(paper, page.width, page.height, dpi) ?: throw PaperRenderFailed()
        val blob = try { BuiltInTemplates.toWebp(bitmap) } finally { bitmap.recycle() }
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.dao().upsert(SoilObjectEntity(
            id = id, parentId = notebookId, type = SoilSchema.TYPE_TEMPLATE,
            createdAt = now, updatedAt = now, text = token,
            width = page.width.toFloat(), height = page.height.toFloat(), blob = blob,
        ))
        Slog.d(TAG) { "re-paper minted template $id ($token, ${blob.size} B)" }
        return id
    }

    private suspend fun loadTemplateFor(page: PageRef) {
        if (page.templateId == templateIdLoaded) return
        // Never recycle() the outgoing bitmap: this runs on IO mid-flip, while the engine keeps
        // painting the old template into every committed-layer repaint (a stroke commit, a scribble
        // erase, a cover capture) until the activity's `setTemplate` lands on Main — recycle() in
        // that window is a "trying to use a recycled bitmap" crash. minSdk 29: bitmaps live on the
        // Java heap, so dropping the reference IS the release.
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

        /** SQLite caps bound variables at 999 — id lists go in below that (`LinkStore.ID_CHUNK`). */
        private const val ID_CHUNK = 500

        /** Rows per `upsertAll`: Room binds ~18 columns each, so keep the statement well inside
         *  SQLite's variable limit. Chunking inside one transaction loses no atomicity. */
        private const val ROW_CHUNK = 50

        /** The row types a lasso selection can put on the clipboard — `"order"` is numbered per
         *  parent **and type** in the family, so a paste rebases each one against its own max. */
        private val ORDERED_TYPES = listOf(
            SoilSchema.TYPE_STROKE, SoilSchema.TYPE_HEADING, SoilSchema.TYPE_LINK,
        )
    }
}
