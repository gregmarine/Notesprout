package com.symmetricalpalmtree.notesprout.extension;

import com.symmetricalpalmtree.notesprout.extension.IExtensionStore;
import com.symmetricalpalmtree.notesprout.extension.ILinkCatalog;
import com.symmetricalpalmtree.notesprout.extension.LinkChoice;
import com.symmetricalpalmtree.notesprout.extension.LinkDestination;
import com.symmetricalpalmtree.notesprout.extension.TrailEntry;

/**
 * The LINK_PROVIDER point (arc 7 / L0). The core owns link structure — the `.soil` rows, wrap /
 * unwrap, the composite render, gestures, navigation, undo — and this extension owns link MEANING:
 * the payload in a link row's `text` column is opaque to the core, written by the extension's picker
 * screen (ACTION_LINK_PICKER_SCREEN — the arc-6 tier-2 pattern) and interpreted only here.
 *
 * Two usage modes on one service. The PICK SHOWING (create + edit) is a held bind bracketing the
 * picker screen: beginPick → the screen (launched by the host for a result; data never rides the
 * Intent beyond EXTRA_LINK_EDIT) → takeResult → endPick → unbind, with [store] and [catalog] alive
 * only inside it. The ONE-SHOT calls (resolve / chromeOf / trail) are bind-per-operation.
 * Every method: HostCallerCheck first. Timeouts are the host's (≤ 2 s each; bind ≤ 3 s).
 */
interface ILinkProvider {
    /** The host is about to show the picker: hold [store] and [catalog] for the showing (both die at
     *  endPick / unbind). [currentNotebookId] = the notebook the link lives in (the picker's "this
     *  notebook" mode); [editPayload] = the payload to pre-populate for an Edit, or null to create. */
    void beginPick(IExtensionStore store, ILinkCatalog catalog,
                   String currentNotebookId, String editPayload);

    /** After the picker's result (any code): what was chosen, or null = cancelled. */
    LinkChoice takeResult();

    /** The showing is over (result / cancel / host stop): drop the store, catalog and result. */
    void endPick();

    /** Resolve an opaque [payload] into a typed destination, or null when the payload is unusable
     *  (malformed, unknown version) — the core then shows its honest dead-link dialog. Pure. */
    LinkDestination resolve(String payload);

    /** The chrome flag (LINK_CHROME_*) of each of [payloads] — same order, same length. Pure. */
    int[] chromeOf(in List<String> payloads);

    /** Push [entry] onto the back-trail in [store] (cap MAX_TRAIL_ENTRIES — the oldest is dropped). */
    void pushTrail(IExtensionStore store, in TrailEntry entry);

    /** Pop the newest trail entry from [store], or null when the trail is empty. */
    TrailEntry popTrail(IExtensionStore store);

    /** Clear the trail in [store] (a fresh notebook open — no EXTRA_VIA_LINK). */
    void clearTrail(IExtensionStore store);
}
