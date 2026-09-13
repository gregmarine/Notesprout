package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.content.Intent
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import com.symmetricalpalmtree.notesproutsn.data.extstore.lease
import kotlinx.coroutines.CancellationException

/**
 * The host's client for the one Bible reader (arc 37 / B0 — SN's NINTH capability point, the fifth
 * screen-owning one and the second with no paper). One call shape only — **the showing**, the tag
 * manager's bracket minus `configureShowing`, because the host has nothing to tell the reader:
 * `ExtensionStores.lease` on IO (the pre-open rule — a cold KDF is seconds on the Nomad and must
 * never sit inside a call timeout) → mint one uid-bound [ExtensionStoreBinder] →
 * [ExtensionBinder.hold] (signature re-checked at bind) → `begin(store)` → the screen Intent, which
 * carries the action and the package and **nothing else**. Returns null on any failure (reason
 * logged; everything opened so far is released). The caller launches with an
 * `ActivityResultLauncher` — a plain `startActivity` leaves the extension's `callingPackage` null
 * and its screen refuses it.
 *
 * The store lent here holds the reader's one row of per-device state — where it was left off.
 * Scripture never crosses this seam in either direction, and nothing of it is logged.
 *
 * **Arc 38 / R3 gave it the tag manager's second call shape as well**: [Companion.resolve] is
 * bind-per-call with no store — the operation *is* the call and nothing is shown — and the showing
 * can now be opened on a passage ([open] with a reference, which calls `beginAt` instead of
 * `begin`). Both ride the R1 tails, which only a reader declaring
 * [ExtensionContract.MIN_API_VERSION_FOR_BIBLE_REFERENCE] has; the gate is [BibleEntry]'s.
 * A reference — the user's own words on the way in, the canonical wire on the way back — is
 * **never logged on either side**: a reference names where the user has read.
 */
class BibleClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<IBible>? = null
    private var storeBinder: ExtensionStoreBinder? = null

    val isOpen: Boolean get() = held != null

    /** Pre-open the store, hold the bind, `begin`, and build the screen Intent — or null (logged). */
    suspend fun open(): Intent? = open(reference = null)

    /**
     * The showing, opened on a passage (arc 38 / R3): `beginAt(store, reference)` in place of
     * `begin(store)` when [reference] is a resolved reference's wire form, and the plain `begin`
     * when it is null. Everything else — the store lease, the hold, the Intent, the failure road —
     * is the one path above.
     *
     * The caller is responsible for only ever passing a reference to a reader that declares
     * [ExtensionContract.MIN_API_VERSION_FOR_BIBLE_REFERENCE] or above ([BibleEntry] is the one
     * that does). The reference itself is never logged.
     */
    suspend fun open(reference: String?): Intent? {
        if (held != null) { Slog.d(TAG) { "open: already open" }; return null }
        val t0 = System.currentTimeMillis()
        val store = ExtensionStores.lease(appContext, ref.packageName, TAG) ?: return null
        val binding = try {
            ExtensionBinder.hold(appContext, ref, ExtensionContract.ACTION_BIBLE, TAG,
                asInterface = { IBible.Stub.asInterface(it) })
        } catch (e: CancellationException) {
            store.revoke(); throw e
        } catch (e: ExtensionCallException) {
            store.revoke()
            Slog.d(TAG) { "open failed: hold ${e.message}" }
            return null
        }
        held = binding
        storeBinder = store
        try {
            binding.call(CALL_TIMEOUT_MS) {
                if (reference == null) it.begin(store) else it.beginAt(store, reference)
            }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "open failed: begin ${e.message}" }
            finish()
            return null
        }
        Slog.d(TAG) { "open: ready in ${System.currentTimeMillis() - t0} ms (passage=${reference != null})" }
        return Intent(ExtensionContract.ACTION_BIBLE_SCREEN).setPackage(ref.packageName)
    }

    /** `end()` (best effort, ≤ [CALL_TIMEOUT_MS]), then unbind + revoke in `finally`. Idempotent. */
    suspend fun finish() {
        val binding = held ?: return
        held = null
        val store = storeBinder
        storeBinder = null
        try {
            if (!binding.isDead) binding.call(CALL_TIMEOUT_MS) { it.end() }
            Slog.d(TAG) { "finish: end ok" }
        } catch (e: CancellationException) {
            throw e   // the caller's scope is gone — the finally below still releases the bind
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "finish: end failed ${e.message}" }
        } finally {
            binding.close()
            store?.revoke()
        }
    }

    companion object {
        const val TAG = "BibleClient"
        const val CALL_TIMEOUT_MS = 2_000L

        /**
         * [resolve]'s budget, and deliberately generous (arc 38 / R3).
         *
         * The work is one Binder round trip, but the *first* one of a freshly installed reader
         * carries `ContentInstaller`'s copy of an 11.6 MB asset out of the APK before a single row
         * is read — seconds on a Nomad, once. A timeout here undoes nothing (a Binder call cannot
         * be cancelled) and costs only the honest "could not check" answer, so it is sized for the
         * cold case rather than the warm one. **To be re-measured on the Nomad** against the
         * duration this call logs.
         */
        const val RESOLVE_TIMEOUT_MS = 8_000L

        /**
         * **Bind-per-call, no store** (the tag manager's second call shape): read [text] — the
         * user's own words — as one or more scripture references and answer the canonical form, or
         * null when the reader does not know it.
         *
         * The operation *is* the call, and the reader needs no state to answer it, so there is no
         * held bind and no store lease. Null covers both "not a reference" and "could not ask"
         * (a dead package, a refused bind, a timeout) — the two are one answer to the caller, which
         * says the same honest thing either way: nothing was created.
         *
         * **Neither the text nor the wire is ever logged** — a duration and the answer's shape.
         */
        suspend fun resolve(context: Context, ref: ProviderRef, text: String): ResolvedReference? {
            val t0 = System.currentTimeMillis()
            return try {
                val answer = ExtensionBinder.call(
                    context.applicationContext, ref, ExtensionContract.ACTION_BIBLE, TAG,
                    asInterface = { IBible.Stub.asInterface(it) },
                    callTimeoutMs = RESOLVE_TIMEOUT_MS,
                ) { iface -> iface.resolve(text) }
                Slog.d(TAG) {
                    "resolve: ${text.length} chars → ${if (answer == null) "no" else "a"} reference " +
                        "in ${System.currentTimeMillis() - t0} ms"
                }
                answer
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "resolve failed: ${e.message}" }
                null
            }
        }
    }
}
