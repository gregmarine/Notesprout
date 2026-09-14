package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * `link` rows of the open notebook (arc 6 / K1) and the re-parenting that wraps a selection in
 * one. Every write goes through the session's single serial [SoilWriter] — shared with
 * [StrokeStore] / [HeadingStore], so a wrap can never interleave with a stroke commit — and the
 * multi-row ops additionally run inside one Room transaction ([transact], injected so the store
 * stays JVM-testable), so a link row and its children's `parentId` are never separately visible.
 *
 * A wrap keeps ids and page-absolute coordinates — only `parentId` flips page → link, and back on
 * an unlink. Soft deletes everywhere; restores are in place, like every store in the family.
 */
class LinkStore(
    private val dao: SoilDao,
    private val writer: SoilWriter,
    /** One Room transaction around [block] — `db.withTransaction` in production, direct call in tests. */
    private val transact: suspend (block: suspend () -> Unit) -> Unit,
) {

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Live links of [pageId] in z-order, each with its wrapped children (strokes in writing
     *  order, everything else in z-order). A malformed row is dropped; the page still renders.
     *
     *  A wrapped **sticky** arrives icon-only, exactly as [StickyStore.loadPage] reads a loose one:
     *  a note's content never draws on the page, so the page load never reads inside one (D2). The
     *  callers that do need it — a capture, a delete snapshot — go through
     *  [StickyStore.withContent]. */
    suspend fun loadPage(pageId: String): List<PageLink> =
        dao.linksOf(pageId).mapNotNull { row ->
            val strokes = dao.childrenOfType(row.id, SoilSchema.TYPE_STROKE).mapNotNull { StrokeRows.toStroke(it) }
            val headings = dao.childrenOfType(row.id, SoilSchema.TYPE_HEADING).mapNotNull { HeadingRows.toHeading(it) }
            val texts = dao.childrenOfType(row.id, SoilSchema.TYPE_TEXT).mapNotNull { TextRows.toText(it) }
            val shapes = dao.childrenOfType(row.id, SoilSchema.TYPE_SHAPE).mapNotNull { ShapeRows.toShape(it) }
            val stickies = dao.childrenOfType(row.id, SoilSchema.TYPE_STICKY).mapNotNull { StickyRows.toSticky(it) }
            LinkRows.toLink(row, strokes, headings, texts, shapes, stickies)
        }

    /** Live descendant ids of [pageId] — its own content **and** the links' children
     *  (page delete / undo carries wrapped selections with their page). */
    suspend fun deepChildIds(pageId: String): List<String> = dao.liveDescendantIds(pageId)

    // ── Writes (Main → serial IO) ────────────────────────────────────────────

    /** Wrap a selection: insert the link row at `MAX("order")+1` among the page's links and
     *  re-parent its children page → link, in one transaction. */
    fun create(pageId: String, link: PageLink) = writer.enqueue {
        val now = System.currentTimeMillis()
        transact {
            val order = dao.maxOrder(pageId, SoilSchema.TYPE_LINK) + 1
            dao.upsert(LinkRows.toRow(link.copy(order = order), pageId, now))
            link.childIds.chunked(ID_CHUNK).forEach { dao.reparent(it, link.id, now) }
        }
        Slog.d(TAG) { "create ${link.id} wrapping ${link.childIds.size}" }
    }

    /** Unwrap: the children become page children again and the link row is soft-deleted. */
    fun unlink(pageId: String, link: PageLink) = writer.enqueue {
        val now = System.currentTimeMillis()
        transact {
            link.childIds.chunked(ID_CHUNK).forEach { dao.reparent(it, pageId, now) }
            dao.softDelete(listOf(link.id), now)
        }
        Slog.d(TAG) { "unlink ${link.id} releasing ${link.childIds.size}" }
    }

    /** Redo of [create] / undo of [unlink] — revives the row at its stored geometry + z-order and
     *  re-parents the same children back under it. */
    fun relink(pageId: String, link: PageLink) = writer.enqueue {
        val now = System.currentTimeMillis()
        transact {
            reviveOrInsert(pageId, link, now)
            link.childIds.chunked(ID_CHUNK).forEach { dao.reparent(it, link.id, now) }
        }
        Slog.d(TAG) { "relink ${link.id} wrapping ${link.childIds.size}" }
    }

    /**
     * Soft-delete links **and everything they wrap** (a selection delete / an eraser hit) —
     * including, since arc 28, the **content strokes of every wrapped sticky**, which are the
     * link's grandchildren and are therefore not in [PageLink.childIds]. They are read here, inside
     * the transaction ([StickyStore.remove]'s rule), so a caller needs no snapshot to delete.
     *
     * It needs one to **undo**: see [restore].
     */
    fun remove(links: List<PageLink>) {
        if (links.isEmpty()) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            val ids = links.map { it.id } + links.flatMap { it.childIds }
            transact {
                val stickyContent = links.flatMap { it.stickies }.flatMap { sticky ->
                    dao.childrenOfType(sticky.id, SoilSchema.TYPE_STROKE).map { it.id }
                }
                (ids + stickyContent).chunked(ID_CHUNK).forEach { dao.softDelete(it, now) }
            }
            Slog.d(TAG) { "remove ${links.size} (${ids.size} rows)" }
        }
    }

    /**
     * [remove] that also hands back the **undo snapshot**: each link with every wrapped sticky's
     * content read in the same job, *ahead of* the soft-delete, in one transaction (arc 34 / M6 —
     * [StickyStore.removeWithContent]'s shape, for the same reason: the delete is queued on the
     * spot, in writer order, and the caller neither drains nor reads). A closed writer cancels the
     * deferred.
     */
    fun removeWithContent(links: List<PageLink>): Deferred<List<PageLink>> {
        val out = CompletableDeferred<List<PageLink>>()
        if (links.isEmpty()) { out.complete(emptyList()); return out }
        val accepted = writer.enqueue {
            try {
                val now = System.currentTimeMillis()
                val ids = links.map { it.id } + links.flatMap { it.childIds }
                val full = ArrayList<PageLink>(links.size)
                transact {
                    val stickyContent = ArrayList<String>()
                    for (l in links) {
                        if (l.stickies.isEmpty()) { full += l; continue }
                        full += l.copy(
                            stickies = l.stickies.map { sticky ->
                                val children = dao.childrenOfType(sticky.id, SoilSchema.TYPE_STROKE)
                                stickyContent += children.map { it.id }
                                sticky.copy(strokes = children.mapNotNull { StrokeRows.toStroke(it) })
                            },
                        )
                    }
                    (ids + stickyContent).chunked(ID_CHUNK).forEach { dao.softDelete(it, now) }
                }
                Slog.d(TAG) { "removeWithContent ${links.size} (${ids.size} rows)" }
                out.complete(full)
            } catch (e: Exception) {
                out.completeExceptionally(e)
                throw e
            }
        }
        if (!accepted) out.cancel()
        return out
    }

    /**
     * Undo of [remove]. The children rows still carry `parentId` = link id, so restoring by id is
     * enough; the link rows revive in place too ([reviveOrInsert]).
     *
     * A wrapped **sticky's content** rides the same way — but only from the snapshot: there is no
     * DAO that reads soft-deleted children, so the ids have to have been captured before the
     * delete. **A delete snapshot of a link holding stickies must therefore carry their content**
     * ([StickyStore.withContent] on every wrapped sticky before recording the undo action); a
     * snapshot taken icon-only revives the note with an empty page. What is restored here is
     * `link.stickies.flatMap { it.childIds }`, which is exactly what that snapshot holds.
     */
    fun restore(pageId: String, links: List<PageLink>) {
        if (links.isEmpty()) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            val stickyContent = links.flatMap { it.stickies }.flatMap { it.childIds }
            transact {
                for (l in links) reviveOrInsert(pageId, l, now)
                (links.flatMap { it.childIds } + stickyContent)
                    .chunked(ID_CHUNK).forEach { dao.restore(it, now) }
            }
            Slog.d(TAG) { "restore ${links.size} to $pageId (+${stickyContent.size} note rows)" }
        }
    }

    /**
     * Revive an existing row **in place** — un-delete by id, so the geometry and the
     * store-assigned z-order the row already carries survive untouched — and only upsert the
     * snapshot when no row exists at all. The host's snapshot holds `order = 0` (the store, not
     * the caller, assigns the real `MAX(order)+1` inside [create]'s transaction), so writing the
     * snapshot over a live row would silently sink the link below its overlap-mates and hand the
     * topmost-last follow tap to the wrong link (K5 review).
     */
    private suspend fun reviveOrInsert(pageId: String, link: PageLink, now: Long) {
        if (dao.byId(link.id) != null) dao.restore(listOf(link.id), now)
        else dao.upsert(LinkRows.toRow(link, pageId, now))
    }

    /**
     * Translate links by (dx, dy) — row and wrapped children alike, from ids only (an undo replay
     * has no [PageLink]). Heading, text, shape and sticky-icon children shift via their stored
     * columns ([SoilDao.moveBy] — a shape's `x`/`y` is its centre, which a delta moves the same
     * way); stroke geometry lives in the blob, so stroke children re-encode like [StrokeStore.move].
     *
     * A wrapped **sticky's content** is deliberately untouched: it is in the note's own local
     * space, so it does not move when the note does (D2).
     */
    fun move(linkIds: List<String>, dx: Float, dy: Float) {
        if (linkIds.isEmpty() || (dx == 0f && dy == 0f)) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            // One transaction like every other multi-row op: a row moved without its children —
            // a crash between the writes — renders sheared forever, with no undo left to repair it.
            transact {
                for (linkId in linkIds) {
                    dao.moveBy(listOf(linkId), dx, dy, now)
                    for (row in dao.childrenOfType(linkId, SoilSchema.TYPE_STROKE)) {
                        if (row.deletedAt != null) continue
                        val stroke = StrokeRows.toStroke(row) ?: continue
                        val moved = StrokeRows.toRow(stroke.translated(dx, dy), row.parentId, row.order, now)
                        dao.upsert(moved.copy(createdAt = row.createdAt))
                    }
                    val boxIds = BOX_CHILD_TYPES.flatMap { type ->
                        dao.childrenOfType(linkId, type).map { it.id }
                    }
                    boxIds.chunked(ID_CHUNK).forEach { dao.moveBy(it, dx, dy, now) }
                }
            }
            Slog.d(TAG) { "move ${linkIds.size} by ($dx,$dy)" }
        }
    }

    /** Rewrite a link's payload (the K2 Edit path — contract-only in K1). Bounds are unchanged —
     *  the wrapped ink is. */
    fun updatePayload(id: String, payload: String) = writer.enqueue {
        dao.setText(id, LinkRows.cap(payload), System.currentTimeMillis())
        Slog.d(TAG) { "updatePayload $id" }
    }

    /**
     * Rewrite a link's own box (arc 38 / R3) — the **only** mutation of a link's bounds that is not
     * a move, and it exists for exactly one caller: a Bible link wraps one text object, and editing
     * that reference re-measures the text, so the link's box (and with it the hit target and the
     * underline band) has to be re-derived from [PageLink.unionBounds] and written down.
     *
     * The wrapped children are **not** touched: the text keeps the top-left it was authored at, and
     * everything else about the link is where it was.
     */
    fun updateBounds(id: String, x: Float, y: Float, width: Float, height: Float) = writer.enqueue {
        dao.setBox(id, x, y, width, height, System.currentTimeMillis())
        Slog.d(TAG) { "updateBounds $id" }
    }

    companion object {
        private const val TAG = "LinkStore"

        /** SQLite caps bound variables at 999 — a big wrap's id list is chunked *inside* the
         *  transaction (chunking loses no atomicity). */
        const val ID_CHUNK = 500

        /**
         * **Every kind a link may wrap** (arc 28 / H1) — the one list, so a reader that walks a
         * link's children (the clipboard capture, this store's own load) can never know a shorter
         * one than the wrap does. A link is not here: no-nesting is the locked K1 rule.
         */
        val WRAPPED_TYPES = listOf(
            SoilSchema.TYPE_STROKE, SoilSchema.TYPE_HEADING, SoilSchema.TYPE_TEXT,
            SoilSchema.TYPE_SHAPE, SoilSchema.TYPE_STICKY,
        )

        /** The wrapped kinds whose geometry is in the row's own columns, so a link drag shifts
         *  them with one `moveBy`. Strokes are not here — their geometry is in the blob and goes
         *  through the codec. */
        private val BOX_CHILD_TYPES = listOf(
            SoilSchema.TYPE_HEADING, SoilSchema.TYPE_TEXT,
            SoilSchema.TYPE_SHAPE, SoilSchema.TYPE_STICKY,
        )
    }
}
