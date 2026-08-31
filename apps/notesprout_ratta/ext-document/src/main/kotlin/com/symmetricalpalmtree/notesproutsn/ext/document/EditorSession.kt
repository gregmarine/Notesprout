package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.IDocumentHost
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore

/**
 * Process-wide state shared by [DocumentEditorService] (the host's held bind) and
 * [DocumentEditorActivity] (the screen) — they live in the same process. It holds **only what the
 * host lent for this showing** (the extension store binder and the [IDocumentHost] callback binder
 * every byte of document text crosses) plus, since M4, the three small things that let a save
 * survive the two ways a showing can come apart:
 *
 * - [pending] — a snapshot whose push failed, parked with the key it was for ([PendingPark]).
 * - [beginListener] — how a live screen hears that the host reconnected.
 * - [flushHook] — how the service, on a Binder thread at `end()`, gets an unsaved buffer out of a
 *   screen that is still standing.
 *
 * **Nothing here is ever written to disk by the extension itself** — the document lives in the
 * host's `.soil` and the editor's small per-device state in the host's extension store (M5). Both
 * binders are revoked by the host alongside the unbind, so a reference kept past `end()` would not
 * be a leak of anything usable — it would just be a binder that throws `SecurityException` on every
 * call. Clearing them is still the honest thing: it is what makes "there is no showing" a state the
 * screen can test rather than infer from a refusal.
 *
 * One monitor for the binders. `begin` and `end` arrive on Binder threads and the screen reads from
 * its own; a showing that swapped mid-read must hand out one pair or the other, never a half of
 * each (`ScratchSession`'s rule in `:ext-scratchpad`, applied to two binders instead of one).
 * [pending] carries its own monitor, and the two hooks are `@Volatile` single writes.
 *
 * **No document text is logged from here, ever** — nothing in this file even formats a message.
 */
object EditorSession {

    private var storeBinder: IExtensionStore? = null
    private var hostBinder: IDocumentHost? = null

    /** Text a push could not deliver, waiting for a host that can take it. Survives the screen — a
     *  recreated (or dead) Activity does not take unwritten words with it. */
    val pending: PendingPark = PendingPark()

    /** The screen's ear for a host reconnect. Registered in `onCreate`, cleared in `onDestroy`;
     *  null means no screen is alive to ask. Invoked on a **Binder thread**. */
    @Volatile
    var beginListener: BeginListener? = null

    /** The service's way to get an unsaved buffer out of a live screen at teardown. Invoked on a
     *  **Binder thread**, where blocking is allowed — the hook does its own main-thread hop. */
    @Volatile
    var flushHook: FlushHook? = null

    /** The host is showing the editor: hold both binders for its life. A second [begin] while one
     *  is held replaces it — the host restarted. */
    @Synchronized
    fun begin(store: IExtensionStore, host: IDocumentHost) {
        storeBinder = store
        hostBinder = host
    }

    /** The showing is over: drop both. Idempotent. The hooks belong to the screen and are **not**
     *  cleared here — the screen registers and unregisters them with its own lifetime. */
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

    /** "The host is back." A live screen answers by asking the host what it is showing now and
     *  flushing only if that is still its own target ([AutosaveGovernor.shouldFlushOnReconnect]). */
    fun interface BeginListener {
        fun onHostBegan()
    }

    /** The live screen's unsaved snapshot as `pageKey to text`, or null when it has nothing owed. */
    fun interface FlushHook {
        fun unsavedSnapshot(): Pair<String, String>?
    }
}
