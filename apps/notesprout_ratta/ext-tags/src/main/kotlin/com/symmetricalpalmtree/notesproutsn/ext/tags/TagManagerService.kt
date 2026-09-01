package com.symmetricalpalmtree.notesproutsn.ext.tags

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import android.os.SystemClock
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.ITagManager
import com.symmetricalpalmtree.notesproutsn.extension.LargeValue
import com.symmetricalpalmtree.notesproutsn.extension.SharedBytes
import com.symmetricalpalmtree.notesproutsn.extension.TagShowing
import java.util.concurrent.atomic.AtomicReference

/**
 * The TAG_MANAGER point (arc 21 / W1). Every method: `HostCallerCheck.enforce` first.
 *
 * **Two call patterns on one interface**, and the store is what tells them apart:
 *  - `begin` / `configureShowing` / `end` bracket a **showing** — the host holds one bind for the
 *    screen's whole life and the store is lent once, exactly as the scratch pad's is. `begin` parks
 *    the store and clears anything stale (a second `begin` while one is held replaces it — the host
 *    restarted); `configureShowing` parks what the screen is about; `end` clears both.
 *  - `snapshot` / `assign` are **call-shaped** — bind, call, unbind — so the store rides the call.
 *    They are what the host's search merge (W4) and the lasso's silent heading→tag (W3) use, and
 *    neither of them shows anything.
 *
 * Both patterns run on Binder threads, and both may edit the same single stored value, so **every
 * read-modify-write takes [TagSession.writes]** — the screen's edits included.
 *
 * `snapshot` answers over ashmem: a full index is megabytes and a `byte[]` that size cannot cross a
 * Binder. The region we create is parked per Binder thread and closed in [onTransact]'s `finally`,
 * **after** the reply (which holds a dup of the descriptor) is written — the `ExtensionStoreBinder`
 * recipe, and getting it wrong hands the host a closed fd.
 *
 * Only `SecurityException` / `IllegalArgumentException` / `IllegalStateException` are thrown —
 * anything else kills the transaction **silently** and the host reads an empty reply as success.
 * Logs are counts, lengths and durations: **a tag is the user's own words and is never logged.**
 */
class TagManagerService : Service() {

    private val binder = object : ITagManager.Stub() {

        /** The ashmem region a `snapshot` reply carries, closed after the reply is marshalled. */
        private val pending = ThreadLocal<SharedMemory>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                return super.onTransact(code, data, reply, flags)
            } finally {
                pending.get()?.close()
                pending.remove()
            }
        }

        override fun begin(store: IExtensionStore?) {
            enforce()
            requireNotNull(store) { "store is null" }
            synchronized(TagSession) {
                TagSession.clear()
                TagSession.store = store
            }
            Slog.d(TAG) { "begin" }
        }

        override fun configureShowing(showing: TagShowing?) {
            enforce()
            // The parcel is already through `TagShowing`'s constructor at unmarshal — that IS the
            // validation. What is left is the ordering: a showing with no store behind it would
            // launch a screen that cannot read anything.
            requireNotNull(showing) { "showing is null" }
            synchronized(TagSession) {
                if (TagSession.store == null) throw IllegalStateException(STORE_UNAVAILABLE)
                TagSession.showing = showing
            }
            // Never the label or a tag: user content. The mode and the kind are structure.
            Slog.d(TAG) { "configureShowing: mode=${showing.mode} kind=${showing.targetKind} pages=${showing.pageIds.size}" }
        }

        override fun end() {
            enforce()
            synchronized(TagSession) { TagSession.clear() }
            Slog.d(TAG) { "end" }
        }

        override fun snapshot(store: IExtensionStore?): LargeValue? {
            enforce()
            requireNotNull(store) { "store is null" }
            val t0 = SystemClock.elapsedRealtime()
            val blob = try {
                TagStore(store).readBlob()
            } catch (e: StoreUnavailable) {
                throw IllegalStateException(STORE_UNAVAILABLE)
            } ?: return null
            // The ashmem step is its own failure domain: `ErrnoException` is checked and outside
            // Binder's marshalable set, so it would kill the transaction silently.
            val value = try {
                SharedBytes.write(blob)
            } catch (e: Exception) {
                throw IllegalStateException("snapshot region: ${e.javaClass.simpleName}: ${e.message}")
            }
            pending.set(value.memory)
            Slog.d(TAG) { "snapshot: ${blob.size} bytes in ${SystemClock.elapsedRealtime() - t0} ms" }
            return value
        }

        override fun assign(store: IExtensionStore?, text: String?, targetKind: Int, targetId: String?): String {
            enforce()
            requireNotNull(store) { "store is null" }
            requireNotNull(text) { "text is null" }
            requireNotNull(targetId) { "targetId is null" }
            val t0 = SystemClock.elapsedRealtime()
            // The whole read-modify-write is [TagWrites]', because the screen writes the same single
            // value from IO. `assign` itself throws for text that is not a tag and for a cap — both
            // marshalable, and both leave the store untouched.
            val display = AtomicReference<String>()
            val outcome = TagWrites.apply(TagStore(store)) { index ->
                val result = index.assign(text, targetKind, targetId)
                display.set(result.display)
                if (result.index === index) null else result.index
            }
            when (outcome) {
                // "Nothing changed" is the tag already being on the target — an honest success, and
                // the caller still gets the canonical spelling for its toast.
                is TagWrites.Outcome.Written, is TagWrites.Outcome.Unchanged -> Unit
                is TagWrites.Outcome.Failed -> throw refusal(outcome.reason)
            }
            Slog.d(TAG) { "assign: ${text.length} chars in ${SystemClock.elapsedRealtime() - t0} ms" }
            return display.get() ?: throw IllegalStateException(STORE_UNAVAILABLE)
        }

        /** A [TagWrites.Reason] as the one marshalable exception the host compares verbatim. */
        private fun refusal(reason: TagWrites.Reason): RuntimeException = when (reason) {
            TagWrites.Reason.NOT_A_TAG -> IllegalArgumentException("not a tag")
            TagWrites.Reason.INDEX_FULL -> IllegalStateException(ExtensionContract.TAG_INDEX_FULL)
            TagWrites.Reason.INDEX_UNREADABLE -> IllegalStateException(INDEX_UNREADABLE)
            TagWrites.Reason.STORE_UNAVAILABLE, TagWrites.Reason.SAVE_FAILED ->
                IllegalStateException(STORE_UNAVAILABLE)
        }

        private fun enforce() = HostCallerCheck.enforce(this@TagManagerService, BuildConfig.HOST_PACKAGE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "TagManagerService"

        /** The store binder is gone (`begin` never ran, or the host revoked it). One of the three
         *  exceptions that survive Binder marshalling. */
        const val STORE_UNAVAILABLE = "store unavailable"

        /** There is a stored index and it cannot be read — the host says so and changes nothing.
         *  Deliberately distinct from an absent index, which is simply empty. */
        const val INDEX_UNREADABLE = "tag index unreadable"

    }
}
