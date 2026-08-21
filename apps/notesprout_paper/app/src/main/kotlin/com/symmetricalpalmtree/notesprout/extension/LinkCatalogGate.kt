package com.symmetricalpalmtree.notesprout.extension

/**
 * The uid + revocation gate behind `LinkCatalogBinder` (arc 7 / L0), with no Android types so it runs
 * on the JVM — the `ExtensionStoreGate` shape without the store: the binder is handed to exactly one
 * extension process for exactly one pick showing, and is dead after it (rule 29 — a catalog binder is
 * a per-showing lens, not a door).
 *
 * Every catalog method first calls [check]: the caller's uid must be [extUid] and the gate not
 * [revoked], else `SecurityException`. [revoke] runs in the client's `finally` beside the unbind and
 * the store binder's revoke, so a late call from an orphaned (timed-out) transaction fails closed.
 * [entry] builds one outward `CatalogEntry` under the caps — the label trimmed to
 * `MAX_CATALOG_LABEL_CHARS` (host names are ≤ 100 chars today; defence in depth) — and [cap] cuts a
 * reply at `MAX_CATALOG_ENTRIES`.
 */
class LinkCatalogGate(
    private val extUid: Int,
    private val callingUid: () -> Int,
) {
    @Volatile
    var revoked: Boolean = false
        private set

    /** After this every catalog method throws `SecurityException`. */
    fun revoke() {
        revoked = true
    }

    fun check() {
        if (revoked) throw SecurityException("catalog binder revoked")
        if (callingUid() != extUid) throw SecurityException("catalog binder belongs to another uid")
    }

    /** One outward row: names + ids + labels only, label capped. */
    fun entry(id: String, kind: Int, label: String): CatalogEntry =
        CatalogEntry(id, kind, label.take(ExtensionContract.MAX_CATALOG_LABEL_CHARS))

    /** A reply carries at most `MAX_CATALOG_ENTRIES` rows; the rest is dropped. */
    fun <T> cap(entries: List<T>): List<T> = entries.take(ExtensionContract.MAX_CATALOG_ENTRIES)
}
