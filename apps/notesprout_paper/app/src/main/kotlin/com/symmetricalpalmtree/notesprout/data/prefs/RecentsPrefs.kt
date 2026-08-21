package com.symmetricalpalmtree.notesprout.data.prefs

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class RecentEntry(val notebookId: String, val timestamp: Long)

class RecentsPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val codec = Json { ignoreUnknownKeys = true }

    fun entries(): List<RecentEntry> = try {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        codec.decodeFromString(ListSerializer(RecentEntry.serializer()), raw)
    } catch (_: Exception) {
        emptyList()
    }

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

    fun pruneDeleted(aliveIds: Set<String>) {
        val list = entries().toMutableList()
        if (list.removeAll { it.notebookId !in aliveIds }) save(list)
    }

    private fun save(list: List<RecentEntry>) {
        prefs.edit().putString(KEY, codec.encodeToString(ListSerializer(RecentEntry.serializer()), list)).apply()
    }

    companion object {
        private const val FILE = "paper_recents"
        private const val KEY = "entries"
        const val MAX = 20
    }
}
