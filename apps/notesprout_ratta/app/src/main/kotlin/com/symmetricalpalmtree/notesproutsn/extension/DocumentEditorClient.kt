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
 * The host's client for the one document editor (arc 19 / M3) — [ScratchPadClient]'s shape for SN's
 * second **held** bind: the operation is the showing of the extension's screen, so the bind brackets
 * it. One instance per showing; [open] / [finish] are idempotent and the caller runs [finish] from
 * its result callback **and** from `onDestroy` while still open.
 *
 * [open]: `ExtensionStores.open` on IO (the pre-open rule — a cold KDF is ≈ 2 s on the Nomad and
 * must never sit inside a call timeout) → mint one uid-bound [ExtensionStoreBinder] → mint one
 * uid-bound [DocumentHostBinder] over a fresh [DocumentHostSession] → [ExtensionBinder.hold]
 * (signature re-checked at bind) → `begin(store, host)` ≤ [CALL_TIMEOUT_MS] → the screen Intent.
 *
 * **The Intent carries nothing.** No ids, no page key, no text, not a single extra — everything
 * this seam moves crosses the two binders, which is what makes the whole of it uid-gated and
 * revocable. (The scratch pad's two booleans were the last thing to ride an Intent on any SN seam;
 * this point starts with none.) The caller launches with an `ActivityResultLauncher` — a plain
 * `startActivity` leaves the extension's `callingPackage` null and its screen refuses it.
 *
 * [finish]: `end()` ≤ [END_TIMEOUT_MS] in a `try`, then in `finally` unbind, revoke the store
 * binder and revoke the host binder (which drops the showing's read window and any half-received
 * save with it). Every path: result, cancel, the caller's death, a failed `begin`.
 *
 * Log tag [TAG] — counts + durations, never a character of the document.
 */
class DocumentEditorClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<IDocumentEditor>? = null
    private var storeBinder: ExtensionStoreBinder? = null
    private var hostBinder: DocumentHostBinder? = null

    val isOpen: Boolean get() = held != null

    /**
     * Pre-open the store, mint both binders, hold the bind, `begin(store, host)` and build the
     * screen Intent — or null on any failure (reason logged; everything opened so far released).
     * [hooks] is the open notebook's read/write half — see [DocumentHostBinder.Hooks] for the
     * thread contract it runs under.
     */
    suspend fun open(hooks: DocumentHostBinder.Hooks): Intent? {
        if (held != null) { Slog.d(TAG) { "open: already open" }; return null }
        val t0 = System.currentTimeMillis()
        val store: ExtensionStoreBinder
        val host: DocumentHostBinder
        try {
            val db = withContext(Dispatchers.IO) { ExtensionStores.open(appContext, ref.packageName) }
            val extUid = appContext.packageManager.getPackageUid(ref.packageName, 0)
            store = ExtensionStoreBinder(db, extUid)
            host = DocumentHostBinder(extUid, DocumentHostSession(), hooks)
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
            ExtensionBinder.hold(appContext, ref, DocumentContract.ACTION_DOCUMENT_EDITOR, TAG,
                asInterface = { IDocumentEditor.Stub.asInterface(it) })
        } catch (e: CancellationException) {
            store.revoke(); host.revoke(); throw e
        } catch (e: ExtensionCallException) {
            store.revoke(); host.revoke()
            Slog.d(TAG) { "open failed: hold ${e.message}" }
            return null
        }
        held = binding
        storeBinder = store
        hostBinder = host
        try {
            binding.call(CALL_TIMEOUT_MS) { it.begin(store, host) }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "open failed: begin ${e.message}" }
            finish()
            return null
        }
        Slog.d(TAG) { "open: begin ok in ${System.currentTimeMillis() - t0} ms" }
        // Nothing rides the Intent — see the class doc. Only the package, so the exported screen of
        // some other app can never answer this action.
        return Intent(DocumentContract.ACTION_DOCUMENT_EDITOR_SCREEN).setPackage(ref.packageName)
    }

    /**
     * `end()` (best effort, ≤ [END_TIMEOUT_MS]), then unbind + both revokes in `finally`. Idempotent.
     *
     * **Why `end()` gets its own, longer clock (M4).** `end()` is not a question — it is the
     * editor's last chance to push text it has not saved yet, and the extension's handler flushes
     * it synchronously through the host binder before answering. So the wait here has to cover a
     * full-size document's chunks *plus* whatever the host's own side of those saves costs, which
     * on the reconnect path includes `DocumentHostHooks`' bounded wait for a `.soil` that is still
     * opening. [CALL_TIMEOUT_MS] is right for `begin` (nothing but a state read behind it) and far
     * too tight for that. A Binder call cannot be cancelled in any case — the timeout only bounds
     * how long we *wait* for it, so the cost of it being generous is nothing, and the cost of it
     * being short is the user's last edit.
     */
    suspend fun finish() {
        val binding = held ?: return
        held = null
        val store = storeBinder
        storeBinder = null
        val host = hostBinder
        hostBinder = null
        try {
            if (!binding.isDead) binding.call(END_TIMEOUT_MS) { it.end() }
            Slog.d(TAG) { "finish: end ok" }
        } catch (e: CancellationException) {
            throw e   // the caller's scope is gone — the finally below still releases the bind
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "finish: end failed ${e.message}" }
        } finally {
            binding.close()
            store?.revoke()
            // Revoking the host binder also clears its session — the showing's read window and any
            // half-received save go with the bind, never outlive it.
            host?.revoke()
        }
    }

    companion object {
        const val TAG = "DocumentEditorClient"

        /** `begin` — a state read and nothing else. */
        const val CALL_TIMEOUT_MS = 2_000L

        /** `end` only — the extension's final flush rides it. See [finish]. */
        const val END_TIMEOUT_MS = 15_000L
    }
}
