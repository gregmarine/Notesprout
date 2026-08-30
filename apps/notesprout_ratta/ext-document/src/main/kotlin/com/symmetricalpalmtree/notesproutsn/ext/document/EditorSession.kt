package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.IDocumentHost
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore

/**
 * Process-wide state shared by [DocumentEditorService] (the host's held bind) and
 * [DocumentEditorActivity] (the screen) — they live in the same process. It holds **only what the
 * host lent for this showing**: the extension store binder and, new at this point, the
 * [IDocumentHost] callback binder every byte of document text crosses.
 *
 * `end()` clears both. **Nothing here is ever written to disk by the extension itself** — the
 * document lives in the host's `.soil` and the editor's small per-device state in the host's
 * extension store (M5). Both binders are revoked by the host alongside the unbind, so a reference
 * kept past `end()` would not be a leak of anything usable — it would just be a binder that throws
 * `SecurityException` on every call. Clearing them is still the honest thing: it is what makes
 * "there is no showing" a state the screen can test rather than infer from a refusal.
 *
 * One monitor for the pair. `begin` and `end` arrive on Binder threads and the screen reads from
 * its own; a showing that swapped mid-read must hand out one pair or the other, never a half of
 * each (`ScratchSession`'s rule in `:ext-scratchpad`, applied to two binders instead of one).
 */
object EditorSession {

    private var storeBinder: IExtensionStore? = null
    private var hostBinder: IDocumentHost? = null

    /** The host is showing the editor: hold both binders for its life. A second [begin] while one
     *  is held replaces it — the host restarted. */
    @Synchronized
    fun begin(store: IExtensionStore, host: IDocumentHost) {
        storeBinder = store
        hostBinder = host
    }

    /** The showing is over: drop both. Idempotent. */
    @Synchronized
    fun end() {
        storeBinder = null
        hostBinder = null
    }

    /** The host's callback binder for this showing, or null when there is no showing. */
    @get:Synchronized
    val host: IDocumentHost?
        get() = hostBinder

    /** The host's extension store for this showing, or null when there is no showing. Unread until
     *  M5 gives the editor state worth keeping (caret memory, text size, the proofread toggle). */
    @get:Synchronized
    val store: IExtensionStore?
        get() = storeBinder
}
