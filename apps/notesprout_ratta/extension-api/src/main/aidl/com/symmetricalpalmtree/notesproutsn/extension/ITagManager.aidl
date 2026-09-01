package com.symmetricalpalmtree.notesproutsn.extension;

// A .aidl that takes a parcelable needs an explicit import for it.
import com.symmetricalpalmtree.notesproutsn.extension.AssignmentRecord;
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore;
import com.symmetricalpalmtree.notesproutsn.extension.TagRecord;
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
 *  - a CALL (tags / assignmentsOf / assign) is bind-per-call, the recognizer shape: the store rides
 *    the call that needs it, because the operation IS the call.
 *
 * ARC 22 / X3 REPLACED `snapshot` (API_VERSION 6). The index is `tag` / `assignment` rows in the
 * store now, not one TagCodec blob, so the search merge is TWO paged reads instead of one whole-index
 * snapshot: tags() ranks, then assignmentsOf(the ids that matched) fetches only the rows the ranking
 * needs. Neither reply rides ashmem -- they are ordinary parcels, which is why both are paged.
 *
 * Nothing rides the screen's Intent -- the showing crosses as a TagShowing on this bind. Tag text
 * and target labels are the user's own words: never logged on either side (counts, lengths and
 * durations only). Every method: HostCallerCheck.enforce first, and only SecurityException /
 * IllegalArgumentException / IllegalStateException leave. Timeouts are the host's.
 */
interface ITagManager {
    /** The host is about to show the screen: hold [store] for the screen's life (revoked at end()). */
    void begin(IExtensionStore store);

    /** After begin(), before the launch: what this showing is about. Replaces any parked showing. */
    void configureShowing(in TagShowing showing);

    /** The screen is over (result / cancel / host stop): drop the store and the parked showing. */
    void end();

    /**
     * One page of the library's tags -- at most ExtensionContract.TAGS_PAGE records, in the browse
     * order (identityKey, then display, which is stable and therefore safe to page through),
     * starting at [offset]. A page shorter than TAGS_PAGE is the last one.
     *
     * IllegalArgumentException for a negative offset; IllegalStateException("store unavailable")
     * when the store cannot be reached.
     */
    List<TagRecord> tags(IExtensionStore store, int offset);

    /**
     * One page of the assignments of the named tags -- at most ExtensionContract.ASSIGNMENTS_PAGE
     * rows ordered (tagId, notebookId, pageId) from [offset], for the tags in [tagIds]. An empty
     * list answers empty and touches no store. A page shorter than ASSIGNMENTS_PAGE is the last one.
     *
     * IllegalArgumentException when [tagIds] holds more than ExtensionContract.ASSIGNMENT_QUERY_TAGS
     * ids (the host chunks), when any of them is not a canonical UUID, or for a negative offset.
     */
    List<AssignmentRecord> assignmentsOf(IExtensionStore store, in List<String> tagIds, int offset);

    /** Normalize [text], create the tag if the library has never seen it, attach it to the target,
     *  and answer with the tag's CANONICAL display text (the casing first entered), which is what
     *  the host's toast says. Idempotent: a tag already on the target writes nothing and still
     *  answers. Throws IllegalArgumentException for text that is not a tag or an id that is not a
     *  canonical UUID, IllegalStateException(TAG_INDEX_FULL) when a cap refuses it (nothing written).
     *
     *  The whole operation is two small reads and one two-statement transaction: the tag row is
     *  INSERT OR IGNOREd under a COUNT cap and the assignment resolves its tag id BY IDENTITY inside
     *  its own statement, so a concurrent creator of the same tag cannot leave a dangling reference.
     *  The transaction is the lock -- there is no read-modify-write of a blob to serialize any more,
     *  and none between the two writers (the screen on IO, this call on a Binder thread) either.
     *
     *  The target is a PAIR (arc 21 / W4): [notebookId] always, [pageId] only when the tag belongs
     *  to one page of it. A page is never named on its own -- the library has no other way to find
     *  out which notebook a tagged page is in. */
    String assign(IExtensionStore store, String text, String notebookId, String pageId);
}
