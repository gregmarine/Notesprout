package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.INotebookImporter
import com.symmetricalpalmtree.notesproutsn.extension.ImportResult
import com.symmetricalpalmtree.notesproutsn.extension.ImportSpec
import com.symmetricalpalmtree.notesproutsn.extension.ImporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ImporterInfo

/**
 * `.md` / `.markdown` / `.txt` on the `NOTEBOOK_IMPORTER` point (arc 19 / M8) — the second service
 * in this APK, beside [DocumentEditorService], and `:ext-soil`'s `SoilImporterService` in shape
 * down to the line: the two importers do **exactly** the same thing (stream the picked document
 * through the write fd) and differ only in what they say they accept.
 *
 * Bound stateless — one call per bind (`ExtensionBinder.call`), never a held binding — because the
 * operation is a single describe or a single delivery, not a showing.
 *
 * What the host does with the bytes afterwards is the descriptor's
 * [ImporterContract.RESULT_TEXT_DOCUMENT] tail: instead of the arc-16 `.soil` pipeline (probe,
 * unlock, re-key, manifest, placement) the host decodes them as UTF-8 and creates a new text
 * document. **That decision is the host's and so is the validation** — this service streams the
 * picked file **verbatim**: no decode, no charset sniff, no cap of its own. The bytes are as
 * untrusted here as a `.soil`'s, and recognising them is the job of the side that owns the data.
 *
 * The manifest declares **API version 3** for this service alone (per-service metadata; the editor
 * keeps 2): a version-2 host reads a descriptor with no result-kind tail, would take the absent
 * tail to mean `.soil`, and would run a Markdown file through the notebook probe. Requiring 3 is
 * what makes that pairing impossible rather than merely unlikely.
 */
class TextImporterService : Service() {

    private val binder = object : INotebookImporter.Stub() {

        override fun describe(): ImporterInfo {
            enforce()
            return ImporterInfo(
                formatLabel = "Text or Markdown",
                // What actually chooses this importer for a picked document: its display name's
                // extension. `.markdown` is listed beside `.md` because both are in the wild.
                fileExtensions = listOf("md", "markdown", "txt"),
                // What the host seeds its OPEN_DOCUMENT filter with. Providers label Markdown
                // files `text/plain` at least as often as `text/markdown`, so both are declared —
                // and the host adds a wildcard of its own regardless.
                mimeTypes = listOf("text/markdown", "text/plain"),
                resultKind = ImporterContract.RESULT_TEXT_DOCUMENT,
            )
        }

        /**
         * The delivery: a **streamed, verified copy** of the picked document into the host's cache
         * file — [TextStreams.streamCopy].
         *
         * The whole method is one `try`/`finally` around the two descriptors: they are this
         * process's dups and are closed here whatever happens — success, refusal or crash — because
         * a leaked fd on an e-ink device outlives the call that made it. (The streams below take
         * ownership too; `ParcelFileDescriptor.close()` is a no-op the second time.)
         *
         * **Only marshalable exceptions leave** ([SecurityException] / [IllegalArgumentException] /
         * [IllegalStateException]): anything else kills the transaction silently and the host reads
         * an empty reply as success. So every `IOException` is re-thrown as an
         * `IllegalStateException` whose message names the failure and no path.
         */
        override fun importDocument(
            source: ParcelFileDescriptor?,
            destination: ParcelFileDescriptor?,
            spec: ImportSpec?,
        ): ImportResult {
            try {
                // Inside the try (the E1 trap): a refused caller still owns two received dups, and
                // the finally is what closes them.
                enforce()
                val src = source ?: throw IllegalArgumentException("no source descriptor")
                val dst = destination ?: throw IllegalArgumentException("no destination descriptor")
                // The spec must be there (the AIDL carries it so a later version can grow options
                // without a new method), but this importer declares none — and an unknown key is
                // ignored rather than refused, which is the forward-compatible direction: a newer
                // host paired with this extension may send options a newer descriptor declared.
                spec ?: throw IllegalArgumentException("no import spec")
                return ImportResult(TextStreams.streamCopy(src, dst, TAG, "import"))
            } catch (e: SecurityException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                // Includes IOException and OutOfMemoryError: nothing but the three marshalable
                // shapes may cross, and the message carries a class name, never a payload.
                Log.w(TAG, "import failed: ${e.javaClass.simpleName}")
                throw IllegalStateException("import failed (${e.javaClass.simpleName})")
            } finally {
                runCatching { source?.close() }
                runCatching { destination?.close() }
            }
        }
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.HOST_PACKAGE)

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "TextImporter"
    }
}
