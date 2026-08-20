package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesprout.data.extstore.ExtensionStoreDatabase
import com.symmetricalpalmtree.notesprout.data.extstore.ExtensionStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The host's client for the one link provider (arc 7 / L0). Two usage modes on one service:
 *
 * **The pick showing** (create + edit — the arc-6 held-bind recipe): [openPick] pre-opens the store
 * on IO, mints one uid-bound [ExtensionStoreBinder] **and** one [LinkCatalogBinder] (the per-showing
 * catalog lens), holds the bind, calls `beginPick` ≤ 2 s and returns the picker-screen Intent
 * ([ExtensionContract.ACTION_LINK_PICKER_SCREEN], `setPackage`, the one boolean
 * [ExtensionContract.EXTRA_LINK_EDIT] — the payload itself never rides the Intent). The caller
 * launches it with an `ActivityResultLauncher` only (the extension's caller check needs
 * `callingPackage`). On any result [takeChoice] drains `takeResult` on the still-held bind, then
 * `endPick` → unbind → **revoke both binders** in one `finally` ([finish] — idempotent, also the
 * caller's `onDestroy` path).
 *
 * **The one-shot calls**: [resolve] / [chromeOf] are stateless bind-per-operation over
 * [ExtensionBinder.call]; [pushTrail] / [popTrail] / [clearTrail] add the store pre-open + per-bind
 * store binder + revoke-in-`finally` (the `NamerClient` shape). Every call ≤ [CALL_TIMEOUT_MS].
 *
 * Inward is untrusted: the parcelables' `requireValid` runs at unmarshal (a malformed reply fails
 * the transaction → [ExtensionCallException]); [chromeOf] additionally requires same length and
 * masks unknown values to `LINK_CHROME_NONE`. Log tag [TAG] — counts + durations, never a payload.
 */
class LinkClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<ILinkProvider>? = null
    private var storeBinder: ExtensionStoreBinder? = null
    private var catalogBinder: LinkCatalogBinder? = null

    val isOpen: Boolean get() = held != null

    /** Pre-open the store, mint both binders, hold the bind, `beginPick`, build the screen Intent — or null (logged). */
    suspend fun openPick(source: LinkCatalogSource, editPayload: String?): Intent? {
        if (held != null) { Slog.d(TAG) { "openPick: already open" }; return null }
        if (editPayload != null) require(editPayload.length <= ExtensionContract.MAX_LINK_PAYLOAD_CHARS) { "edit payload too long" }
        val t0 = System.currentTimeMillis()
        val store: ExtensionStoreBinder
        val catalog: LinkCatalogBinder
        try {
            val db = openStore()
            val extUid = appContext.packageManager.getPackageUid(ref.packageName, 0)
            store = ExtensionStoreBinder(db, extUid)
            catalog = LinkCatalogBinder(appContext, extUid, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageManager.NameNotFoundException) {
            Slog.d(TAG) { "openPick failed: package gone ${ref.packageName}" }
            return null
        } catch (e: Exception) {
            Slog.d(TAG) { "openPick failed: store open ${e.javaClass.simpleName}: ${e.message}" }
            return null
        }
        val binding = try {
            ExtensionBinder.hold(appContext, ref, ExtensionContract.ACTION_LINK_PROVIDER, TAG,
                asInterface = { ILinkProvider.Stub.asInterface(it) })
        } catch (e: CancellationException) {
            store.revoke(); catalog.revoke(); throw e
        } catch (e: ExtensionCallException) {
            store.revoke(); catalog.revoke()
            Slog.d(TAG) { "openPick failed: hold ${e.message}" }
            return null
        }
        held = binding
        storeBinder = store
        catalogBinder = catalog
        try {
            binding.call(CALL_TIMEOUT_MS) { it.beginPick(store, catalog, source.currentNotebookId, editPayload) }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "openPick failed: beginPick ${e.message}" }
            finish()
            return null
        }
        Slog.d(TAG) { "openPick: beginPick ok in ${System.currentTimeMillis() - t0} ms (edit=${editPayload != null})" }
        return Intent(ExtensionContract.ACTION_LINK_PICKER_SCREEN)
            .setPackage(ref.packageName)
            .putExtra(ExtensionContract.EXTRA_LINK_EDIT, editPayload != null)
    }

    /**
     * After the picker's result (any code): drain the choice on the still-held bind, then tear the
     * showing down ([finish] in `finally` — endPick, unbind, both binders revoked). Null = cancelled,
     * a dead bind, or a refused (malformed) result.
     */
    suspend fun takeChoice(): LinkChoice? {
        val binding = held ?: return null
        return try {
            val choice = binding.call(CALL_TIMEOUT_MS) { it.takeResult() }
            Slog.d(TAG) { "takeChoice: ${choice?.let { "chrome=${it.chrome}, ${it.payload.length} chars" } ?: "null (cancelled)"}" }
            choice
        } catch (e: CancellationException) {
            throw e   // the finally below still tears the showing down
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "takeChoice failed: ${e.message}" }
            null
        } finally {
            finish()
        }
    }

    /** `endPick()` (best effort, ≤ 2 s), then unbind + revoke both binders in `finally`. Idempotent. */
    suspend fun finish() {
        val binding = held ?: return
        held = null
        val store = storeBinder
        storeBinder = null
        val catalog = catalogBinder
        catalogBinder = null
        try {
            if (!binding.isDead) binding.call(CALL_TIMEOUT_MS) { it.endPick() }
            Slog.d(TAG) { "finish: endPick ok" }
        } catch (e: CancellationException) {
            throw e   // the finally below still releases everything
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "finish: endPick failed ${e.message}" }
        } finally {
            binding.close()
            store?.revoke()
            catalog?.revoke()
        }
    }

    // ── One-shot calls (bind-per-operation) ──────────────────────────────────

    /** The typed destination for an opaque [payload], or null when the extension says it is unusable. */
    suspend fun resolve(payload: String): LinkDestination? {
        require(payload.length <= ExtensionContract.MAX_LINK_PAYLOAD_CHARS) { "payload too long" }
        val t0 = System.currentTimeMillis()
        val dest = call { it.resolve(payload) }
        Slog.d(TAG) { "resolve: ${dest?.let { "kind=${it.kind}" } ?: "null"} in ${System.currentTimeMillis() - t0} ms" }
        return dest
    }

    /** The chrome flag per payload — same order, same length (anything else fails the call); unknown values → NONE. */
    suspend fun chromeOf(payloads: List<String>): IntArray {
        payloads.forEach { require(it.length <= ExtensionContract.MAX_LINK_PAYLOAD_CHARS) { "payload too long" } }
        val t0 = System.currentTimeMillis()
        val reply = call { it.chromeOf(payloads) }
            ?: throw ExtensionCallException("chromeOf returned null")
        if (reply.size != payloads.size) throw ExtensionCallException("chromeOf length ${reply.size} != ${payloads.size}")
        val masked = IntArray(reply.size) {
            if (reply[it] == ExtensionContract.LINK_CHROME_UNDERLINE) ExtensionContract.LINK_CHROME_UNDERLINE
            else ExtensionContract.LINK_CHROME_NONE
        }
        Slog.d(TAG) { "chromeOf: ${payloads.size} payload(s) in ${System.currentTimeMillis() - t0} ms" }
        return masked
    }

    /** Push the origin onto the persisted back-trail (the extension's host-owned store). */
    suspend fun pushTrail(entry: TrailEntry) {
        callWithStore { provider, store -> provider.pushTrail(store, entry) }
        Slog.d(TAG) { "pushTrail ok" }
    }

    /** Pop the newest trail entry, or null when the trail is empty. Untrusted — the caller validates the ids. */
    suspend fun popTrail(): TrailEntry? {
        val entry = callWithStore { provider, store -> provider.popTrail(store) }
        Slog.d(TAG) { "popTrail: ${if (entry != null) "entry" else "empty"}" }
        return entry
    }

    /** Clear the trail (a fresh notebook open — no `EXTRA_VIA_LINK`). */
    suspend fun clearTrail() {
        callWithStore { provider, store -> provider.clearTrail(store) }
        Slog.d(TAG) { "clearTrail ok" }
    }

    /** One stateless call: bind, run, unbind in `finally` (`ExtensionBinder.call`). */
    private suspend fun <T> call(block: (ILinkProvider) -> T): T =
        ExtensionBinder.call(appContext, ref, ExtensionContract.ACTION_LINK_PROVIDER, TAG,
            asInterface = { ILinkProvider.Stub.asInterface(it) }, callTimeoutMs = CALL_TIMEOUT_MS) { block(it) }

    /**
     * One store-carrying call (the `NamerClient` shape): the store pre-opened on IO **before** the
     * bind (a cold KDF must never sit inside the 2 s window), one [ExtensionStoreBinder] minted per
     * bind and revoked in the same `finally` as the unbind.
     */
    private suspend fun <T> callWithStore(block: (ILinkProvider, ExtensionStoreBinder) -> T): T {
        val db = openStore()
        val extUid = appContext.packageManager.getPackageUid(ref.packageName, 0)
        val store = ExtensionStoreBinder(db, extUid)
        return try {
            ExtensionBinder.call(appContext, ref, ExtensionContract.ACTION_LINK_PROVIDER, TAG,
                asInterface = { ILinkProvider.Stub.asInterface(it) }, callTimeoutMs = CALL_TIMEOUT_MS) { block(it, store) }
        } finally {
            store.revoke()
        }
    }

    private suspend fun openStore(): ExtensionStoreDatabase =
        withContext(Dispatchers.IO) { ExtensionStores.open(appContext, ref.packageName) }

    companion object {
        const val TAG = "LinkClient"
        const val CALL_TIMEOUT_MS = 2_000L
    }
}
