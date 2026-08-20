package com.symmetricalpalmtree.notesprout.ext.links

import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.TrailEntry

/**
 * The link back-trail (arc 7 / L0) over the **host-owned** store — the extension never has files of
 * its own (the arc-2 rule). One key, [KEY], holding the whole trail as a small hand binary format:
 * a version byte, then each entry oldest-first (**newest last**) as `u16 big-endian byte length +
 * UTF-8 notebookId`, then the same for `pageId`. Fifty entries of two UUIDs is well under the
 * store's inline cap, so `put` / `get` carry it.
 *
 * The store binder is an in-parameter of every call — the host lends one per bind and revokes it
 * after (rule 25); nothing here holds one. Store failures are **not** swallowed: they propagate to
 * [LinkProviderService], which maps them to a Binder-marshalable exception. Only *decoding* is
 * tolerant — a missing key, a version byte we do not know, a truncated blob or an entry whose ids
 * break `TrailEntry`'s rules loses that entry (or the whole list), never throws.
 */
object TrailStore {

    /** The single store key the trail lives under. */
    const val KEY = "trail"

    /** Format version of the encoded blob. */
    private const val VERSION: Byte = 1

    /** Push [entry] as the newest; past `MAX_TRAIL_ENTRIES` the **oldest** is dropped. */
    fun push(store: IExtensionStore, entry: TrailEntry) {
        val trail = ArrayList(decode(store.get(KEY)))
        trail.add(entry)
        while (trail.size > ExtensionContract.MAX_TRAIL_ENTRIES) trail.removeAt(0)
        store.put(KEY, encode(trail))
    }

    /** Remove and return the newest entry, or null when the trail is empty. */
    fun pop(store: IExtensionStore): TrailEntry? {
        val trail = ArrayList(decode(store.get(KEY)))
        if (trail.isEmpty()) return null
        val last = trail.removeAt(trail.size - 1)
        if (trail.isEmpty()) store.delete(KEY) else store.put(KEY, encode(trail))
        return last
    }

    /** Forget the whole trail (a fresh notebook open — no follow brought us here). */
    fun clear(store: IExtensionStore) {
        store.delete(KEY)
    }

    // ── The blob format (internal + pure so the JVM tests drive it directly) ──────

    internal fun encode(entries: List<TrailEntry>): ByteArray {
        var size = 1
        val parts = ArrayList<Pair<ByteArray, ByteArray>>(entries.size)
        for (e in entries) {
            val nb = e.notebookId.toByteArray(Charsets.UTF_8)
            val pg = e.pageId.toByteArray(Charsets.UTF_8)
            parts.add(nb to pg)
            size += 2 + nb.size + 2 + pg.size
        }
        val out = ByteArray(size)
        out[0] = VERSION
        var i = 1
        for ((nb, pg) in parts) {
            i = writeString(out, i, nb)
            i = writeString(out, i, pg)
        }
        return out
    }

    /** Tolerant read: anything malformed yields the entries decoded so far (or none at all). */
    internal fun decode(blob: ByteArray?): List<TrailEntry> {
        if (blob == null || blob.isEmpty() || blob[0] != VERSION) return emptyList()
        val out = ArrayList<TrailEntry>()
        var i = 1
        while (i < blob.size) {
            val nb = readString(blob, i) ?: return out
            i += 2 + nb.length2
            val pg = readString(blob, i) ?: return out
            i += 2 + pg.length2
            // Construct only after the same checks TrailEntry's constructor enforces — a blank or
            // over-long id is a corrupt entry, not a crash.
            if (nb.value.isNotBlank() && nb.value.length <= ExtensionContract.MAX_LINK_ID_CHARS &&
                pg.value.isNotBlank() && pg.value.length <= ExtensionContract.MAX_LINK_ID_CHARS
            ) {
                out.add(TrailEntry(nb.value, pg.value))
            }
        }
        return out
    }

    private fun writeString(out: ByteArray, at: Int, bytes: ByteArray): Int {
        out[at] = ((bytes.size ushr 8) and 0xFF).toByte()
        out[at + 1] = (bytes.size and 0xFF).toByte()
        System.arraycopy(bytes, 0, out, at + 2, bytes.size)
        return at + 2 + bytes.size
    }

    /** A decoded string plus the byte length it occupied (so the cursor can step past it). */
    private class Field(val value: String, val length2: Int)

    private fun readString(blob: ByteArray, at: Int): Field? {
        if (at + 2 > blob.size) return null
        val len = ((blob[at].toInt() and 0xFF) shl 8) or (blob[at + 1].toInt() and 0xFF)
        if (at + 2 + len > blob.size) return null
        return Field(String(blob, at + 2, len, Charsets.UTF_8), len)
    }
}
