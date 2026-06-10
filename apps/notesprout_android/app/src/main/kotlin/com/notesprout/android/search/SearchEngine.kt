package com.notesprout.android.search

import java.io.File

data class SearchResult(
    val file: File,
    val displayName: String,
    val folderLabel: String,
    val score: Int
)

object SearchEngine {

    /**
     * Recursively searches [root] for .soil files whose display names match [query].
     * Results are ranked: exact substring (score 3) > all words present (score 2) > prefix/initials (score 1).
     * [notesDir] is the root Notebooks directory — used to build the folderLabel breadcrumb.
     */
    fun search(query: String, root: File, notesDir: File): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val results = mutableListOf<SearchResult>()
        collectSoilFiles(root, notesDir, results, trimmed)

        return results.sortedWith(
            compareByDescending<SearchResult> { it.score }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun collectSoilFiles(
        dir: File,
        notesDir: File,
        results: MutableList<SearchResult>,
        query: String
    ) {
        dir.listFiles()?.forEach { file ->
            when {
                file.isDirectory && !file.name.startsWith(".") -> {
                    collectSoilFiles(file, notesDir, results, query)
                }
                file.isFile && file.extension == "soil" -> {
                    val displayName = file.nameWithoutExtension
                    val score = score(query, displayName)
                    if (score > 0) {
                        results.add(
                            SearchResult(
                                file = file,
                                displayName = displayName,
                                folderLabel = buildFolderLabel(file.parentFile!!, notesDir),
                                score = score
                            )
                        )
                    }
                }
            }
        }
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
        val nameWords = n.split(Regex("[\\s_\\-]+")).filter { it.isNotEmpty() }
        if (queryWords.size > 1 && queryWords.all { qw -> nameWords.any { nw -> nw.contains(qw) } }) return 2

        val initials = nameWords.map { it.first() }.joinToString("")
        if (initials.contains(q)) return 1
        if (nameWords.any { it.startsWith(q) }) return 1

        return 0
    }

    /**
     * Builds a breadcrumb label like "Notebooks" or "Notebooks › Work › Projects".
     * [notesDir] is the root — its name is always the first segment ("Notebooks").
     */
    private fun buildFolderLabel(folder: File, notesDir: File): String {
        val segments = mutableListOf<String>()
        var current: File? = folder
        while (current != null && current != notesDir.parentFile) {
            segments.add(0, current.name)
            if (current == notesDir) break
            current = current.parentFile
        }
        return segments.joinToString(" › ")
    }
}
