package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.ExportResult
import com.symmetricalpalmtree.notesproutsn.extension.ExportSpec
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.INotebookExporter

/**
 * The notebook **document** on arc 15's one `NOTEBOOK_EXPORTER` point (arc 19 / M9) — the third
 * service in this APK, beside [DocumentEditorService] and [TextImporterService], and no new
 * capability point: the third exporter joins `:ext-soil` and `:ext-pdf` on the point that already
 * existed. Bound stateless — one call per bind (`ExtensionBinder.call`), never a held binding —
 * because the operation is a single describe or a single export, not a showing.
 *
 * **The seam: the host assembles, the extension streams.** This service declares
 * [com.symmetricalpalmtree.notesproutsn.extension.ExporterContract.SOURCE_DOCUMENT], and the host
 * answers it by building the notebook's document into **final UTF-8 text bytes** in its own cache —
 * the notebook document when one exists, else the per-page documents in page order — and executing
 * the format choice *before* the fds are opened, so a plain-text export is stripped host-side
 * through the shared `:markdown` engine. What arrives on the read fd is therefore already the file
 * the user asked for, and this side is a **verbatim streamer** exactly like `NSE · Soil Export`:
 * no decode, no charset sniff, no formatting of its own. No `.soil`, no key, no path, no notebook id
 * ever reaches this process, and the only thing it can write to is the destination fd it was handed
 * — the writes-nothing-to-disk rule, kept to the letter.
 *
 * Because the output is a **copy and not a transform**, the verbatim `bytesWritten == streamBytes`
 * equality is this exporter's verification contract — the same one the soil exporter is held to, and
 * the reason the source-kind split matters at all: the PDF exporter's count can only be
 * corroborated, this one's can be *checked*.
 *
 * The bytes are the user's document and are **never** logged — counts and class names only.
 */
class DocumentExporterService : Service() {

    private val binder = object : INotebookExporter.Stub() {

        // No keying: the trio is `.soil`-specific and the device key is the host's business. The
        // descriptor itself lives in DocumentExporterDescriptor, where a JVM test pins its shape.
        override fun describe(): ExporterInfo {
            enforce()
            return DocumentExporterDescriptor.info()
        }

        /**
         * The export: a **streamed, verified copy** of the text the host assembled onto the
         * destination the host opened — [TextStreams.streamCopy], the same one the importer
         * delivers through, in the other direction.
         *
         * The whole method is one `try`/`finally` around the two descriptors: they are this
         * process's dups and are closed here whatever happens — success, refusal or crash — because
         * a leaked fd on an e-ink device outlives the call that made it. The caller check is
         * **inside** the try for exactly that reason: a `SecurityException` thrown above it would
         * leak both. (The streams below take ownership too; `ParcelFileDescriptor.close()` is a
         * no-op the second time.)
         *
         * **Only marshalable exceptions leave** ([SecurityException] / [IllegalArgumentException] /
         * [IllegalStateException]): anything else kills the transaction silently and the host reads
         * an empty reply as success. So every `IOException` is re-thrown as an
         * `IllegalStateException` whose message names the failure and no path.
         */
        override fun export(
            source: ParcelFileDescriptor?,
            destination: ParcelFileDescriptor?,
            spec: ExportSpec?,
        ): ExportResult {
            try {
                enforce()
                val src = source ?: throw IllegalArgumentException("no source descriptor")
                val dst = destination ?: throw IllegalArgumentException("no destination descriptor")
                // The spec must be there, but there is nothing in it for this side to act on: the
                // format choice was executed host-side before the stream was opened. An unknown key
                // is ignored rather than refused, which is the forward-compatible direction — a
                // newer host paired with this extension may send options a newer descriptor
                // declared. (The PDF exporter refuses instead, because its options are its own
                // work; this one has none.)
                spec ?: throw IllegalArgumentException("no export spec")
                return ExportResult(TextStreams.streamCopy(src, dst, TAG, "export"))
            } catch (e: SecurityException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                // Includes IOException and OutOfMemoryError: nothing but the three marshalable
                // shapes may cross, and the message carries a class name, never a payload.
                Log.w(TAG, "export failed: ${e.javaClass.simpleName}")
                throw IllegalStateException("export failed (${e.javaClass.simpleName})")
            } finally {
                runCatching { source?.close() }
                runCatching { destination?.close() }
            }
        }
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.HOST_PACKAGE)

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "DocumentExporter"
    }
}
