package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One visit. **Id and time only** — a recents list must never leak a notebook's name to plaintext prefs. */
@Serializable
data class RecentEntry(val notebookId: String, val timestamp: Long)

/**
 * `SharedPreferences("sn_recents")` — the last [MAX] notebooks opened, most-recent first.
 *
 * **The store exists in R2; nothing records into it yet.** Opening a notebook is R3's event, and
 * the Recents *view* is R5. Deletion already prunes through it ([remove]) so the list cannot start
 * life holding dead ids the moment recording is switched on.
 *
 * A corrupt or hand-edited blob reads as an empty list rather than throwing — this is a
 * convenience, never a source of truth.
 */
class RecentsPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val codec = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RecentEntry.serializer())

    fun entries(): List<RecentEntry> = try {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        codec.decodeFromString(serializer, raw)
    } catch (_: Exception) {
        emptyList()
    }

    /** Move [notebookId] to the front, dropping anything past [MAX]. */
    fun record(notebookId: String, now: Long = System.currentTimeMillis()) {
        val list = entries().toMutableList()
        list.removeAll { it.notebookId == notebookId }
        list.add(0, RecentEntry(notebookId, now))
        if (list.size > MAX) list.subList(MAX, list.size).clear()
        save(list)
    }

    fun remove(notebookId: String) {
        val list = entries().toMutableList()
        if (list.removeAll { it.notebookId == notebookId }) save(list)
    }

    /** Drop every entry whose id is not in [aliveIds] (called when the list is read for display). */
    fun pruneDeleted(aliveIds: Set<String>) {
        val list = entries().toMutableList()
        if (list.removeAll { it.notebookId !in aliveIds }) save(list)
    }

    private fun save(list: List<RecentEntry>) {
        prefs.edit().putString(KEY, codec.encodeToString(serializer, list)).apply()
    }

    companion object {
        private const val FILE = "sn_recents"
        private const val KEY = "entries"
        const val MAX = 20
    }
}
