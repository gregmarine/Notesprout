package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The CLOUD_STORAGE point (arc 25 / V1) — SN's EIGHTH capability point, granted by the user
 * 2026-09-04 (`DRIVE_PLAN.md` decision 1), and the first that is **generic over a provider**: the
 * contract speaks folders, files and bytes, never a provider's own terms. `NSE · Google Drive`
 * (`:ext-drive`) is the first provider; a second provider is a new extension on this same point,
 * not a new point.
 *
 * **The extension owns the network and the account** (decision 3): the OAuth flow, the client id
 * and secret (compiled only into the extension APK), the refresh token (rows in its extension
 * store, host-encrypted). The host has no INTERNET permission and never learns any of it — it
 * sees a [CloudStatus] (connected? account label) and performs file operations by **name**, under
 * a root the provider owns. **No secret, no device path, no URL ever crosses this seam** in either
 * direction; a path here is a list of folder *names* under the provider's root, an entry is named
 * by the opaque id the provider gave it.
 *
 * **Store-taking, bind-per-call** (the tag manager's second call shape): the store rides every call,
 * minted per bind, uid-bound, revoked with the unbind. There is no held bind — every operation is
 * one Binder call sized by a measured timeout, because a Binder call cannot be cancelled.
 *
 * **No other extension is aware of it** (decision 6): an exporter or importer only ever sees the two
 * fds the host hands it, so a cloud destination changes only where the host's fd points. If an
 * extension ever needs the cloud for itself, that is a host-side `ICloudHost` stub minted per
 * showing (the `IDocumentHost` recipe) under a fresh user decision — recorded in the plan, not built.
 *
 * The account label (an email, usually) is user content: **never logged on either side.**
 *
 * The two typed refusals the host distinguishes for its dialogs are [NOT_CONNECTED] and [NETWORK];
 * both cross as `IllegalStateException` with the message compared **verbatim**. Every other failure
 * is one of the three marshalable exceptions with any message, and the host reads it as "the
 * provider didn't answer".
 */
object CloudContract {

    /** Intent action a cloud-storage `<service>` declares in its intent-filter. */
    const val ACTION_CLOUD_STORAGE: String =
        "com.symmetricalpalmtree.notesproutsn.extension.CLOUD_STORAGE"

    /** Intent action the provider's exported **connect** screen `<activity>` declares (the sign-in,
     *  a tier-2 screen the extension owns). Resolved with `setPackage(<the discovered service's
     *  package>)` and launched **for a result**; a plain `startActivity` leaves `callingPackage`
     *  null and the screen refuses it. Nothing rides its Intent — no extras at all. */
    const val ACTION_CLOUD_STORAGE_SCREEN: String =
        "com.symmetricalpalmtree.notesproutsn.extension.CLOUD_STORAGE_SCREEN"

    /**
     * The floor for the cloud point: born at API version 8, so there is no older cloud shape a host
     * could accept — a service declaring less is not a provider this host knows. The calendar's
     * per-action precedent (`ExtensionContract.MIN_API_VERSION_FOR_CALENDAR`); no other floor moves.
     */
    const val MIN_API_VERSION_FOR_CLOUD: Int = 8

    // ── Paths and names ──────
    // A path is folder NAMES under the provider's root, never the root itself and never anything
    // above it: the provider resolves each segment by name (find-or-create for `ensureFolder`, find
    // for everything else). The bounds are the seam's, not a provider's — Drive allows 32k-char
    // names; nothing the host ever writes needs more than a filename.

    /** Most segments in one path — `Backups/<device>` is two, `Exports/<folder>/<sub>` three. */
    const val MAX_PATH_DEPTH: Int = 8

    /** Longest one folder or file name may be (chars) — the common filesystem ceiling. */
    const val MAX_NAME_CHARS: Int = 255

    /** Longest an entry id may be (chars). Drive's are ≈ 33–44; the cap is generous for a second
     *  provider and still far below anything a parcel would notice. */
    const val MAX_ENTRY_ID_CHARS: Int = 256

    /** Longest a MIME type string may be (chars). */
    const val MAX_MIME_CHARS: Int = 128

    /** Longest an account label may be (chars) — an email, or whatever the provider calls the
     *  signed-in account. Display only. */
    const val MAX_ACCOUNT_LABEL_CHARS: Int = 254

    /** Longest a provider's display name may be (chars) — what the host's Destination / Source rows
     *  and its Cloud section print ("Google Drive"). */
    const val MAX_PROVIDER_NAME_CHARS: Int = 64

    /** Most entries one `list` reply carries. A reply is an ordinary parcel (the tag manager's
     *  lesson), and an entry is a few hundred bytes at most; a folder the host itself wrote never
     *  comes near this, and a provider truncates a larger listing rather than failing it. */
    const val MAX_LIST_ENTRIES: Int = 1_000

    // ── Typed refusals — `IllegalStateException` messages compared VERBATIM by the host ──────

    /** No account is connected (never was, or `disconnect` ran, or the provider's token was
     *  revoked out from under it). The host offers Connect. */
    const val NOT_CONNECTED: String = "not connected"

    /** The provider could not reach its service — offline, DNS, TLS, a 5xx, a timeout of its own.
     *  Nothing was changed. The host says so and offers to try again. */
    const val NETWORK: String = "network"

    /** Whether [name] is one legal folder or file name: non-blank, at most [MAX_NAME_CHARS], no
     *  `/` or `\`, no control characters, not `.` or `..`, no leading or trailing whitespace. Pure —
     *  both sides run the same check. */
    fun isName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) return false
        if (name == "." || name == "..") return false
        if (name.first().isWhitespace() || name.last().isWhitespace()) return false
        for (c in name) {
            if (c == '/' || c == '\\' || c.isISOControl()) return false
        }
        return true
    }

    /** Whether [id] is a legal entry id: non-blank, at most [MAX_ENTRY_ID_CHARS], no whitespace or
     *  control characters. Opaque otherwise — the provider minted it and the provider reads it. */
    fun isEntryId(id: String): Boolean {
        if (id.isEmpty() || id.length > MAX_ENTRY_ID_CHARS) return false
        for (c in id) if (c.isWhitespace() || c.isISOControl()) return false
        return true
    }

    /** Whether [label] is printable display text of at most [max] chars: no control characters.
     *  Empty is allowed — a status that is not connected has no label. */
    fun isLabel(label: String, max: Int): Boolean {
        if (label.length > max) return false
        for (c in label) if (c.isISOControl()) return false
        return true
    }

    /** The path check both stubs run first thing: at most [MAX_PATH_DEPTH] segments, each one
     *  [isName]. An empty path names the provider's root. Throws `IllegalArgumentException`. */
    fun requireValidPath(path: Array<String>?): Array<String> {
        requireNotNull(path) { "path is null" }
        require(path.size <= MAX_PATH_DEPTH) { "path has ${path.size} segments — at most $MAX_PATH_DEPTH" }
        for ((i, segment) in path.withIndex()) require(isName(segment)) { "path segment $i is not a name" }
        return path
    }

    /** The MIME check: non-blank, at most [MAX_MIME_CHARS], `type/subtype` shaped, no whitespace. */
    fun isMime(mime: String): Boolean {
        if (mime.isEmpty() || mime.length > MAX_MIME_CHARS) return false
        val slash = mime.indexOf('/')
        if (slash <= 0 || slash == mime.length - 1 || mime.indexOf('/', slash + 1) >= 0) return false
        for (c in mime) if (c.isWhitespace() || c.isISOControl()) return false
        return true
    }
}
