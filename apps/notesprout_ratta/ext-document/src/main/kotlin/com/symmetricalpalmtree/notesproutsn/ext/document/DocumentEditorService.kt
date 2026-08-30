package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IDocumentEditor
import com.symmetricalpalmtree.notesproutsn.extension.IDocumentHost
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore

/**
 * The DOCUMENT_EDITOR point (arc 19 / M3) — the host's **held** bind for one showing of the editor
 * screen, and the second screen-owning point after the scratch pad. Every method:
 * `HostCallerCheck.enforce` first, before anything is read out of the arguments.
 *
 * `begin` parks both lent binders in [EditorSession] for the screen's life; `end` clears them. That
 * is the whole service — there is no model to warm, no engine to register and no store read worth
 * doing here, so `onCreate` is the default one. The editor's own work all happens on the screen,
 * against the callback binder this call carried across.
 *
 * **Only marshalable exceptions leave.** `SecurityException` (a caller that is not the host),
 * `IllegalArgumentException` (a null binder) and `IllegalStateException` are the three Binder
 * carries intact; anything else kills the transaction *silently* and the host reads the empty reply
 * as success. Nothing here does work that could raise anything else — which is the point of keeping
 * it this small.
 *
 * Logs: counts and durations. **Never a character of the document** — it does not pass through this
 * service at all, and when it passes through the screen it is still never logged.
 */
class DocumentEditorService : Service() {

    private val binder = object : IDocumentEditor.Stub() {

        override fun begin(store: IExtensionStore?, host: IDocumentHost?) {
            enforce()
            requireNotNull(store) { "store is null" }
            requireNotNull(host) { "host is null" }
            EditorSession.begin(store, host)
            Slog.d(TAG) { "begin" }
        }

        override fun end() {
            enforce()
            EditorSession.end()
            Slog.d(TAG) { "end" }
        }

        private fun enforce() = HostCallerCheck.enforce(this@DocumentEditorService, BuildConfig.HOST_PACKAGE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "DocumentEditorService"
    }
}
