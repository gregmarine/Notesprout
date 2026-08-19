package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesprout.data.extstore.ExtensionStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The host's client for the one scratch pad (arc 6 / S0) — the first **held** bind: the operation is
 * the showing of the extension's screen, so the bind brackets it (rule 26). One instance per calling
 * screen; [open] / [finish] are idempotent and the caller runs [finish] from its result callback
 * **and** from `onDestroy` while still open.
 *
 * [open]: `ExtensionStores.open` on IO (pre-open rule — the cold KDF never sits inside a call
 * timeout) → mint one uid-bound [ExtensionStoreBinder] → [ExtensionBinder.hold] (signature
 * re-checked at bind) → `begin(store)` ≤ [CALL_TIMEOUT_MS] → the screen Intent
 * ([ExtensionContract.ACTION_SCRATCH_PAD_SCREEN], `setPackage(ref.packageName)`, the two boolean
 * extras — **nothing else rides the Intent**, the ink goes through the held service in S2). Returns
 * null on any failure (reason logged; everything opened so far is released) — the caller shows the
 * core's `scratch_failed` dialog. The caller launches the Intent with an `ActivityResultLauncher`
 * (`startActivityForResult`-style, so the extension's `callingPackage` check can pass — risk 2).
 *
 * [finish]: `end()` ≤ [CALL_TIMEOUT_MS] in a `try`, then unbind + revoke the store binder in
 * `finally` — every path: result, cancel, the caller's death, a failed `begin`.
 *
 * S2 adds `send` (`receiveInk` per chunk) and `drainOutgoing` (`takeOutgoing` until an empty
 * bundle or the caps) on the same held bind. Log tag [TAG] — counts + durations, never a stroke.
 */
class ScratchPadClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<IScratchPad>? = null
    private var storeBinder: ExtensionStoreBinder? = null

    val isOpen: Boolean get() = held != null

    /** Pre-open the store, hold the bind, `begin(store)`, and build the screen Intent — or null (logged). */
    suspend fun open(sendEnabled: Boolean, openReceived: Boolean): Intent? {
        if (held != null) { Slog.d(TAG) { "open: already open" }; return null }
        val t0 = System.currentTimeMillis()
        val store: ExtensionStoreBinder
        try {
            val db = withContext(Dispatchers.IO) { ExtensionStores.open(appContext, ref.packageName) }
            val extUid = appContext.packageManager.getPackageUid(ref.packageName, 0)
            store = ExtensionStoreBinder(db, extUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageManager.NameNotFoundException) {
            Slog.d(TAG) { "open failed: package gone ${ref.packageName}" }
            return null
        } catch (e: Exception) {
            Slog.d(TAG) { "open failed: store open ${e.javaClass.simpleName}: ${e.message}" }
            return null
        }
        val binding = try {
            ExtensionBinder.hold(appContext, ref, ExtensionContract.ACTION_SCRATCH_PAD, TAG,
                asInterface = { IScratchPad.Stub.asInterface(it) })
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
        Slog.d(TAG) { "open: begin ok in ${System.currentTimeMillis() - t0} ms (send=$sendEnabled received=$openReceived)" }
        return Intent(ExtensionContract.ACTION_SCRATCH_PAD_SCREEN)
            .setPackage(ref.packageName)
            .putExtra(ExtensionContract.EXTRA_SCRATCH_SEND_ENABLED, sendEnabled)
            .putExtra(ExtensionContract.EXTRA_SCRATCH_OPEN_RECEIVED, openReceived)
    }

    /** `end()` (best effort, ≤ 2 s), then unbind + revoke in `finally`. Idempotent. */
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
        const val TAG = "ScratchPadClient"
        const val CALL_TIMEOUT_MS = 2_000L
    }
}
