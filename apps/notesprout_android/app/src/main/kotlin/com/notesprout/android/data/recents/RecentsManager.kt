package com.notesprout.android.data.recents

import android.content.Context
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ObjectEntity
import com.notesprout.android.data.index.ObjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Device-local store of the most recently opened notebooks.
 *
 * Backed by a single [SharedPreferences] key ([KEY_ENTRIES]) holding a JSON-serialized
 * `List<RecentEntry>`, ordered newest-first. Mirrors [com.notesprout.android.state.AppStateManager]
 * and [com.notesprout.android.sort.SortPreferencesManager] — not in `notesprout.db`, not in any
 * `.soil` file.
 *
 * Invariants:
 * - A notebook appears at most once. Opening an already-listed notebook bumps it to the top.
 * - Capacity is [MAX_ENTRIES]; on overflow the oldest (furthest-back timestamp) is dropped.
 * - Order is always [RecentEntry.timestamp] descending.
 */
object RecentsManager {

    const val MAX_ENTRIES = 20
    private const val PREFS_NAME = "notesprout_recents"
    private const val KEY_ENTRIES = "entries"

    private val codec = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(RecentEntry.serializer())

    /** All entries, ordered newest-first. Tolerant of malformed/absent JSON → empty list. */
    fun load(context: Context): List<RecentEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { codec.decodeFromString(listSerializer, raw) }
            .getOrElse { emptyList() }
            .sortedByDescending { it.timestamp }
    }

    /**
     * Record that [notebookId] was just opened: drop any existing entry for the id, prepend a
     * fresh [RecentEntry] stamped *now*, cap to [MAX_ENTRIES], persist. No-op on a blank id.
     */
    fun recordOpen(context: Context, notebookId: String) {
        if (notebookId.isBlank()) return
        val now = System.currentTimeMillis()
        val updated = (listOf(RecentEntry(notebookId, now)) +
            load(context).filter { it.notebookId != notebookId })
            .take(MAX_ENTRIES)
        persist(context, updated)
    }

    /**
     * Record that [notebookId] was just closed: if present, refresh its [RecentEntry.timestamp]
     * to *now* and re-sort newest-first. No-op if the id is blank or not currently listed.
     */
    fun recordClose(context: Context, notebookId: String) {
        if (notebookId.isBlank()) return
        val existing = load(context)
        if (existing.none { it.notebookId == notebookId }) return
        val now = System.currentTimeMillis()
        val updated = existing
            .map { if (it.notebookId == notebookId) it.copy(timestamp = now) else it }
            .sortedByDescending { it.timestamp }
        persist(context, updated)
    }

    /** Remove the entry for [notebookId] if present (used by the stale-entry prune). */
    fun remove(context: Context, notebookId: String) {
        val existing = load(context)
        if (existing.none { it.notebookId == notebookId }) return
        persist(context, existing.filter { it.notebookId != notebookId })
    }

    /**
     * Resolve the stored entries against the index into display-ready [ResolvedRecent] models,
     * newest-first. Any entry whose notebook is missing or soft-deleted is skipped and pruned
     * from the store in a single write (self-healing). Runs on [Dispatchers.IO].
     */
    suspend fun resolve(context: Context): List<ResolvedRecent> = withContext(Dispatchers.IO) {
        val entries = load(context)
        if (entries.isEmpty()) return@withContext emptyList()

        val repo = IndexRepository(NotesproutIndex.dao())
        val allFolders = repo.getAllFolders()
        val missing = mutableListOf<String>()

        val resolved = entries.mapNotNull { entry ->
            val entity = repo.getNotebook(entry.notebookId)
            if (entity == null || entity.deletedAt != null || entity.type != ObjectType.NOTEBOOK) {
                missing.add(entry.notebookId)
                null
            } else {
                ResolvedRecent(
                    notebookId   = entity.id,
                    notebookName = entity.name,
                    folderPath   = buildFolderPath(entity.parentId, allFolders),
                    timestamp    = entry.timestamp,
                )
            }
        }

        if (missing.isNotEmpty()) {
            // Re-load before pruning so a concurrent open/close isn't clobbered.
            persist(context, load(context).filter { it.notebookId !in missing })
        }
        resolved
    }

    /** Full breadcrumb for [parentId], e.g. `"Notebooks › A › B"` (root → `"Notebooks"`). */
    private fun buildFolderPath(parentId: String?, allFolders: List<ObjectEntity>): String {
        val segments = mutableListOf<String>()
        var currentId: String? = parentId
        while (currentId != null) {
            val folder = allFolders.find { it.id == currentId } ?: break
            segments.add(0, folder.name)
            currentId = folder.parentId
        }
        segments.add(0, "Notebooks")
        return segments.joinToString(" › ")
    }

    private fun persist(context: Context, entries: List<RecentEntry>) {
        prefs(context).edit()
            .putString(KEY_ENTRIES, codec.encodeToString(listSerializer, entries))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
