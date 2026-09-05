package com.symmetricalpalmtree.notesproutsn.ext.cloud

import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads

/** The extension cannot reach its storage (any store exception — the host's rule: treat all as
 *  unavailable). Mirrors `:ext-tags`' `StoreUnavailable`. */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/**
 * The Drive provider's `account` table over the host's `IExtensionStore` (arc 25 / V1). **Blocking**
 * — every call runs on a Binder thread (the service's calls) or IO (a future connect flow), never
 * Main. The extension writes nothing to disk itself: this store is the host's, lent for the call.
 *
 * The schema is [DriveSchema.V1] and [load] applies it — the ONE door, because the host's gate
 * refuses `exec` / `query` on a binder that has not declared. Every public method applies it first:
 * idempotent, and a matching version costs one `SELECT` host-side.
 *
 * Every exception that is not this class's own typed failure becomes [StoreUnavailable] — the
 * host's rule, and this extension's whole answer to a store it cannot reach.
 */
class DriveStore(private val store: IExtensionStore) {

    /** Declare the schema. Idempotent, and the only door — nothing may reach the store before it. */
    fun load() = guard { store.applySchema(DriveSchema.V1) }

    /** One value by key, or null when the row is not there. */
    fun value(key: String): String? = guard {
        store.applySchema(DriveSchema.V1)
        StoreReads.all(store, DriveSql.selectValue(key)).rows.firstOrNull()?.text("value")
    }

    /** Write (or overwrite) one value by key. */
    fun put(key: String, value: String) = guard {
        store.applySchema(DriveSchema.V1)
        StoreReads.exec(store, DriveSql.upsertValue(key, value))
        Unit
    }

    /** Forget one key. A no-op if it was never set. */
    fun remove(key: String) = guard {
        store.applySchema(DriveSchema.V1)
        StoreReads.exec(store, DriveSql.deleteValue(key))
        Unit
    }

    /** Forget the whole account — `disconnect`'s call. */
    fun clear() = guard {
        store.applySchema(DriveSchema.V1)
        StoreReads.exec(store, DriveSql.deleteAll())
        Unit
    }

    /** Every failure not already typed becomes [StoreUnavailable]. */
    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: StoreUnavailable) {
            throw e
        } catch (e: Exception) {
            throw StoreUnavailable(e)
        }
}
