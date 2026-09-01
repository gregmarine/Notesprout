// IExtensionStore.aidl — the host-owned encrypted SQLite store an extension is lent for the life of
// one bind (arc 11 / J2; REPLACED whole by arc 22 / X1 — API_VERSION 6, with a floor). Not a
// capability point: a parameter of the calls that need it.
package com.symmetricalpalmtree.notesproutsn.extension;

import com.symmetricalpalmtree.notesproutsn.extension.StorePayload;
import com.symmetricalpalmtree.notesproutsn.extension.StoreResult;
import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema;

/**
 * A host-owned, encrypted SQLite store scoped to the calling extension. The host mints one binder
 * per bind, bound to that extension's uid, and revokes it when the bind ends. The extension
 * declares its tables once (applySchema), then sends parameterized SQL and reads rows back; the
 * host validates every statement (StoreSql: one statement, SELECT/WITH for query and
 * INSERT/REPLACE/UPDATE/DELETE/WITH for exec, no PRAGMA/ATTACH/DDL/transaction control, no
 * host-reserved names), runs it on the one connection it owns, and encodes the rows (StoreCodec).
 *
 * Only SecurityException (wrong uid / revoked), IllegalArgumentException (a statement or schema the
 * validator refuses, an unreadable payload) and IllegalStateException (an SQLite failure — a
 * constraint violation included — or one of the ExtensionContract.STORE_* typed refusals, compared
 * verbatim) cross. The extension treats every one of them, and RemoteException, the same way:
 * "store unavailable".
 *
 * API_VERSION 6 REPLACED the version-1..5 key/value interface (get/put/delete/keys/putLarge/getLarge)
 * — these methods stand in those transaction codes, which is why store-taking services carry the
 * MIN_API_VERSION_FOR_STORE floor.
 */
interface IExtensionStore {
    /** The schema version applied to this store (0 = nothing declared yet). */
    int schemaVersion();

    /**
     * Idempotent: runs the steps `applied + 1 .. schema.version`, each in its own transaction with
     * the version bump (crash-resumable — a step that lands is never re-run). A no-op when the
     * versions already match (one SELECT). IllegalStateException(STORE_SCHEMA_NEWER) when the store
     * is at a version above schema.version; IllegalArgumentException for DDL the validator refuses.
     * Must be called on a binder before exec / query — see STORE_SCHEMA_UNAPPLIED.
     */
    void applySchema(in StoreSchema schema);

    /**
     * N statements (a StoreCodec statement batch, 1..STORE_MAX_BATCH_STATEMENTS) in ONE transaction,
     * all-or-nothing; answers changes() per statement, in order. A failure anywhere (SQLite, a
     * constraint, the validator) rolls the whole batch back and throws. No transaction is ever held
     * open across Binder calls.
     */
    long[] exec(in StorePayload batch);

    /**
     * ONE statement (a StoreCodec batch of exactly one). The host runs it to completion, encodes the
     * rows (StoreCodec rows) and hands back the first chunk; `more` says whether next() must follow,
     * and then `handle` names the parked remainder (a binder holds at most STORE_MAX_OPEN_RESULTS —
     * STORE_RESULTS_OPEN past that). STORE_RESULT_LARGE / STORE_ROW_LARGE when the result or one
     * row will not fit. StoreReads.all is the loop every extension takes.
     */
    StoreResult query(in StorePayload statement);

    /** The following chunk of a parked result. IllegalStateException when the handle is unknown or
     *  already drained. The last chunk answers with `more = false` and the handle is released. */
    StoreResult next(int handle);

    /** Drop an unfinished result early (no-op for an unknown handle). Every parked result is
     *  dropped when the binder is revoked. */
    void close(int handle);
}
