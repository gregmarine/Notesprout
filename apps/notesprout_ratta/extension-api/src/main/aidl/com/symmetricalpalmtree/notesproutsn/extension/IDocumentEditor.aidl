package com.symmetricalpalmtree.notesproutsn.extension;

import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore;
import com.symmetricalpalmtree.notesproutsn.extension.IDocumentHost;

/**
 * The DOCUMENT_EDITOR point (arc 19 / M3) -- SN's FIFTH capability point and its second
 * screen-owning one. The extension owns the full-screen Markdown editor Activity (action
 * ACTION_DOCUMENT_EDITOR_SCREEN) the host launches for a result; the host HOLDS one bind on
 * this service for the screen's whole showing (begin -> launch -> result -> end -> unbind).
 *
 * The seam's new piece rides begin(): [host] is a HOST-side binder (the first on any SN
 * extension seam) through which every byte of document text crosses, in both directions,
 * chunked (TextChunks) -- the editor pulls its text through it and pushes every save back
 * through it. Nothing rides the screen's Intent at all. Every method here:
 * HostCallerCheck.enforce first. Timeouts are the host's.
 */
interface IDocumentEditor {
    /** The host is about to show the editor: hold [store] and [host] for the showing (both are
     *  dropped at end(); the store binder is revoked by the host alongside the unbind). */
    void begin(IExtensionStore store, IDocumentHost host);

    /** The showing is over (result / cancel / host stop): drop the store and the host binder. */
    void end();
}
