package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The extension side's two calls over an `IExtensionStore` (arc 22 / X1), written once so no
 * extension re-writes the chunk loop or the payload handshake.
 *
 * Both are **blocking Binder I/O** — an IO thread or a Binder thread, never Main. Both let every
 * exception through unchanged (`SecurityException` / `IllegalArgumentException` /
 * `IllegalStateException` from the host, `RemoteException` from the bind): the extension's own
 * adapter is where "every one of them means unavailable" is decided.
 */
object StoreReads {

    /**
     * Run one `SELECT` to completion: `query`, then `next` while the host says there is more, the
     * chunks stitched into one [StoreRows]. On **any** failure between chunks the parked remainder
     * is closed before the exception is rethrown, so a failed read never leaves a handle behind.
     *
     * A result above `STORE_MAX_RESULT_BYTES` is the host's typed refusal
     * (`STORE_RESULT_LARGE`); the fix is a `LIMIT`/keyset loop on the extension's side, not a
     * bigger read.
     */
    fun all(store: IExtensionStore, statement: Statement): StoreRows {
        var result = send(StoreCodec.encodeStatements(listOf(statement))) { store.query(it) }
        val first = StoreCodec.decodeRows(result.payload.readAndClose())
        if (!result.more) return first
        val handle = result.handle
        val cells = ArrayList(first.cells)
        try {
            while (result.more) {
                result = store.next(handle)
                cells += StoreCodec.decodeRows(result.payload.readAndClose()).cells
            }
        } catch (t: Throwable) {
            runCatching { store.close(handle) }
            throw t
        }
        return StoreRows(first.columns, cells)
    }

    /** [all] over a statement built from [sql] and [args] ([Cell.of] applied to each). */
    fun all(store: IExtensionStore, sql: String, vararg args: Any?): StoreRows =
        all(store, Statement(sql, *args))

    /**
     * Run [statements] as ONE transaction (`exec`), all-or-nothing; answers `changes()` per
     * statement in order. A failure anywhere rolled the whole batch back before it was thrown.
     */
    fun exec(store: IExtensionStore, statements: List<Statement>): LongArray =
        send(StoreCodec.encodeStatements(statements)) { store.exec(it) }

    /** [exec] of a single statement. */
    fun exec(store: IExtensionStore, statement: Statement): Long = exec(store, listOf(statement))[0]

    /** [exec] of a single statement built from [sql] and [args]. */
    fun exec(store: IExtensionStore, sql: String, vararg args: Any?): Long = exec(store, Statement(sql, *args))

    /** Wrap [bytes] in the carrier its size calls for, make the call, and — the sender's half of the
     *  ashmem handshake — close our own handle on a region once the call has returned. */
    private inline fun <T> send(bytes: ByteArray, call: (StorePayload) -> T): T {
        val payload = StorePayload.of(bytes)
        try {
            return call(payload)
        } finally {
            payload.region?.memory?.close()
        }
    }
}
