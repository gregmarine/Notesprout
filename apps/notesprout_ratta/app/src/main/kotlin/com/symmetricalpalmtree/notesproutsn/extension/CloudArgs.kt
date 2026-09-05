package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The host's own checks on everything it is about to send across the cloud seam, and on everything
 * that comes back (arc 25 / V2) — **run before the bind, never after it**.
 *
 * The provider's stub validates too (the seam's rule: both ends check, because either end can be
 * the buggy one). This runs first anyway, for one reason: a refusal must never cost a bind. Binding
 * a service starts a process, and a path with nine segments is a host bug that should be answered
 * in microseconds by the host, not by waking another app to say no.
 *
 * Everything here is pure — [CloudContract]'s predicates and nothing else — so the whole table is
 * JVM-testable. A failure is an [ExtensionCallException] rather than an `IllegalArgumentException`
 * because a caller cannot always know: a file name comes from the user's own naming, and "that name
 * cannot go to the cloud" is a thing to *say*, not a thing to crash on.
 *
 * The reply side is the same idea pointed the other way. A parcelable's constructor already
 * `require`s its own fields (unmarshal is validation, the family rule since E1), so what is left
 * here is the shape of a *reply as a whole*: a listing longer than the contract allows, or an
 * `upload` that answered with a folder.
 */
object CloudArgs {

    /** The path check, host-side: at most [CloudContract.MAX_PATH_DEPTH] segments, each a legal
     *  name. An empty path is the provider's root and is legal everywhere a path is taken. */
    fun requirePath(path: Array<String>) {
        if (path.size > CloudContract.MAX_PATH_DEPTH) {
            throw ExtensionCallException("path has ${path.size} segments — at most ${CloudContract.MAX_PATH_DEPTH}")
        }
        for ((i, segment) in path.withIndex()) {
            if (!CloudContract.isName(segment)) throw ExtensionCallException("path segment $i is not a name")
        }
    }

    /** One folder or file name ([CloudContract.isName]). */
    fun requireName(name: String) {
        if (!CloudContract.isName(name)) throw ExtensionCallException("not a legal name (${name.length} chars)")
    }

    /** A MIME type ([CloudContract.isMime]). */
    fun requireMime(mime: String) {
        if (!CloudContract.isMime(mime)) throw ExtensionCallException("not a MIME type (${mime.length} chars)")
    }

    /** An entry id ([CloudContract.isEntryId]) — opaque, minted by the provider, passed back verbatim. */
    fun requireEntryId(id: String) {
        if (!CloudContract.isEntryId(id)) throw ExtensionCallException("not an entry id (${id.length} chars)")
    }

    /**
     * The byte count the host promises to write into the upload's fd. Negative is a bug; zero is
     * allowed, because an empty file is a file (an export of an empty page is not an error, and
     * refusing it here would make the host lie about why).
     */
    fun requireExpectedBytes(bytes: Long) {
        if (bytes < 0) throw ExtensionCallException("expectedBytes is negative ($bytes)")
    }

    /**
     * A `list` reply: present, and no longer than [CloudContract.MAX_LIST_ENTRIES]. The contract
     * says a provider **truncates** a longer listing rather than failing it, so a reply over the cap
     * is a provider that is not keeping the contract and the host does not draw it.
     */
    fun checkList(entries: Array<CloudEntry>?): List<CloudEntry> {
        if (entries == null) throw ExtensionCallException("list returned nothing")
        if (entries.size > CloudContract.MAX_LIST_ENTRIES) {
            throw ExtensionCallException("list returned ${entries.size} entries — at most ${CloudContract.MAX_LIST_ENTRIES}")
        }
        return entries.asList()
    }

    /** An `ensureFolder` reply: present, and a folder. */
    fun checkFolder(entry: CloudEntry?): CloudEntry {
        if (entry == null) throw ExtensionCallException("ensureFolder returned nothing")
        if (!entry.isFolder) throw ExtensionCallException("ensureFolder returned a file")
        return entry
    }

    /**
     * An `upload` reply: present, and **not a folder**. The size is deliberately *not* checked here —
     * a provider's metadata can lag its own write (the arc's standing trap), and the host's answer to
     * a disagreement is "check the file", never "delete it" (the arc-15 rule). That comparison
     * belongs to the caller's verification, where it can be worded; here it would be a refusal.
     */
    fun checkUploaded(entry: CloudEntry?): CloudEntry {
        if (entry == null) throw ExtensionCallException("upload returned nothing")
        if (entry.isFolder) throw ExtensionCallException("upload returned a folder")
        return entry
    }

    /** A `download` reply: the byte count it wrote, which cannot be negative. */
    fun checkDownloaded(bytes: Long): Long {
        if (bytes < 0) throw ExtensionCallException("download reported $bytes bytes")
        return bytes
    }
}
