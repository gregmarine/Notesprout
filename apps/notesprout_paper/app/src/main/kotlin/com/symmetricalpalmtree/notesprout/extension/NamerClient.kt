package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesprout.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesprout.data.extstore.ExtensionStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bind-per-operation client for the one notebook namer — the shared [ExtensionBinder] path (explicit
 * component, signature re-checked at bind, `BIND_AUTO_CREATE` on the app context, bind ≤ 3 s, every
 * call ≤ 2 s, IO, unbind in `finally`, every failure → [ExtensionCallException]) plus the store:
 * calls that need it open the extension's store on IO **before** binding (pre-open rule — a cold KDF
 * never sits inside the call timeout), mint one uid-bound [ExtensionStoreBinder] for the bind, and
 * revoke it in the same `finally` as the unbind.
 *
 * Everything received is untrusted: [SchemeField] strings are truncated; a `defaultName` over
 * [ExtensionContract.MAX_NAME_CHARS] is dropped here and the rest of the name rule is applied by the
 * caller (`NewNotebookActivity.acceptDefaultName`).
 */
class NamerClient(context: Context, private val ref: ProviderRef) {

    private val appContext = context.applicationContext

    /** How to draw the scheme field, strings truncated to sane lengths. No store. */
    suspend fun describeField(): SchemeField = call(store = false) { namer, _ ->
        val f = namer.describeField() ?: throw ExtensionCallException("describeField returned null")
        SchemeField(f.label.take(MAX_LABEL), f.hint.take(MAX_HINT), f.help.take(MAX_HELP))
    }

    /** The scheme stored for [folderId], or null; capped at [ExtensionContract.MAX_NAME_CHARS]. */
    suspend fun currentScheme(folderId: String): String? = call(store = true) { namer, store ->
        namer.currentScheme(store, folderId)?.take(ExtensionContract.MAX_NAME_CHARS)
    }

    /** null if acceptable, else the extension's user-facing error (truncated). No store. */
    suspend fun validate(scheme: String): String? = call(store = false) { namer, _ ->
        namer.validateScheme(scheme)?.take(MAX_ERROR)
    }

    /** Store [scheme] for [folderId]; blank clears. Throws [ExtensionCallException] on any failure. */
    suspend fun save(folderId: String, scheme: String) = call(store = true) { namer, store ->
        namer.saveScheme(store, folderId, scheme)
    }

    /**
     * The extension's default name for a new notebook in [folderId], or null when the folder has no
     * scheme. Only the folder UUID and the sibling notebook names cross (the recorded widening).
     */
    suspend fun defaultName(folderId: String, siblingNames: List<String>): String? = call(store = true) { namer, store ->
        // Over-length is rejected here, before the string rides an Intent; the name rule itself is
        // applied by NewNotebookActivity.acceptDefaultName.
        namer.defaultName(store, folderId, siblingNames)?.takeIf { it.length <= ExtensionContract.MAX_NAME_CHARS }
    }

    /**
     * Bind, run [block] on IO under [CALL_TIMEOUT_MS], unbind — the shared [ExtensionBinder] path.
     * When [store] is set the extension's store is opened first (IO, before the bind) and a per-bind
     * [ExtensionStoreBinder] is handed to [block]; it is revoked in this method's `finally` — the same
     * breath as the unbind — whatever happened. On timeout the caller resumes with
     * [ExtensionCallException] while the orphaned call finishes on its own IO thread (its result is
     * discarded — and its store binder is revoked, so a late store call fails closed).
     */
    private suspend fun <T> call(store: Boolean, block: (INotebookNamer, IExtensionStore?) -> T): T {
        val storeBinder: ExtensionStoreBinder? = if (store) {
            val db = try {
                withContext(Dispatchers.IO) { ExtensionStores.open(appContext, ref.packageName) }
            } catch (e: CancellationException) {
                throw e   // the caller's scope is gone — never disguise that as a namer failure
            } catch (e: Exception) {
                throw ExtensionCallException("store open failed: ${e.message}", e)
            }
            val extUid = try {
                appContext.packageManager.getPackageUid(ref.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                throw ExtensionCallException("package gone: ${ref.packageName}", e)
            }
            ExtensionStoreBinder(db, extUid)
        } else null

        try {
            return ExtensionBinder.call(
                appContext, ref, ExtensionContract.ACTION_NOTEBOOK_NAMER, TAG,
                asInterface = { INotebookNamer.Stub.asInterface(it) },
                callTimeoutMs = CALL_TIMEOUT_MS,
            ) { namer -> block(namer, storeBinder) }
        } finally {
            storeBinder?.revoke()
        }
    }

    companion object {
        private const val TAG = "NamerClient"
        const val CALL_TIMEOUT_MS = 2_000L
        const val MAX_LABEL = 40
        const val MAX_HINT = 60
        const val MAX_HELP = 200
        const val MAX_ERROR = 200
    }
}
