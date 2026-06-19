package com.notesprout.android.search

import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.ObjectEntity

data class SearchResult(
    val entity: ObjectEntity,
    val displayName: String,
    val folderLabel: String,
    val score: Int
)

object SearchEngine {

    /**
     * Queries the index for all notebooks matching [query].
     * Results are ranked: exact substring (score 3) > all words present (score 2) > prefix/initials (score 1).
     */
    suspend fun search(query: String, repository: IndexRepository): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val allNotebooks = repository.getAllNotebooks()
        val allFolders   = repository.getAllFolders()

        val results = allNotebooks.mapNotNull { entity ->
            val s = score(trimmed, entity.name)
            if (s > 0) {
                SearchResult(
                    entity      = entity,
                    displayName = entity.name,
                    folderLabel = buildFolderLabel(entity.parentId, allFolders),
                    score       = s,
                )
            } else null
        }

        return results.sortedWith(
            compareByDescending<SearchResult> { it.score }
                .thenBy { it.displayName.lowercase() }
        )
    }

    /**
     * Builds a breadcrumb label such as "Notebooks" or "Notebooks › Work › Projects"
     * by walking up the parentId chain using the already-fetched [folders] list.
     */
    private fun buildFolderLabel(parentId: String?, folders: List<ObjectEntity>): String {
        val segments = mutableListOf<String>()
        var currentId = parentId
        while (currentId != null) {
            val folder = folders.find { it.id == currentId } ?: break
            segments.add(0, folder.name)
            currentId = folder.parentId
        }
        segments.add(0, "Notebooks")
        return segments.joinToString(" › ")
    }

    /** Public name scorer (same tiers as notebook search). */
    fun scoreName(query: String, name: String): Int = score(query, name)

    /**
     * Queries the index for all templates matching [query].
     * Same ranking tiers as [search].
     */
    suspend fun searchTemplates(query: String, repository: IndexRepository): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val allTemplates       = repository.getAllTemplates()
        val allTemplateFolders = repository.getAllTemplateFolders()

        val results = allTemplates.mapNotNull { entity ->
            val s = score(trimmed, entity.name)
            if (s > 0) {
                SearchResult(
                    entity      = entity,
                    displayName = entity.name,
                    folderLabel = buildTemplateFolderLabel(entity.parentId, allTemplateFolders),
                    score       = s,
                )
            } else null
        }

        return results.sortedWith(
            compareByDescending<SearchResult> { it.score }
                .thenBy { it.displayName.lowercase() }
        )
    }

    /** Like [buildFolderLabel] but rooted at "Templates" over template folders. */
    private fun buildTemplateFolderLabel(parentId: String?, folders: List<ObjectEntity>): String {
        val segments = mutableListOf<String>()
        var currentId = parentId
        while (currentId != null) {
            val folder = folders.find { it.id == currentId } ?: break
            segments.add(0, folder.name)
            currentId = folder.parentId
        }
        segments.add(0, "Templates")
        return segments.joinToString(" › ")
    }

    /**
     * Scoring tiers:
     *   3 = case-insensitive substring match (query appears anywhere in name)
     *   2 = all query words are present in name (order-independent)
     *   1 = initials/prefix match (each query char matches first letter of a word, or name starts with query)
     *   0 = no match
     */
    private fun score(query: String, name: String): Int {
        val q = query.lowercase()
        val n = name.lowercase()

        if (n.contains(q)) return 3

        val queryWords = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val nameWords  = n.split(Regex("[\\s_\\-]+")).filter { it.isNotEmpty() }
        if (queryWords.size > 1 && queryWords.all { qw -> nameWords.any { nw -> nw.contains(qw) } }) return 2

        val initials = nameWords.map { it.first() }.joinToString("")
        if (initials.contains(q)) return 1
        if (nameWords.any { it.startsWith(q) }) return 1

        return 0
    }
}
