package com.symmetricalpalmtree.notesproutsn.export

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.data.soil.DocumentRepository
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
 * The guard order is [ExportRender]'s, one guard for one guard, because the same things are at
 * stake — a live writer, a missing key, a file that will not open:
 *
 *  1. **Assert the file is not held.** One file, one connection; [SoilOpenFiles] is the door
 *     written down, so this checks rather than assumes.
 *  2. **Read-only open** through the one [SoilDatabase.open] door. Nothing is written — not even
 *     `notebook_meta`'s `exportedAt`: a text file is not the notebook, and reading a document must
 *     not mutate what it reads.
 *  3. **Assemble** ([ExportDocumentRules.assemble]) and write the bytes into `cacheDir/export/` —
 *     the same directory [ExportArtifact] uses and [ExportArtifact.clean] wipes, so one `finally`
 *     in the screen takes whichever artifact was made.
 *  4. **Seal**, always, in a `finally` — an unsealed open strands the connection and its WAL
 *     sidecar for the process lifetime (the R6 lesson).
 *
 * **Export never recognizes.** A page with ink and no document contributes nothing; a notebook with
 * no document at all is [Problem.NO_DOCUMENT] — an honest refusal, never an empty file. (The screen
 * does not normally get here: such an exporter is not even listed, [ExportDocumentRules.listed].
 * This is the same answer from the other side of a document deleted under a standing screen.)
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

        /** Nothing has been written in this notebook — no notebook document and no page document.
         *  A document of nothing is not a document, so this is a refusal, never an empty file. */
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
        val source = soilFile(context, notebookId)
        if (!source.exists() || source.length() == 0L) return@withContext Outcome.Failed(Problem.MISSING)
        if (SoilOpenFiles.isOpen(source)) {
            Log.w(TAG, "refusing to read a notebook that is open in this process")
            return@withContext Outcome.Failed(Problem.IN_USE)
        }
        val passphrase = KeySession.get() ?: return@withContext Outcome.Failed(Problem.NO_KEY)

        val db = try {
            SoilDatabase.open(context, notebookId, source, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "document open failed", e)
            return@withContext Outcome.Failed(Problem.UNREADABLE)
        }
        try {
            val markdown = markdownOf(db, notebookId)
                ?: return@withContext Outcome.Failed(Problem.NO_DOCUMENT)
            write(context, notebookId, format, markdown)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class and message only — an IO failure's message can carry a path.
            Log.w(TAG, "document assembly failed: ${e.javaClass.simpleName}")
            Outcome.Failed(Problem.UNREADABLE)
        } finally {
            db.seal(source)
        }
    }

    /**
     * **The read both document exports share** — this one and [DocumentPdfRender]'s, which lays the
     * very same Markdown out on paper. One reader, so the two can never come to disagree about what
     * the document of a notebook is: the notebook document if there is one, else the page documents
     * in page order ([ExportDocumentRules.assemble] owns the rule, this owns the rows).
     *
     * Null when the notebook has nothing written in it at all.
     */
    internal suspend fun markdownOf(db: SoilDatabase, notebookId: String): String? {
        val dao = db.dao()
        val documents = DocumentRepository(db.documentDao(), dao)
        val pageDocs = dao.childrenOfType(notebookId, SoilSchema.TYPE_PAGE)
            .map { page -> documents.get(page.id)?.text }
        val markdown = ExportDocumentRules.assemble(documents.get(notebookId)?.text, pageDocs)
        Slog.d(TAG) { "assembled ${markdown?.length ?: 0} chars from ${pageDocs.size} page(s)" }
        return markdown
    }

    /** The cache write. [ExportRender]'s directory hygiene — wiped and recreated per export — and
     *  the extension the format asked for, so a `.txt` export is not a file called `.md` with the
     *  syntax taken out of it.
     *
     *  The suffix is derived from the choice rather than *being* it: [format] arrives from a
     *  descriptor, and a descriptor is untrusted input all the way down — a choice id carrying a
     *  path character would otherwise name a file outside the cache directory. */
    private fun write(context: Context, notebookId: String, format: String, markdown: String): Outcome {
        val dir = File(context.cacheDir, ExportArtifact.DIR)
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("could not create the export cache directory")
        val suffix = if (format == ExporterContract.TEXT_FORMAT_PLAIN) "txt" else "md"
        val out = File(dir, "$notebookId.$suffix")
        val bytes = ExportDocumentRules.finalText(markdown, format).toByteArray(Charsets.UTF_8)
        out.writeBytes(bytes)
        Slog.d(TAG) { "wrote ${bytes.size} bytes of $format" }
        return Outcome.Ready(out)
    }
}
