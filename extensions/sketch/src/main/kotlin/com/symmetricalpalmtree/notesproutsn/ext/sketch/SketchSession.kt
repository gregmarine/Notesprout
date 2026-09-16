package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.notesproutsn.extension.ISketchHost

/**
 * Process-wide state shared by [SketchService] (the host's held bind) and [SketchActivity] (the
 * screen) — they live in the same process (arc 43 / K5). It holds **only what the host lent for
 * this showing** plus the two small things that let a drawing survive the ways a showing can come
 * apart.
 *
 * **There is no store**, and that is the point of this point. Every other screen-owning extension is
 * lent an `IExtensionStore` at `begin`, because every other one has something of its own to
 * remember — the pad's pages, the calendar's bookmark, the reader's position, the editor's caret. A
 * sketch has none: the pixels live in the host's `.soil`, the bookmark is the notebook's own page,
 * the undo history is this sitting and dies with it, and there is one tool with no settings.
 *
 * What it does hold:
 *
 * - [host] — the `ISketchHost` callback binder every byte of PNG crosses, in both directions. The
 *   host revokes it alongside the unbind, so a reference kept past `end()` would not be a leak of
 *   anything usable — it would be a binder that throws `SecurityException` on every call. Clearing
 *   it is still the honest thing: it makes "there is no showing" a state the screen can test rather
 *   than infer from a refusal.
 * - [pending] — a page whose push failed, parked with the key it was for ([PendingPngPark]). It
 *   survives the screen: a recreated or dead Activity does not take unwritten pixels with it.
 * - [flushHook] — how the service, on a Binder thread at `end()`, gets unsaved pixels out of a
 *   screen that is still standing, through that screen's own push lock.
 * - [beginListener] — how a live screen hears that the host restarted and re-offers what it holds.
 *
 * One monitor for the binder: `begin` and `end` arrive on Binder threads and the screen reads from
 * its own, so a showing that swapped mid-read must hand out one binder or the other, never a half of
 * each (`ScratchSession`'s rule). [pending] carries its own monitor and the two hooks are `@Volatile`
 * single writes.
 *
 * **No pixels are logged from here, ever** — nothing in this file even formats a message.
 */
object SketchSession {

    private var hostBinder: ISketchHost? = null

    /** Pixels a push could not deliver, waiting for a host that can take them. */
    val pending: PendingPngPark = PendingPngPark()

    /** The screen's ear for a host reconnect. Registered in `onCreate`, cleared in `onDestroy`;
     *  null means no screen is alive to ask. Invoked on a **Binder thread**. */
    @Volatile
    var beginListener: BeginListener? = null

    /** The service's way to get an unsaved page out of a live screen at teardown. Invoked on a
     *  **Binder thread**, where blocking is allowed — the hook does its own main-thread hop. */
    @Volatile
    var flushHook: FlushHook? = null

    /** The host is showing the sketch screen: hold [host] for its life. A second [begin] while one
     *  is held replaces it — the host restarted. */
    @Synchronized
    fun begin(host: ISketchHost) {
        hostBinder = host
    }

    /** The showing is over: drop the binder. Idempotent. The hooks belong to the screen and are
     *  **not** cleared here — the screen registers and unregisters them with its own lifetime. */
    @Synchronized
    fun clear() {
        hostBinder = null
    }

    /** The host's callback binder for this showing, or null when there is no showing. */
    @get:Synchronized
    val host: ISketchHost?
        get() = hostBinder

    /** "The host is back." A live screen answers by re-offering its park and anything still dirty —
     *  a page key is the page's own id, so it is still the same page after a host restart. */
    fun interface BeginListener {
        fun onHostBegan()
    }

    /** The live screen's half of the teardown flush — two methods, so the service's own re-push can
     *  ride the saver's push lock instead of interleaving with a save already in the air on the
     *  host's one accumulator (the document editor's M11 lesson). */
    interface FlushHook {
        /** Push anything the screen holds unsaved, blocking the calling Binder thread. Never
         *  throws: a failure has already parked its pixels. */
        fun flushBlocking()

        /** Push exactly these bytes for this page, through the saver's push lock, blocking the
         *  calling Binder thread; throws on failure (the caller decides what to do about it). */
        fun pushBlocking(pageKey: String, png: ByteArray)
    }
}
