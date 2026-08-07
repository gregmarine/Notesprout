package com.notesprout.android

import android.content.Context
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.data.DocumentRepository
import com.notesprout.android.data.NotebookDao
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.recognition.HandwritingRecognizerProvider
import com.notesprout.android.recognition.MarkdownText
import com.notesprout.android.recognition.PageTextRecognizer
import com.notesprout.android.recognition.PageTextRepository
import java.io.File

/**
 * Exports a notebook's writing to a **Markdown** (`.md`, the default) or **plain-text** (`.txt`) file.
 *
 * Per page, a **document** wins when the page has one — the user's edited text is the finished version
 * of the same words (see docs/documents.md). Otherwise the page's handwriting is recognized through the
 * shared core ([PageTextRepository] → [PageTextRecognizer]); pages that already have a fresh cached
 * `page_text` (e.g. written by RTR) are reused, so a partially-recognized notebook exports fast.
 *
 * Text is always assembled as Markdown; the plain-text format strips the Markdown syntax at the
 * end via [MarkdownText]. See docs/handwriting-recognition.md § "Path 2 — export-only recognition".
 */
object NotebookTextExporter {

    enum class Format(val extension: String) { MARKDOWN("md"), PLAIN("txt") }

    /**
     * Export using an **already-open** [db] (the caller owns/closes it) — used from
     * [NotebookActivity], which holds the live connection to the encrypted `.soil`.
     */
    suspend fun export(
        context: Context,
        db: SoilDatabase,
        pageIds: List<String>?,
        notebookTitle: String,
        format: Format,
        onProgress: (current: Int, total: Int) -> Unit,
    ): File = writeFile(context, db.notebookDao(), pageIds, notebookTitle, format, onProgress)

    /**
     * Export by opening a **transient** Room connection for [soilPath] — used from MainActivity /
     * PageIndexActivity where the notebook is not already open. Does not checkpoint on close.
     */
    suspend fun exportFromPath(
        context: Context,
        soilPath: String,
        pageIds: List<String>?,
        notebookTitle: String,
        format: Format,
        onProgress: (current: Int, total: Int) -> Unit,
        passphrase: String? = null,
    ): File {
        val builder = SoilDatabase.builder(context, soilPath)
        if (passphrase != null) builder.openHelperFactory(SoilCrypto.roomFactory(passphrase))
        val db = builder.build()
        try {
            return writeFile(context, db.notebookDao(), pageIds, notebookTitle, format, onProgress)
        } finally {
            db.close()
        }
    }

    private suspend fun writeFile(
        context: Context,
        dao: NotebookDao,
        pageIds: List<String>?,
        notebookTitle: String,
        format: Format,
        onProgress: (current: Int, total: Int) -> Unit,
    ): File {
        val safeTitle = notebookTitle.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim('_', ' ')
            .ifBlank { "notebook" }

        // null pageIds = whole notebook, in display order.
        val ids = pageIds ?: dao.getPagesSorted().map { it.id }

        // A ready recognizer lets us compute missing/stale pages; without one we fall back to
        // whatever cached page_text already exists (uncached pages come out empty).
        val hwr = HandwritingRecognizerProvider.instance?.takeIf { it.isReady() }
        val recognizer = hwr?.let { PageTextRecognizer(it) }

        val total = ids.size
        val pageTexts = ArrayList<String>(total)
        for ((i, pageId) in ids.withIndex()) {
            onProgress(i + 1, total)
            // A page with a document exports the document: it is the same text carried one step
            // further, and it is what the user meant by "the writing on this page". Recognition is
            // skipped entirely for those pages — the draft has already served its purpose.
            val md = DocumentRepository.get(dao, pageId)?.text?.takeIf { it.isNotBlank() }
                ?: run {
                    val pageText = if (recognizer != null) {
                        PageTextRepository.freshOrRecognize(dao, pageId, recognizer)
                    } else {
                        PageTextRepository.getCached(dao, pageId)
                    }
                    pageText?.text?.takeIf { it.isNotBlank() }
                }
                ?: continue
            pageTexts += when (format) {
                Format.MARKDOWN -> md
                Format.PLAIN -> MarkdownText.toPlainText(md)
            }
        }

        // Pages flow into one document, separated by a blank line. Empty pages are dropped.
        val body = (pageTexts.joinToString("\n\n").trim() + "\n")

        val outDir = File(context.cacheDir, "exported_text").also { it.deleteRecursively(); it.mkdirs() }
        val outFile = File(outDir, "$safeTitle.${format.extension}")
        outFile.writeText(body)
        return outFile
    }
}
