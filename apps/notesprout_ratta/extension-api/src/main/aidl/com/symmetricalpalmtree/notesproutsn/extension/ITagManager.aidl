package com.symmetricalpalmtree.notesproutsn.extension;

// A .aidl that takes a parcelable needs an explicit import for it.
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore;
import com.symmetricalpalmtree.notesproutsn.extension.LargeValue;
import com.symmetricalpalmtree.notesproutsn.extension.TagShowing;

/**
 * The TAG_MANAGER point (arc 21 / W1) -- SN's SIXTH capability point and its THIRD screen-owning
 * one. The extension owns the tag screen (action ACTION_TAG_MANAGER_SCREEN) and the tag index, which
 * lives in the host's extension store; the host owns every entry point, the recognizer and the
 * library-search merge.
 *
 * ONE interface, TWO call patterns, and the difference is the store:
 *
 *  - a SHOWING (begin -> configureShowing -> launch -> result -> end) is a HELD bind, the scratch-pad
 *    bracket: the store is lent once, for the screen's whole life, and revoked with the unbind;
 *  - a CALL (snapshot / assign) is bind-per-call, the recognizer shape: the store rides the call
 *    that needs it, because the operation IS the call.
 *
 * Nothing rides the screen's Intent -- the showing crosses as a TagShowing on this bind. Tag text
 * and target labels are the user's own words: never logged on either side (counts, lengths and
 * durations only). Every method: HostCallerCheck.enforce first. Timeouts are the host's.
 */
interface ITagManager {
    /** The host is about to show the screen: hold [store] for the screen's life (revoked at end()). */
    void begin(IExtensionStore store);

    /** After begin(), before the launch: what this showing is about. Replaces any parked showing. */
    void configureShowing(in TagShowing showing);

    /** The screen is over (result / cancel / host stop): drop the store and the parked showing. */
    void end();

    /** The whole tag index as its TagCodec blob, over ashmem (it can be megabytes -- a byte[] that
     *  size cannot cross a Binder). Null when the store holds no index yet. Throws
     *  IllegalStateException if the index is stored but unreadable -- which is NOT the same as
     *  empty, and the host must not treat it as such. */
    LargeValue snapshot(IExtensionStore store);

    /** Normalize [text], create the tag if the library has never seen it, attach it to the target,
     *  and write the index. Returns the tag's CANONICAL display text (the casing first entered),
     *  which is what the host's toast says. Throws IllegalArgumentException for text that is not a
     *  tag or an id that is not a canonical UUID, IllegalStateException(TAG_INDEX_FULL) when a cap
     *  refuses it (nothing written).
     *
     *  The target is a PAIR (arc 21 / W4): [notebookId] always, [pageId] only when the tag belongs
     *  to one page of it. A page is never named on its own -- the library has no other way to find
     *  out which notebook a tagged page is in. */
    String assign(IExtensionStore store, String text, String notebookId, String pageId);
}
