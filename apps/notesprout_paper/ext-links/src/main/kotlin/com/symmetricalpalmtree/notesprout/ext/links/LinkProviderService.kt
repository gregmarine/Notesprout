package com.symmetricalpalmtree.notesprout.ext.links

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.ILinkCatalog
import com.symmetricalpalmtree.notesprout.extension.ILinkProvider
import com.symmetricalpalmtree.notesprout.extension.LinkChoice
import com.symmetricalpalmtree.notesprout.extension.LinkDestination
import com.symmetricalpalmtree.notesprout.extension.TrailEntry

/**
 * The LINK_PROVIDER point (arc 7 / L0). Two usage modes on one service, both starting with
 * `HostCallerCheck.enforce`:
 *
 * - **The pick showing** — `beginPick` holds the store + catalog binders in [PickSession] for the
 *   picker screen's life (a second `beginPick` while one is held replaces it: the host restarted),
 *   `takeResult` drains what the screen parked, `endPick` drops everything.
 * - **The one-shot calls** — `resolve` and `chromeOf` are pure over [LinkPayload]; the trail methods
 *   run over [TrailStore] with the store the call carried (never the held one).
 *
 * A store failure must not cross the Binder as an exception Binder cannot marshal — that fails the
 * transaction *silently* (the arc-2 lesson): anything but the marshalable ones is rethrown as an
 * `IllegalStateException`. Logs are counts and durations only — never a payload, id or label.
 */
class LinkProviderService : Service() {

    private val binder = object : ILinkProvider.Stub() {

        override fun beginPick(
            store: IExtensionStore?,
            catalog: ILinkCatalog?,
            currentNotebookId: String?,
            editPayload: String?,
        ) {
            enforce()
            requireNotNull(store) { "store is null" }
            requireNotNull(catalog) { "catalog is null" }
            PickSession.clear()
            PickSession.store = store
            PickSession.catalog = catalog
            PickSession.currentNotebookId = currentNotebookId
            PickSession.editPayload = editPayload
            Slog.d(TAG) { "beginPick: edit=${editPayload != null}" }
        }

        override fun takeResult(): LinkChoice? {
            enforce()
            val result = PickSession.result
            PickSession.result = null
            Slog.d(TAG) { "takeResult: ${if (result != null) "picked" else "cancelled"}" }
            return result
        }

        override fun endPick() {
            enforce()
            PickSession.clear()
            Slog.d(TAG) { "endPick" }
        }

        override fun resolve(payload: String?): LinkDestination? {
            enforce()
            val decoded = payload?.let { LinkPayload.decode(it) } ?: return null
            // decode already guarantees the kind / id combination, so requireValid cannot fire —
            // belt and braces: a resolve must never crash the binder.
            return runCatching { LinkDestination(decoded.kind, decoded.notebookId, decoded.pageId) }.getOrNull()
        }

        override fun chromeOf(payloads: MutableList<String>?): IntArray {
            enforce()
            requireNotNull(payloads) { "payloads is null" }
            // A malformed or future payload has no chrome — the core draws the content bare.
            return IntArray(payloads.size) { i ->
                payloads[i]?.let { LinkPayload.decode(it)?.chrome } ?: ExtensionContract.LINK_CHROME_NONE
            }
        }

        override fun pushTrail(store: IExtensionStore?, entry: TrailEntry?) {
            enforce()
            requireNotNull(store) { "store is null" }
            requireNotNull(entry) { "entry is null" }
            trail("push") { TrailStore.push(store, entry) }
        }

        override fun popTrail(store: IExtensionStore?): TrailEntry? {
            enforce()
            requireNotNull(store) { "store is null" }
            return trail("pop") { TrailStore.pop(store) }
        }

        override fun clearTrail(store: IExtensionStore?) {
            enforce()
            requireNotNull(store) { "store is null" }
            trail("clear") { TrailStore.clear(store) }
        }

        /**
         * Run a trail operation, letting the Binder-marshalable exceptions through as they are and
         * turning anything else (`DeadObjectException`, a plain `RemoteException`, an IO failure…)
         * into an `IllegalStateException` — an unmarshalable exception fails the transaction silently.
         */
        private fun <T> trail(what: String, block: () -> T): T = try {
            block()
        } catch (e: SecurityException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "trail $what failed: ${e.javaClass.simpleName}" }
            throw IllegalStateException("trail: ${e.javaClass.simpleName}: ${e.message}")
        }

        private fun enforce() = HostCallerCheck.enforce(this@LinkProviderService, BuildConfig.HOST_PACKAGE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "LinkProviderService"
    }
}
