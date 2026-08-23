package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One hop the user came *from* — where a Back should land. **Ids only**, never a notebook name:
 *  these prefs are plaintext, and the index is the only place a name is allowed to live. */
@Serializable
data class TrailEntry(val notebookId: String, val pageId: String)

/**
 * The pure trail algebra (arc 6 / K4) — a bounded LIFO stack of [TrailEntry], newest **last**.
 *
 * Split out from [LinkTrail] so every rule that matters is JVM-testable without a `Context`: the
 * cap, the drop-the-oldest overflow, the LIFO pop, and — the one that is really a safety rule —
 * [decode] treating stored JSON as *untrusted input*. A hand-edited or half-written blob reads as
 * an empty trail rather than throwing (a walk-back must never crash the notebook), and an over-cap
 * stored list is truncated to the newest [MAX_ENTRIES] on the way in, so a corrupt file cannot make
 * the walk-back loop longer than the flow's own bound.
 */
object TrailCodec {

    /** How deep a link story can go before its oldest hop is forgotten. Also the walk-back's loop
     *  bound (`LinkFollowFlow`), so the two can never disagree. */
    const val MAX_ENTRIES = 50

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TrailEntry.serializer())

    /** Never throws: null, blank, corrupt, or valid JSON of the wrong shape all read as empty. */
    fun decode(raw: String?): List<TrailEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val list = try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            return emptyList()
        }
        return if (list.size > MAX_ENTRIES) list.takeLast(MAX_ENTRIES) else list
    }

    fun encode(entries: List<TrailEntry>): String = json.encodeToString(serializer, entries)

    /** Append [entry] as the newest hop, dropping the **oldest** once past [MAX_ENTRIES]. */
    fun push(entries: List<TrailEntry>, entry: TrailEntry): List<TrailEntry> {
        val out = entries + entry
        return if (out.size > MAX_ENTRIES) out.takeLast(MAX_ENTRIES) else out
    }

    /** The newest hop and what is left without it; `null` (and the same list) when there is none. */
    fun pop(entries: List<TrailEntry>): Pair<TrailEntry?, List<TrailEntry>> =
        if (entries.isEmpty()) null to entries
        else entries.last() to entries.dropLast(1)
}

/**
 * `SharedPreferences("sn_trail")` — where a link story's hops are remembered (arc 6 / K4).
 *
 * Every successful follow pushes the **origin** (the notebook + page the user was looking at when
 * they tapped the link) before navigating; a Back — the notebook's swipe-up, or the system Back on
 * a screen that was opened *via* a link — pops the newest and returns there. Persisted rather than
 * held in RAM because a follow into another notebook is a real Activity hand-off: the source screen
 * finishes, and a process death mid-story must not strand the user with no way home.
 *
 * A fresh, non-via-link open of any notebook [clear]s it: that is a new story, and the old trail
 * would walk back into someone else's.
 *
 * All the rules live in [TrailCodec]; this is only the prefs door (app context, ids only).
 */
class LinkTrail(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun entries(): List<TrailEntry> = TrailCodec.decode(prefs.getString(KEY, null))

    fun push(entry: TrailEntry) = save(TrailCodec.push(entries(), entry))

    fun pop(): TrailEntry? {
        val (entry, rest) = TrailCodec.pop(entries())
        if (entry != null) save(rest)
        return entry
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun save(entries: List<TrailEntry>) {
        prefs.edit().putString(KEY, TrailCodec.encode(entries)).apply()
    }

    private companion object {
        const val FILE = "sn_trail"
        const val KEY = "trail"
    }
}
