package com.notesprout.android.export

import com.notesprout.android.NotebookTextExporter
import com.notesprout.android.data.PageRef
import kotlinx.serialization.Serializable

/**
 * The value types describing one export, assembled by [ExportActivity] and executed by
 * [ExportEngine].
 *
 * Everything the user picks on the export screen lands in an [ExportSpec]; nothing else is needed
 * to run the job. Keeping the choices in one immutable value is what lets a single screen replace
 * the three dialog chains that used to live in NotebookActivity / MainActivity / PageIndexActivity.
 */

/** Output file format. One per export — the screen offers a single choice. */
@Serializable
enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    PNG("png", "image/png", "PNG image"),
    MARKDOWN("md", "text/markdown", "Markdown (.md)"),
    TEXT("txt", "text/plain", "Plain text (.txt)"),
    SOIL("soil", "application/x-notesprout-soil", "Notebook (.soil)");

    /** True for formats rendered from page bitmaps — these honour the page-template option. */
    val isRaster: Boolean get() = this == PDF || this == PNG

    /** The recognition format, for the two text outputs. Null for everything else. */
    val textFormat: NotebookTextExporter.Format?
        get() = when (this) {
            MARKDOWN -> NotebookTextExporter.Format.MARKDOWN
            TEXT -> NotebookTextExporter.Format.PLAIN
            else -> null
        }
}

/** Which pages the export covers. Seeded from the launching screen, changeable by the user. */
enum class PageScope { ALL, CURRENT, SELECTED }

/** Where the finished file(s) go. */
@Serializable
enum class ExportDestination { SAVE, SHARE, TEMPLATE, DRIVE }

/**
 * What to do with an encrypted notebook's key when exporting a portable `.soil`.
 * Mirrors the three options the old `SoilExportKeying` action sheet offered.
 */
@Serializable
enum class SoilKeying { KEEP, REMOVE, NEW }

/**
 * A fully specified export job.
 *
 * [pageIds] is already resolved to the scoped, display-ordered set — [ExportEngine] does no
 * further filtering. [passphrase] is the notebook's SQLCipher key (null for a plaintext notebook)
 * and is used only to open the `.soil` for reading; it is never written anywhere.
 */
data class ExportSpec(
    val notebookId: String,
    val notebookTitle: String,
    val soilPath: String,
    val pages: List<PageRef>,
    val format: ExportFormat,
    val destination: ExportDestination,
    val passphrase: String?,
    /** Render the page template (grid/lines) under the content. Raster formats only. */
    val includeTemplate: Boolean = true,
    /** Append sticky-note endnote pages with two-way links. PDF only. */
    val stickyEndnotes: Boolean = true,
    /** Password-protect the output PDF (AES-128). PDF only; null = unprotected. */
    val pdfPassword: String? = null,
    /** How the exported `.soil` copy is keyed. `.soil` of an encrypted notebook only. */
    val soilKeying: SoilKeying = SoilKeying.KEEP,
    /** The new passphrase for [SoilKeying.NEW]. */
    val newSoilPassphrase: String? = null,
    /**
     * Folder path segments under the app-owned Drive root ("Notesprout Exports") for
     * [ExportDestination.DRIVE]. Names, not Drive ids — the chain is find-or-created at upload
     * time, so a path chosen in the picker but never exported to creates nothing remotely.
     */
    val drivePath: List<String> = emptyList(),
) {
    val pageIds: List<String> get() = pages.map { it.id }

    /** True when the export produces one file per page rather than a single document. */
    val isMultiFile: Boolean get() = format == ExportFormat.PNG && pages.size > 1
}
