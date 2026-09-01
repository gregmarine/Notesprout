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

/** A tag cap refused the operation (`TAG_INDEX_FULL`) — nothing was written. */
class TagIndexFullException(cause: Throwable) : ExtensionCallException(ExtensionContract.TAG_INDEX_FULL, cause)

/**
 * There **is** a stored tag index and it could not be read. Deliberately not "empty": the host must
 * say so rather than quietly showing a library with no tags in it.
 */
class TagIndexUnreadableException(cause: Throwable) : ExtensionCallException(INDEX_UNREADABLE, cause) {
    companion object {
        const val INDEX_UNREADABLE = "tag index unreadable"
    }
}

/**
 * The host's client for the one tag manager (arc 21 / W1) — SN's **second** held bind, and the first
 * client to use both bind shapes against one interface.
 *
 * **The showing** ([open] / [finish]) is a held bind, the scratch pad's bracket: `ExtensionStores.open`
 * on IO (the pre-open rule — a cold KDF is seconds on the Nomad and must never sit inside a call
 * timeout) → mint one uid-bound [ExtensionStoreBinder] → [ExtensionBinder.hold] (signature re-checked
 * at bind) → `begin(store)` → `configureShowing(showing)` → the screen Intent, which carries the
 * action and the package and **nothing else**: the target, its label and any prefill went over the
 * bind, because a tag is the user's own words. Returns null on any failure (reason logged; everything
 * opened so far is released). The caller launches with an `ActivityResultLauncher` — a plain
 * `startActivity` leaves the extension's `callingPackage` null and its screen refuses it.
 *
 * **The two calls** ([snapshot], [assign]) are bind-per-call, the recognizer's shape, because the
 * operation *is* the call and nothing is shown. [assign] is the lasso's silent heading→tag (W3);
 * [snapshot] is what the library's search merge reads (W4). Both still pre-open the store.
 *
 * Log tag [TAG] — counts, lengths and durations. **A tag is never logged.**
 */
class TagClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<ITagManager>? = null
    private var storeBinder: ExtensionStoreBinder? = null

    val isOpen: Boolean get() = held != null

    /** Pre-open the store, hold the bind, `begin` + `configureShowing`, and build the screen Intent — or null (logged). */
    suspend fun open(showing: TagShowing): Intent? {
        if (held != null) { Slog.d(TAG) { "open: already open" }; return null }
        val t0 = System.currentTimeMillis()
        val store = openStore() ?: return null
        val binding = try {
            ExtensionBinder.hold(appContext, ref, ExtensionContract.ACTION_TAG_MANAGER, TAG,
                asInterface = { ITagManager.Stub.asInterface(it) })
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
            binding.call(CALL_TIMEOUT_MS) { it.configureShowing(showing) }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "open failed: begin/configure ${e.message}" }
            finish()
            return null
        }
        Slog.d(TAG) { "open: ready in ${System.currentTimeMillis() - t0} ms (mode=${showing.mode})" }
        return Intent(ExtensionContract.ACTION_TAG_MANAGER_SCREEN).setPackage(ref.packageName)
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

    /** Open the extension's store on IO and wrap it in a uid-bound binder, or null (logged). */
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
        const val TAG = "TagClient"
        const val CALL_TIMEOUT_MS = 2_000L

        /** The whole index can be megabytes and its decode is the host's, so a snapshot gets its
         *  own budget rather than a chat-sized one. */
        const val SNAPSHOT_TIMEOUT_MS = 5_000L

        /** The exact `IllegalStateException` message the extension throws for a stored-but-unreadable
         *  index. Compared verbatim, never as a substring (the family rule). */
        private const val INDEX_UNREADABLE = "tag index unreadable"

        /**
         * The whole tag index, or null when the extension has never written one (the library simply
         * has no tags yet). **W4's door** — the library's search merge reads it per query.
         *
         * Bind-per-call, so this is safe to run from a screen that holds no bind of its own; the
         * store is still pre-opened on IO first.
         *
         * @throws TagIndexUnreadableException there is an index and it could not be decoded.
         * @throws ExtensionCallException the bind, the call or the reply failed.
         */
        suspend fun snapshot(context: Context, ref: ProviderRef): TagIndex? {
            val client = TagClient(context, ref)
            val store = client.openStore() ?: throw ExtensionCallException("store unavailable")
            val t0 = System.currentTimeMillis()
            try {
                val bytes = ExtensionBinder.call(
                    client.appContext, ref, ExtensionContract.ACTION_TAG_MANAGER, TAG,
                    asInterface = { ITagManager.Stub.asInterface(it) },
                    callTimeoutMs = SNAPSHOT_TIMEOUT_MS,
                ) { iface ->
                    val value = try {
                        iface.snapshot(store)
                    } catch (e: IllegalStateException) {
                        if (e.message == INDEX_UNREADABLE) throw TagIndexUnreadableException(e)
                        throw e
                    } ?: return@call null
                    // The region is the extension's; we copy out and close ours in the same breath.
                    SharedBytes.readAndClose(value)
                } ?: return null
                val index = try {
                    TagCodec.decode(bytes)
                } catch (e: IllegalArgumentException) {
                    throw TagIndexUnreadableException(e)
                }
                Slog.d(TAG) {
                    "snapshot: ${index.tags.size} tags, ${index.assignments.size} assignments " +
                        "(${bytes.size} bytes) in ${System.currentTimeMillis() - t0} ms"
                }
                return index
            } finally {
                store.revoke()
            }
        }

        /**
         * Create-if-absent and attach [text] to one target, answering with the tag's canonical
         * display form (the casing it was first entered in) — **W3's door**, the lasso's silent
         * heading→tag.
         *
         * @throws TagIndexFullException a cap refused it; nothing was written.
         * @throws ExtensionCallException anything else, including text that is not a tag.
         */
        suspend fun assign(
            context: Context,
            ref: ProviderRef,
            text: String,
            targetKind: Int,
            targetId: String,
        ): String {
            val client = TagClient(context, ref)
            val store = client.openStore() ?: throw ExtensionCallException("store unavailable")
            val t0 = System.currentTimeMillis()
            try {
                val display = ExtensionBinder.call(
                    client.appContext, ref, ExtensionContract.ACTION_TAG_MANAGER, TAG,
                    asInterface = { ITagManager.Stub.asInterface(it) },
                    callTimeoutMs = CALL_TIMEOUT_MS,
                ) { iface ->
                    try {
                        iface.assign(store, text, targetKind, targetId)
                    } catch (e: IllegalStateException) {
                        when (e.message) {
                            ExtensionContract.TAG_INDEX_FULL -> throw TagIndexFullException(e)
                            INDEX_UNREADABLE -> throw TagIndexUnreadableException(e)
                            else -> throw e
                        }
                    }
                } ?: throw ExtensionCallException("assign returned nothing")
                Slog.d(TAG) { "assign: ${text.length} chars in ${System.currentTimeMillis() - t0} ms" }
                return display
            } finally {
                store.revoke()
            }
        }
    }
}
