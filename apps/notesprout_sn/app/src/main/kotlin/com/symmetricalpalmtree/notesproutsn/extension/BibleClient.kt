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
 *
 * **B9 "Send"** gave the showing its one launch extra ([open]'s `sendEnabled` →
 * `EXTRA_BIBLE_SEND_ENABLED`, a boolean — the calendar's shape, still no content on the Intent)
 * and one more call on the held bind, [takeOutgoingReference]: the reference the reader's Send
 * parked, read right after the screen returned `RESULT_BIBLE_SEND` and before [finish]. Only
 * against a reader declaring [ExtensionContract.MIN_API_VERSION_FOR_BIBLE_SEND] ([BibleEntry]'s
 * gate).
 */
class BibleClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<IBible>? = null
    private var storeBinder: ExtensionStoreBinder? = null

    val isOpen: Boolean get() = held != null

    /** Pre-open the store, hold the bind, `begin`, and build the screen Intent — or null (logged). */
    suspend fun open(): Intent? = open(reference = null, sendEnabled = false)

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
    suspend fun open(reference: String?, sendEnabled: Boolean = false): Intent? {
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
        Slog.d(TAG) { "open: ready in ${System.currentTimeMillis() - t0} ms (passage=${reference != null}, send=$sendEnabled)" }
        return Intent(ExtensionContract.ACTION_BIBLE_SCREEN).setPackage(ref.packageName).apply {
            // B9: the one boolean the Bible Intent carries, and only when a notebook is behind the
            // reader — the library's door never sets it, so the reader shows no Send there.
            if (sendEnabled) putExtra(ExtensionContract.EXTRA_BIBLE_SEND_ENABLED, true)
        }
    }

    /**
     * B9 — the reference the reader's Send parked, over the bind this showing still holds: the
     * calendar's `drainOutgoing` on a reference instead of ink. Null when nothing was parked, when
     * the bind is gone, or when the call failed (logged) — every one of which means nothing lands.
     * Once-only on the reader's side. The reference is never logged.
     */
    suspend fun takeOutgoingReference(): ResolvedReference? {
        val binding = held ?: return null
        if (binding.isDead) return null
        return try {
            val taken = binding.call(CALL_TIMEOUT_MS) { it.takeOutgoingReference() }
            Slog.d(TAG) { "takeOutgoingReference: ${if (taken == null) "nothing" else "a reference"}" }
            taken
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "takeOutgoingReference failed: ${e.message}" }
            null
        }
    }

    /**
     * Arc 40 "Verses" — the verses of [wire] as Markdown, over the bind this showing still holds:
     * the reader's Send chose the words, so the host asks for them right after
     * [takeOutgoingReference] and before [finish]. Null when the bind is gone or the call failed
     * (logged); a `STATUS_TOO_LONG` reply is the reader's own refusal. Never logged.
     */
    suspend fun passageText(wire: String): PassageText? {
        val binding = held ?: return null
        if (binding.isDead) return null
        return try {
            val text = binding.call(TEXT_TIMEOUT_MS) { it.passageText(wire) }
            Slog.d(TAG) { "passageText: ${text?.toString() ?: "nothing"}" }
            text
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "passageText failed: ${e.message}" }
            null
        }
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

        /** `passageText`'s budget — one open of the source and one short read, the same cold case
         *  `resolve` allows for (a fresh reader's first call copies the asset out of the APK). */
        const val TEXT_TIMEOUT_MS = RESOLVE_TIMEOUT_MS

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

        /**
         * Arc 40 "Verses" — **bind-per-call, no store**, `resolve`'s twin: the verses of [wire]
         * as Markdown for the notebook's two dialog-side doors (the reference dialog's switch and
         * the lasso bar's Verses), where no showing is open. Null covers "could not ask" and "could
         * not read"; a `STATUS_TOO_LONG` reply is the reader's refusal, which the caller explains.
         * Neither the wire nor the text is logged — a status, a length and a duration.
         */
        suspend fun passageText(context: Context, ref: ProviderRef, wire: String): PassageText? {
            val t0 = System.currentTimeMillis()
            return try {
                val answer = ExtensionBinder.call(
                    context.applicationContext, ref, ExtensionContract.ACTION_BIBLE, TAG,
                    asInterface = { IBible.Stub.asInterface(it) },
                    callTimeoutMs = TEXT_TIMEOUT_MS,
                ) { iface -> iface.passageText(wire) }
                Slog.d(TAG) { "passageText: ${answer?.toString() ?: "nothing"} in ${System.currentTimeMillis() - t0} ms" }
                answer
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "passageText failed: ${e.message}" }
                null
            }
        }
    }
}
