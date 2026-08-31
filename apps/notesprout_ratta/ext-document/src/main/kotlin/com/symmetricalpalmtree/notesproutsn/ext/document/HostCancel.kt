package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Stop reading" — the one hand on the Cancel button of a reading popup (arc 19 / M7).
 *
 * A notebook merge can walk every page of a notebook and recognize each one, which is the only wait
 * in this editor long enough to be worth taking back. `IDocumentHost.cancelRequest` is how: the
 * host's loop stops between pages, and the in-flight call answers null (`requestScope`) or throws
 * [com.symmetricalpalmtree.notesproutsn.extension.DocumentContract.MERGE_CANCELLED]
 * (`requestMerge`). Nothing was written either way.
 *
 * **It is a blocking Binder call and never runs on Main** — the request it is cancelling is itself
 * blocking a Binder thread, and a cancel posted to a wedged Main would be a Cancel button that does
 * nothing. Fire-and-forget on IO: a cancel with nothing running is a harmless no-op host-side, so
 * there is no answer worth waiting for.
 *
 * A failure here is not something the reader can act on — the merge either comes back cancelled or
 * it does not — so it is logged (class name only, never a message from across the seam) and
 * dropped.
 */
internal object HostCancel {

    fun fire(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                EditorSession.host?.cancelRequest()
                Slog.d(TAG) { "cancel sent" }
            } catch (e: Exception) {
                Slog.d(TAG) { "cancel failed: ${e.javaClass.simpleName}" }
            }
        }
    }

    private const val TAG = "DocumentEditor"
}
