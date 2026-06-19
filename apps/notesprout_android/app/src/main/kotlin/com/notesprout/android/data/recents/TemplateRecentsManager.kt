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
 * Device-local store of the most recently used library templates.
 *
 * Backed by a single [SharedPreferences] key ([KEY_ENTRIES]) holding a JSON-serialized
 * `List<TemplateRecentEntry>`, ordered newest-first. Mirrors [RecentsManager] — not in
 * `notesprout.db`, not in any `.soil` file.
 *
 * Invariants:
 * - A template appears at most once. Using an already-listed template bumps it to the top.
 * - Capacity is [MAX_ENTRIES]; on overflow the oldest (furthest-back timestamp) is dropped.
 * - Order is always [TemplateRecentEntry.timestamp] descending.
 */
object TemplateRecentsManager {

    const val MAX_ENTRIES = 20
    private const val PREFS_NAME = "notesprout_template_recents"
    private const val KEY_ENTRIES = "entries"

    private val codec = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(TemplateRecentEntry.serializer())

    /** All entries, ordered newest-first. Tolerant of malformed/absent JSON → empty list. */
    fun load(context: Context): List<TemplateRecentEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { codec.decodeFromString(listSerializer, raw) }
            .getOrElse { emptyList() }
            .sortedByDescending { it.timestamp }
    }

    /**
     * Record that [templateId] was just used: drop any existing entry for the id, prepend a
     * fresh [TemplateRecentEntry] stamped *now*, cap to [MAX_ENTRIES], persist. No-op on a blank id.
     */
    fun recordUse(context: Context, templateId: String) {
        if (templateId.isBlank()) return
        val now = System.currentTimeMillis()
        val updated = (listOf(TemplateRecentEntry(templateId, now)) +
            load(context).filter { it.templateId != templateId })
            .take(MAX_ENTRIES)
        persist(context, updated)
    }

    /** Remove the entry for [templateId] if present (used by the stale-entry prune). */
    fun remove(context: Context, templateId: String) {
        val existing = load(context)
        if (existing.none { it.templateId == templateId }) return
        persist(context, existing.filter { it.templateId != templateId })
    }

    /**
     * Resolve the stored entries against the index into display-ready [ResolvedTemplateRecent]
     * models, newest-first. Any entry whose template is missing or soft-deleted is skipped and
     * pruned from the store in a single write (self-healing). Runs on [Dispatchers.IO].
     */
    suspend fun resolve(context: Context): List<ResolvedTemplateRecent> = withContext(Dispatchers.IO) {
        val entries = load(context)
        if (entries.isEmpty()) return@withContext emptyList()

        val repo = IndexRepository(NotesproutIndex.dao())
        val allFolders = repo.getAllTemplateFolders()
        val missing = mutableListOf<String>()

        val resolved = entries.mapNotNull { entry ->
            val entity = repo.getTemplate(entry.templateId)
            if (entity == null || entity.deletedAt != null || entity.type != ObjectType.TEMPLATE) {
                missing.add(entry.templateId)
                null
            } else {
                ResolvedTemplateRecent(
                    templateId   = entity.id,
                    templateName = entity.name,
                    folderPath   = buildFolderPath(entity.parentId, allFolders),
                    timestamp    = entry.timestamp,
                )
            }
        }

        if (missing.isNotEmpty()) {
            // Re-load before pruning so a concurrent use isn't clobbered.
            persist(context, load(context).filter { it.templateId !in missing })
        }
        resolved
    }

    /** Full breadcrumb for [parentId], e.g. `"Templates › A › B"` (root → `"Templates"`). */
    private fun buildFolderPath(parentId: String?, allFolders: List<ObjectEntity>): String {
        val segments = mutableListOf<String>()
        var currentId: String? = parentId
        while (currentId != null) {
            val folder = allFolders.find { it.id == currentId } ?: break
            segments.add(0, folder.name)
            currentId = folder.parentId
        }
        segments.add(0, "Templates")
        return segments.joinToString(" › ")
    }

    private fun persist(context: Context, entries: List<TemplateRecentEntry>) {
        prefs(context).edit()
            .putString(KEY_ENTRIES, codec.encodeToString(listSerializer, entries))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
