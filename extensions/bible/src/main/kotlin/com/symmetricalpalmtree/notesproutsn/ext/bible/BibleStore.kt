package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads

/** The extension cannot reach its storage (any store exception — the host's rule: treat all as unavailable). */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/**
 * The reader's one row of per-device state over the host's `IExtensionStore` (arc 37 / B0, the
 * tag manager's `TagStore` shape). **Blocking** — every call runs on `Dispatchers.IO` (the
 * screen) or a Binder thread (the service's `begin`/`end`), never Main. The extension writes
 * nothing to disk itself: this store is the host's, lent for the showing.
 *
 * The schema is [BibleSchema.V1] and [load] applies it — the ONE door, because the host's gate
 * refuses `exec` / `query` on a binder that has not declared. Every public method applies it
 * first: that is idempotent, and a matching version costs one `SELECT` host-side.
 *
 * Every exception becomes [StoreUnavailable] via [guard]. **The position is never logged** — it
 * is only a reference into scripture, but the rule is uniform across the reader: nothing that
 * names where the user has read is written to a log, on either side of the seam.
 */
class BibleStore(private val store: IExtensionStore) {

    /** Declare the schema. Idempotent, and the only door — nothing may reach the store before it. */
    fun load() = guard { store.applySchema(BibleSchema.V1) }

    /** The last-read position, or null when nothing has been saved yet. */
    fun readPosition(): String? = guard {
        store.applySchema(BibleSchema.V1)
        val rows = StoreReads.all(store, Statement(BibleSql.SELECT_STATE, BibleSql.KEY_POSITION))
        rows.rows.firstOrNull()?.text("value")
    }

    /** Save the last-read position. One statement — `INSERT OR REPLACE` is safe because `state`
     *  has no children for a replacement to cascade away. */
    fun writePosition(value: String) = guard {
        store.applySchema(BibleSchema.V1)
        StoreReads.exec(store, Statement(BibleSql.UPSERT_STATE, BibleSql.KEY_POSITION, value))
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
