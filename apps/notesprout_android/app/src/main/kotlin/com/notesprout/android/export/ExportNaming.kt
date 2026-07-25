package com.notesprout.android.export

import com.notesprout.android.data.PageRef

/**
 * Filename and template-name hygiene, shared by [ExportEngine] and [ExportDelivery].
 *
 * The whitelist (`[^a-zA-Z0-9_\-. ]`) and the two de-duplication conventions are lifted verbatim
 * from the export paths these replace — `_2`/`_3` for filenames, ` (2)`/` (3)` for template names,
 * the latter matching `TemplateBrowserActivity.makeUniqueName`.
 */
internal object ExportNaming {

    /** Whitelist [raw] to filesystem-safe characters, replacing rejects with `_`. */
    fun sanitizeFile(raw: String, fallback: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim('_', ' ').ifBlank { fallback }

    /** Whitelist a proposed template name to the browser's accepted characters; never empty. */
    fun sanitizeTemplate(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim()
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "Template" else cleaned
    }

    /** De-duplicate a filename base among [used] by appending `_2`, `_3`, … (no extension). */
    fun uniqueFile(base: String, used: Set<String>): String {
        if (base !in used) return base
        var n = 2
        while ("${base}_$n" in used) n++
        return "${base}_$n"
    }

    /** De-duplicate a template name among [existing] using a ` (2)`, ` (3)`, … suffix. */
    fun uniqueTemplate(name: String, existing: List<String>): String {
        if (existing.none { it.equals(name, ignoreCase = true) }) return name
        var n = 2
        while (existing.any { it.equals("$name ($n)", ignoreCase = true) }) n++
        return "$name ($n)"
    }

    /**
     * The default label for [page] — its top heading, else "Page N". [spaced] controls whether the
     * fallback reads "Page 3" (template names) or "Page3" (filename bases).
     */
    fun pageLabel(page: PageRef, spaced: Boolean = true): String =
        page.headingName ?: if (spaced) "Page ${page.number}" else "Page${page.number}"

    /**
     * Build the per-page `(pageId, filenameBase)` specs `NotebookExporter.exportPagesPng` expects,
     * each prefixed with the notebook name and de-duplicated across the batch.
     */
    fun pngFileSpecs(notebookTitle: String, pages: List<PageRef>): List<Pair<String, String>> {
        val safeNotebook = sanitizeFile(notebookTitle, "notebook")
        val used = mutableSetOf<String>()
        return pages.map { page ->
            val label = sanitizeFile(pageLabel(page, spaced = false), "Page${page.number}")
            val base = ExportNaming.uniqueFile("${safeNotebook}_$label", used)
            used.add(base)
            page.id to base
        }
    }
}
