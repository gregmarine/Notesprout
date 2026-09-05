package com.symmetricalpalmtree.notesproutsn.extension;

// A .aidl that takes a parcelable needs an explicit import for it.
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry;
import com.symmetricalpalmtree.notesproutsn.extension.CloudStatus;
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore;

/**
 * The CLOUD_STORAGE point (arc 25 / V1) -- SN's EIGHTH capability point, and the first that is
 * generic over a provider: folders, files and bytes, never a provider's own terms. The host consumes
 * it for three of its own features (export destination, backup destination, import source); no
 * other extension knows it exists.
 *
 * STORE-TAKING, BIND-PER-CALL -- the tag manager's second call shape. The store rides every call:
 * minted per bind by the host, uid-bound, revoked with the unbind. There is no held bind and no
 * session: every operation is one Binder call under a host timeout sized by on-device measurement
 * (CloudTimeouts), because a Binder call cannot be cancelled. The provider persists its account
 * (token, label, cached folder ids) ONLY through the store it is handed -- an extension writes
 * nothing to disk itself, ever.
 *
 * A path is folder NAMES under the provider's own root (CloudContract.requireValidPath: at most
 * MAX_PATH_DEPTH segments, each a legal name); an empty path is the root itself. An entry id is the
 * provider's opaque id, passed back verbatim. No secret, no device path, no URL crosses in either
 * direction. The account label is user content and is never logged on either side.
 *
 * Every method: HostCallerCheck.enforce first, INSIDE the try whose finally closes any fd. Only
 * SecurityException / IllegalArgumentException / IllegalStateException leave a stub. The host
 * compares two IllegalStateException messages VERBATIM -- CloudContract.NOT_CONNECTED (offer
 * Connect) and CloudContract.NETWORK (nothing changed, try again); any other message is "the
 * provider didn't answer".
 */
interface ICloudStorage {
    /** What the store says: configured? connected? the account's label. NEVER touches the network. */
    CloudStatus status(IExtensionStore store);

    /** Revoke the token with the provider (best effort, bounded) and forget it from the store.
     *  Idempotent; not-connected is not an error here. */
    void disconnect(IExtensionStore store);

    /**
     * The folders and files directly under [path] (the root when empty), folders first then files,
     * each group by name -- at most CloudContract.MAX_LIST_ENTRIES (a longer listing is truncated,
     * never failed). A path whose folder does not exist answers EMPTY: to the host a missing folder
     * and an empty one look the same, and `ensureFolder` is how one comes to exist.
     * IllegalStateException(NOT_CONNECTED) / (NETWORK).
     */
    CloudEntry[] list(IExtensionStore store, in String[] path);

    /** Find or create every segment of [path] in turn and answer the last one. An empty path
     *  answers the root. Same-named siblings are a provider fact (Drive allows them): the FIRST by
     *  the provider's order is the one, and nothing is ever created beside an existing name. */
    CloudEntry ensureFolder(IExtensionStore store, in String[] path);

    /**
     * Write [source]'s bytes as the file [name] under [path], creating the folders on the way.
     * REPLACE-BY-NAME: a file of that name already there is updated in place (its id kept), never
     * duplicated -- og's find-then-update rule. [expectedBytes] is what the host wrote to [source];
     * the provider streams exactly that many and refuses a short or long read as
     * IllegalStateException. Answers the entry AS THE PROVIDER REPORTS IT after the write -- the
     * host corroborates sizeBytes against expectedBytes and treats disagreement as "check the
     * file", never as delete. The stub closes [source] in its finally.
     */
    CloudEntry upload(IExtensionStore store, in String[] path, String name, String mime,
                      in ParcelFileDescriptor source, long expectedBytes);

    /** Stream the file [entryId] into [destination] (truncated first), fsync, and answer the bytes
     *  written. The stub closes [destination] in its finally. A folder id is
     *  IllegalArgumentException; an id the provider no longer knows is IllegalStateException. */
    long download(IExtensionStore store, String entryId, in ParcelFileDescriptor destination);

    /** Delete the file or (empty or not) folder [entryId]. Idempotent on an id already gone. */
    void delete(IExtensionStore store, String entryId);
}
