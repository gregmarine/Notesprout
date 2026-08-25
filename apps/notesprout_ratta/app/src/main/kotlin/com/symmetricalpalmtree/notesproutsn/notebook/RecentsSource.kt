package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Recents gather (arc 10 / T1 — IO): the device-local `sn_recents` store resolved against the
 * global index into display-ready rows, newest first.
 *
 * Nothing here touches the open `.soil` — recents is about *other* notebooks, so the only reads are
 * one blob-free batch of index rows ([IndexRepository.aliveNotebooks] — never `alive()`, which drags
 * a cover blob per row) plus one ancestry walk per **distinct** parent folder. Ids that no longer
 * resolve are pruned from the store in the same pass, so a deleted notebook cannot haunt the list
 * (the library's shelf self-heals the same way).
 *
 * The current notebook is looked up like any other — it is health-checked, and only then dropped
 * from the display list. Excluding it *before* the lookup would make the open notebook the one id
 * the prune could never verify.
 *
 * Rebuilt on every open. No cache: the dialog is a modal snapshot, and the store is twenty rows.
 * Logs counts and durations — never a notebook name.
 */
object RecentsSource {

    private const val TAG = "RecentsSource"

    /** One row of the panel: everything it draws, already resolved. */
    data class Row(
        val notebookId: String,
        val name: String,
        val folderPath: String,
        val timestamp: Long,
    )

    suspend fun gather(
        context: Context,
        repo: IndexRepository,
        currentNotebookId: String,
    ): List<Row> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val prefs = RecentsPrefs(context)
        val entries = prefs.entries()
        if (entries.isEmpty()) return@withContext emptyList()

        val alive = repo.aliveNotebooks(entries.map { it.notebookId }.distinct())
        prefs.pruneDeleted(alive.keys)

        val root = context.getString(R.string.library_root)
        val paths = HashMap<String, String>()
        val rows = RecentRows.select(entries, alive.keys, currentNotebookId).mapNotNull { id ->
            val s = alive[id] ?: return@mapNotNull null
            val parent = s.parentId
            val path = if (parent == null) root else paths.getOrPut(parent) {
                RecentRows.breadcrumb(root, repo.ancestry(parent).map { it.name })
            }
            Row(id, s.name, path, entries.first { it.notebookId == id }.timestamp)
        }
        Slog.d(TAG) {
            "gather: stored=${entries.size} alive=${alive.size} rows=${rows.size} " +
                "in ${System.currentTimeMillis() - t0} ms"
        }
        rows
    }
}
