package com.symmetricalpalmtree.notesproutsn.export

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.DocumentDao
import com.symmetricalpalmtree.notesproutsn.data.soil.DocumentRepository
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The third thing that gets exported** (arc 19 / M9): the notebook's *document* — the authored
 * Markdown — assembled into one final UTF-8 text file, which is what a
 * [com.symmetricalpalmtree.notesproutsn.extension.ExporterContract.SOURCE_DOCUMENT] exporter
 * receives through its read fd in place of the `.soil` or a page bundle.
 *
 * **Final** is the whole seam here. The host does the assembly *and* the format strip, so the
 * extension is a byte-for-byte copier — which is exactly why [ExportVerification] holds this source
 * kind to the same verbatim equality the soil path answers, and why the reserved
 * [com.symmetricalpalmtree.notesproutsn.extension.ExporterContract.OPTION_TEXT_FORMAT] choice is
 * host-executed twice over (the bytes here, the destination's name in [ExportDocumentRules]).
 *
 * The guards are [ExportOpen]'s — the family's one door, in the order that *is* the invariant (the
 * file is there, the file is not held, there is a key, the open is sealed in a `finally`), because
 * the same things are at stake here as in [ExportRender]: a live writer, a missing key, a file that
 * will not open. What is this file's own:
 *
 *  1. **Nothing is written** — not even `notebook_meta`'s `exportedAt`: a text file is not the
 *     notebook, and reading a document must not mutate what it reads.
 *  2. **Assemble** ([ExportDocumentRules.assemble]) and write the bytes into
 *     [ExportArtifact.freshDir] — the same directory the soil copy uses and [ExportArtifact.clean]
 *     wipes, so one `finally` in the screen takes whichever artifact was made.
 *
 * **Export never recognizes.** A page with ink and no document contributes nothing; a notebook with
 * no document at all is [Problem.NO_DOCUMENT] — an honest refusal, never an empty file. (The screen
 * does not normally get here: such an exporter is not even listed, [ExportDocumentRules.listed].
 * This is the same answer from the other side of a document deleted under a standing screen.)
 *
 * **And never an empty file at the far end of the strip either.** A document that is all syntax and
 * no words — `---` on its own — has a document to export in Markdown and *nothing* to export as
 * plain text, so the `.txt` branch refuses with the same [Problem.NO_DOCUMENT] rather than writing
 * the 0 bytes the strip left ([ExportDocumentRules.finalText] answers null for it). The verbatim
 * check downstream would have passed such a file happily: 0 streamed, 0 written.
 *
 * Document text is never logged — lengths only, like every other user-content path in this app.
 */
object ExportText {

    private const val TAG = "ExportText"

    /** Why an assembly could not produce a file. Each maps to one sentence on screen. */
    enum class Problem {
        /** A connection to this `.soil` is open in this process — never read under a live writer. */
        IN_USE,

        /** No key session (the process was killed and nothing has unlocked since). */
        NO_KEY,

        /** The `.soil` is missing or empty — the index row outlived its file. */
        MISSING,

        /** The file would not open, would not read, or the bytes would not be written. */
        UNREADABLE,

        /** Nothing has been written in this notebook — no notebook document and no page document,
         *  **or** a document the plain-text strip leaves empty (all syntax, no words). A document of
         *  nothing is not a document, so this is a refusal, never an empty file. */
        NO_DOCUMENT,
    }

    sealed class Outcome {
        /** [file] lives in the cache dir; the screen takes its length for the verbatim check. */
        class Ready(val file: File) : Outcome()
        class Failed(val problem: Problem) : Outcome()
    }

    /**
     * Assemble [notebookId]'s document into a file the exporter can stream, in [format] — one of
     * the reserved [com.symmetricalpalmtree.notesproutsn.extension.ExporterContract]
     * `TEXT_FORMAT_*` ids. IO throughout; never touches the UI, never logs a name, a path or a
     * line of the document.
     */
    suspend fun assemble(
        context: Context,
        notebookId: String,
        format: String,
    ): Outcome = withContext(Dispatchers.IO) {
        // The assembly's own failures are caught inside the open, not around it: they mean the
        // *write* failed, which is a different sentence from the file not opening — and the seal
        // still runs.
        val opened = ExportOpen.readOnly(context, notebookId, "assemble") { db ->
            try {
                val markdown = markdownOf(db, notebookId)
                if (markdown == null) Outcome.Failed(Problem.NO_DOCUMENT)
                else write(context, notebookId, format, markdown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class and message only — an IO failure's message can carry a path.
                Log.w(TAG, "document assembly failed: ${e.javaClass.simpleName}")
                Outcome.Failed(Problem.UNREADABLE)
            }
        }
        when (opened) {
            is ExportOpen.Opened.Read -> opened.value
            is ExportOpen.Opened.Blocked -> Outcome.Failed(problemOf(opened.guard))
        }
    }

    /** The family's guards in this assembly's own words — the text file's reasons, one for one. */
    private fun problemOf(guard: ExportOpen.Guard): Problem = when (guard) {
        ExportOpen.Guard.MISSING -> Problem.MISSING
        ExportOpen.Guard.IN_USE -> Problem.IN_USE
        ExportOpen.Guard.NO_KEY -> Problem.NO_KEY
        ExportOpen.Guard.UNREADABLE -> Problem.UNREADABLE
    }

    /**
     * **The read both document exports share** — this one and [DocumentPdfRender]'s, which lays the
     * very same Markdown out on paper. One reader, so the two can never come to disagree about what
     * the document of a notebook is: the notebook document if there is one, else the page documents
     * in page order ([ExportDocumentRules.assemble] owns the rule, this owns the rows).
     *
     * Null when the notebook has nothing written in it at all.
     *
     * The page documents arrive in **one** read ([DocumentDao.pageDocumentsIn]) rather than a SELECT
     * per page: an export of a long notebook is a long enough silence already, and every one of
     * those reads was a round trip into an encrypted file for one row.
     */
    internal suspend fun markdownOf(db: SoilDatabase, notebookId: String): String? {
        val dao = db.dao()
        val documentDao = db.documentDao()
        // Keyed by page, first row wins — `documentFor`'s `LIMIT 1`, which is a cap on damage: the
        // repository never inserts a second row for one parent, but a foreign writer could have.
        val byPage = HashMap<String, SoilObjectEntity>()
        for (row in documentDao.pageDocumentsIn(notebookId)) byPage.getOrPut(row.parentId) { row }
        val pageDocs = dao.childrenOfType(notebookId, SoilSchema.TYPE_PAGE)
            // Blank means absent — the repository's read rule, kept by hand because the batch read
            // goes round it (and [ExportDocumentRules.assemble] would drop the blanks regardless).
            .map { page -> byPage[page.id]?.text?.takeIf { it.isNotBlank() } }
        // The notebook document is still the repository's own read: one row, one parent, and the
        // blank rule applied where it is written down.
        val notebookDoc = DocumentRepository(documentDao, dao).get(notebookId)?.text
        val markdown = ExportDocumentRules.assemble(notebookDoc, pageDocs)
        Slog.d(TAG) { "assembled ${markdown?.length ?: 0} chars from ${pageDocs.size} page(s)" }
        return markdown
    }

    /** The cache write. The family's directory hygiene ([ExportArtifact.freshDir] — wiped and
     *  recreated per export) and the extension the format asked for, so a `.txt` export is not a
     *  file called `.md` with the syntax taken out of it.
     *
     *  **The strip is asked before the file is made**: a document that flattens to nothing is
     *  [Problem.NO_DOCUMENT], not a 0-byte `.txt` under a success dialog (the class doc's rule).
     *
     *  The suffix is derived from the choice rather than *being* it: [format] arrives from a
     *  descriptor, and a descriptor is untrusted input all the way down — a choice id carrying a
     *  path character would otherwise name a file outside the cache directory. */
    private fun write(context: Context, notebookId: String, format: String, markdown: String): Outcome {
        val text = ExportDocumentRules.finalText(markdown, format)
            ?: return Outcome.Failed(Problem.NO_DOCUMENT)
        val suffix = if (format == ExporterContract.TEXT_FORMAT_PLAIN) "txt" else "md"
        val out = File(ExportArtifact.freshDir(context), "$notebookId.$suffix")
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeBytes(bytes)
        Slog.d(TAG) { "wrote ${bytes.size} bytes of $format" }
        return Outcome.Ready(out)
    }
}
