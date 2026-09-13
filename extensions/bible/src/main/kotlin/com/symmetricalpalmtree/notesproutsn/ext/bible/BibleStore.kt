package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads

/** The extension cannot reach its storage (any store exception — the host's rule: treat all as unavailable). */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/**
 * The reader's per-device state over the host's `IExtensionStore` (arc 37 / B0, the tag
 * manager's `TagStore` shape; grown by B7 with the recents). **Blocking** — every call runs on
 * `Dispatchers.IO` (the screen) or a Binder thread (the service's `begin`/`end`), never Main. The
 * extension writes nothing to disk itself: this store is the host's, lent for the showing.
 *
 * The schema is [BibleSchema.CURRENT] and [load] applies it — the ONE door, because the host's
 * gate refuses `exec` / `query` on a binder that has not declared. Every public method applies it
 * first: that is idempotent, and a matching version costs one `SELECT` host-side.
 *
 * Every exception becomes [StoreUnavailable] via [guard]. **Neither the position nor a recent
 * chapter is ever logged** — they are only references into scripture, but the rule is uniform
 * across the reader: nothing that names where the user has read is written to a log, on either
 * side of the seam.
 */
class BibleStore(private val store: IExtensionStore) {

    /** Declare the schema. Idempotent, and the only door — nothing may reach the store before it. */
    fun load() = guard { store.applySchema(BibleSchema.CURRENT) }

    /** The last-read position, or null when nothing has been saved yet. */
    fun readPosition(): String? = guard {
        store.applySchema(BibleSchema.CURRENT)
        val rows = StoreReads.all(store, Statement(BibleSql.SELECT_STATE, BibleSql.KEY_POSITION))
        rows.rows.firstOrNull()?.text("value")
    }

    /** Save the last-read position. One statement — `INSERT OR REPLACE` is safe because `state`
     *  has no children for a replacement to cascade away. */
    fun writePosition(value: String) = guard {
        store.applySchema(BibleSchema.CURRENT)
        StoreReads.exec(store, Statement(BibleSql.UPSERT_STATE, BibleSql.KEY_POSITION, value))
        Unit
    }

    /**
     * The recent chapters, newest first, at most [limit]. A row this build cannot read (an
     * unknown book code, a chapter below 1, a cell of the wrong class) is dropped, not thrown —
     * a malformed history row is never a dialog.
     */
    fun readRecents(limit: Int): List<RecentRef> = guard {
        store.applySchema(BibleSchema.CURRENT)
        val rows = StoreReads.all(store, Statement(BibleSql.SELECT_RECENTS, limit.toLong()))
        rows.rows.mapNotNull { row ->
            runCatching {
                RecentRef.of(row.text("usfm"), row.long("chapter").toInt(), row.long("at"))
            }.getOrNull()
        }
    }

    /**
     * Record a pick, stamped [at], and trim the table to its newest [keep] rows — one
     * two-statement batch, so the trim can never run against a store the upsert did not reach.
     */
    fun writeRecent(ref: ChapterRef, at: Long, keep: Int) = guard {
        store.applySchema(BibleSchema.CURRENT)
        StoreReads.exec(
            store,
            listOf(
                Statement(BibleSql.UPSERT_RECENT, ref.usfm, ref.chapter.toLong(), at),
                Statement(BibleSql.TRIM_RECENTS, keep.toLong()),
            ),
        )
        Unit
    }

    /** Every failure is [StoreUnavailable] — the host's own rule, and the extension's whole
     *  answer to a store it cannot reach. */
    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: StoreUnavailable) {
            throw e
        } catch (e: Exception) {
            throw StoreUnavailable(e)
        }
}
