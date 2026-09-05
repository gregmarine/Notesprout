package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **The connect showing** (arc 25 / V2) — the one held bind on the cloud point, and the only place
 * the seam looks like the tag manager's bracket rather than [CloudClient]'s bind-per-call.
 *
 * The sign-in is a screen the *extension* owns (`ACTION_CLOUD_STORAGE_SCREEN`): the host has no
 * INTERNET permission and never sees a token. But the screen has to **persist** what it wins, and an
 * extension writes nothing to disk itself, ever — the store is the host's to lend. So the store has
 * to outlive a single call, which is exactly what a held bind is for:
 *
 *  1. pre-open the store on IO (the cold KDF is seconds on the Nomad and must never sit inside a
 *     call timeout) and mint one uid-bound [ExtensionStoreBinder];
 *  2. [ExtensionBinder.hold] — the signature is re-checked at the bind, not only at discovery;
 *  3. `beginConnect(store)` parks it for the screen the host is about to launch;
 *  4. the caller launches the Intent [open] returns through an `ActivityResultLauncher` — **nothing
 *     rides it**, no extras at all, and a plain `startActivity` would leave the extension's
 *     `callingPackage` null and its screen would refuse it;
 *  5. on the result, [finish]: `endConnect()` best effort, then unbind and revoke in one `finally`
 *     on every path. The revoke is what makes the store dead to anything the screen still holds.
 *
 * The screen answers `RESULT_OK` **only after the token is in the store**, so the host learns
 * nothing from the bracket itself: its next [CloudClient.status] is the truth, and that is what the
 * caller re-reads either way.
 *
 * Log tag [TAG] — durations and result codes. **The account label never appears here**; the host
 * does not even see it until it asks for a status.
 */
class CloudConnectClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<ICloudStorage>? = null
    private var storeBinder: ExtensionStoreBinder? = null

    val isOpen: Boolean get() = held != null

    /** Pre-open the store, hold the bind, `beginConnect`, and build the screen Intent — or null
     *  (the reason logged; everything opened so far released). */
    suspend fun open(): Intent? {
        if (held != null) { Slog.d(TAG) { "open: already open" }; return null }
        val t0 = System.currentTimeMillis()
        val store = openStore() ?: return null
        val binding = try {
            ExtensionBinder.hold(
                appContext, ref, CloudContract.ACTION_CLOUD_STORAGE, TAG,
                asInterface = { ICloudStorage.Stub.asInterface(it) },
            )
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
            binding.call(CALL_TIMEOUT_MS) { it.beginConnect(store) }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "open failed: beginConnect ${e.message}" }
            finish()
            return null
        }
        Slog.d(TAG) { "open: ready in ${System.currentTimeMillis() - t0} ms" }
        return Intent(CloudContract.ACTION_CLOUD_STORAGE_SCREEN).setPackage(ref.packageName)
    }

    /** `endConnect()` (best effort, ≤ [CALL_TIMEOUT_MS]), then unbind + revoke in `finally`.
     *  Idempotent, and the backstop for a caller destroyed while the screen is up. */
    suspend fun finish() {
        val binding = held ?: return
        held = null
        val store = storeBinder
        storeBinder = null
        try {
            if (!binding.isDead) binding.call(CALL_TIMEOUT_MS) { it.endConnect() }
            Slog.d(TAG) { "finish: endConnect ok" }
        } catch (e: CancellationException) {
            throw e   // the caller's scope is gone — the finally below still releases the bind
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "finish: endConnect failed ${e.message}" }
        } finally {
            binding.close()
            store?.revoke()
        }
    }

    /** Open the provider's store on IO and wrap it in a uid-bound binder, or null (logged). */
    private suspend fun openStore(): ExtensionStoreBinder? =
        try {
            val db = withContext(Dispatchers.IO) { ExtensionStores.open(appContext, ref.packageName) }
            val extUid = appContext.packageManager.getPackageUid(ref.packageName, 0)
            ExtensionStoreBinder(db, extUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageManager.NameNotFoundException) {
            Slog.d(TAG) { "store open failed: package gone ${ref.packageName}" }
            null
        } catch (e: Exception) {
            Slog.d(TAG) { "store open failed: ${e.javaClass.simpleName}: ${e.message}" }
            null
        }

    companion object {
        const val TAG = "CloudConnectClient"

        /**
         * The bracket's budget — the tag manager's 2 s, and for the same reason: `beginConnect` and
         * `endConnect` are two **store-less** calls that park and forget a binder reference. Neither
         * touches the network (the sign-in itself happens in the screen, not in a Binder call) and
         * neither touches the store, so there is no work here for a larger number to cover.
         */
        const val CALL_TIMEOUT_MS = 2_000L
    }
}
