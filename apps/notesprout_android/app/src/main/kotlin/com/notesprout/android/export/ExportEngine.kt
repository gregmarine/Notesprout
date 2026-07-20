package com.notesprout.android.export

import android.content.Context
import com.notesprout.android.NotebookExporter
import com.notesprout.android.NotebookPackager
import com.notesprout.android.NotebookTextExporter
import com.notesprout.android.crypto.SoilMigrator
import com.notesprout.android.data.index.IndexRepository
import java.io.File

/**
 * Runs an [ExportSpec] and returns the file(s) it produced.
 *
 * This is the single place any export is executed from. It owns no UI and no key resolution — the
 * screen resolves the passphrase once and hands it over in the spec — so the only thing here is the
 * dispatch to the existing exporters, all of which open their own transient connection by path:
 *
 *  - PDF   → [NotebookExporter.exportPagesPdf]
 *  - PNG   → [NotebookExporter.exportPagesPng]
 *  - MD/TXT→ [NotebookTextExporter.exportFromPath]
 *  - .soil → [NotebookPackager.packageForExport] + the [SoilMigrator] keying transform
 *
 * Callers must invoke this off the UI thread; every underlying exporter is a `suspend` function
 * that dispatches its own heavy work, but the recognition and render loops are long.
 *
 * [onProgress] is called on the IO thread with (current, total) — post to the main thread yourself.
 */
object ExportEngine {

    /** Progress phases, so the screen can word its status line for what is actually happening. */
    enum class Phase { RENDERING, RECOGNIZING, PACKAGING }

    suspend fun run(
        context: Context,
        repo: IndexRepository,
        spec: ExportSpec,
        onProgress: (phase: Phase, current: Int, total: Int) -> Unit,
    ): List<File> = when (spec.format) {

        ExportFormat.PDF -> listOf(
            NotebookExporter.exportPagesPdf(
                context = context,
                soilPath = spec.soilPath,
                pageIds = spec.pageIds,
                notebookTitle = spec.notebookTitle,
                onProgress = { c, t -> onProgress(Phase.RENDERING, c, t) },
                passphrase = spec.passphrase,
                exportPassword = spec.pdfPassword,
                includeTemplate = spec.includeTemplate,
                stickyEndnotes = spec.stickyEndnotes,
            )
        )

        ExportFormat.PNG -> NotebookExporter.exportPagesPng(
            context = context,
            soilPath = spec.soilPath,
            pages = ExportNaming.pngFileSpecs(spec.notebookTitle, spec.pages),
            onProgress = { c, t -> onProgress(Phase.RENDERING, c, t) },
            passphrase = spec.passphrase,
            includeTemplate = spec.includeTemplate,
        )

        ExportFormat.MARKDOWN, ExportFormat.TEXT -> listOf(
            NotebookTextExporter.exportFromPath(
                context = context,
                soilPath = spec.soilPath,
                pageIds = spec.pageIds,
                notebookTitle = spec.notebookTitle,
                format = spec.format.textFormat!!,
                onProgress = { c, t -> onProgress(Phase.RECOGNIZING, c, t) },
                passphrase = spec.passphrase,
            )
        )

        ExportFormat.SOIL -> listOf(exportSoil(context, repo, spec, onProgress))
    }

    /**
     * Copy the `.soil` out and apply the chosen keying to **the copy only** — the Garden original is
     * never touched, which is the invariant the old `SoilExportKeying` was built around.
     *
     * `packageForExport`'s `openableKey` contract: `""` = plaintext, non-empty = the key to open
     * with, `null` = skip the metadata refresh and copy the cold file as-is.
     */
    private suspend fun exportSoil(
        context: Context,
        repo: IndexRepository,
        spec: ExportSpec,
        onProgress: (phase: Phase, current: Int, total: Int) -> Unit,
    ): File {
        onProgress(Phase.PACKAGING, 0, 1)

        val packaged = NotebookPackager.packageForExport(
            context = context,
            repo = repo,
            notebookId = spec.notebookId,
            openableKey = spec.passphrase ?: "",
        )

        val key = spec.passphrase
        try {
            when (spec.soilKeying) {
                // Plaintext stays plaintext; an encrypted copy keeps whatever key it already has.
                SoilKeying.KEEP -> Unit
                SoilKeying.REMOVE -> if (key != null) SoilMigrator.decryptInPlace(packaged, key)
                SoilKeying.NEW -> {
                    val newPass = requireNotNull(spec.newSoilPassphrase) {
                        "SoilKeying.NEW requires newSoilPassphrase"
                    }
                    if (key != null) SoilMigrator.rekeyInPlace(packaged, key, newPass)
                    else SoilMigrator.encryptInPlace(packaged, newPass)
                }
            }
        } catch (e: Exception) {
            // A half-transformed copy is worse than none — drop it before surfacing the failure.
            runCatching { packaged.delete() }
            throw e
        }

        onProgress(Phase.PACKAGING, 1, 1)
        return packaged
    }
}
