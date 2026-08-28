package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract

/**
 * What an export is **called** — the filename offered to the SAF picker, and the display name the
 * [com.symmetricalpalmtree.notesproutsn.extension.ExportSpec] carries for formats that can hold a
 * title (arc 15 / E1). Pure, JVM-tested: naming is a rule, not a screen.
 *
 * The sanitize rule is og Notesprout's, verbatim (`docs/full-notebook-export.md`), so a file
 * exported by SN is named the way the family has always named one: the notebook's **current index
 * display name** with everything outside `[a-zA-Z0-9_\-. ]` removed and the result trimmed —
 * **spaces inside are kept**, because a notebook called "Meeting notes" should not export as
 * "Meetingnotes". If nothing usable survives, or what survives is `.` or `..` (a name a filesystem
 * reads as a directory), the notebook's **UUID** is the name instead: an id is always a legal
 * filename and is never empty.
 *
 * Two callers, one base, on purpose: the file on disk and the name inside the file must agree, and
 * the way to guarantee that is for both to come from [base].
 */
object ExportNaming {

    /** Everything og strips. Note the space is *not* in the class — it survives. */
    private val ILLEGAL = Regex("[^a-zA-Z0-9_\\-. ]")

    /**
     * The sanitized filename stem for [displayName], falling back to [notebookId] when the name
     * strips down to nothing usable. Strip first, then trim: " ***name*** " must become "name",
     * not " name " — the stripping is what exposes the outer spaces.
     */
    fun base(displayName: String, notebookId: String): String {
        val cleaned = ILLEGAL.replace(displayName, "").trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") notebookId else cleaned
    }

    /**
     * What the picker is offered: [base] + "." + the exporter's declared extension (already
     * `[a-z0-9]{1..12}` by [com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo]'s
     * constructor — an exporter cannot smuggle a path or a second extension through it).
     */
    fun suggestedFileName(displayName: String, notebookId: String, fileExtension: String): String =
        base(displayName, notebookId) + "." + fileExtension

    /**
     * The `ExportSpec.notebookName` value — the same sanitized base, truncated to the spec's
     * [ExporterContract.MAX_NAME_CHARS] cap. The spec also forbids `/` and NUL, which the sanitize
     * already guarantees; the truncation is the only thing left to do, and it is done here rather
     * than at the call site so the spec's constructor can never be the thing that refuses an
     * export over a long name.
     */
    fun specName(displayName: String, notebookId: String): String =
        base(displayName, notebookId).take(ExporterContract.MAX_NAME_CHARS)
}
