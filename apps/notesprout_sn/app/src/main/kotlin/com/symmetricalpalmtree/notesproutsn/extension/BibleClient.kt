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
 */
class BibleClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<IBible>? = null
    private var storeBinder: ExtensionStoreBinder? = null

    val isOpen: Boolean get() = held != null

    /** Pre-open the store, hold the bind, `begin`, and build the screen Intent — or null (logged). */
    suspend fun open(): Intent? {
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
            binding.call(CALL_TIMEOUT_MS) { it.begin(store) }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "open failed: begin ${e.message}" }
            finish()
            return null
        }
        Slog.d(TAG) { "open: ready in ${System.currentTimeMillis() - t0} ms" }
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
    }
}
