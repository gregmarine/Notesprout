package com.symmetricalpalmtree.notesproutsn.ext.pdf

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.ExportResult
import com.symmetricalpalmtree.notesproutsn.extension.ExportSpec
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.INotebookExporter

/**
 * Notesprout SN's second exporter on arc 15's one `NOTEBOOK_EXPORTER` point (arc 18 / D1) — no new
 * capability point, and none was needed. Bound stateless, one call per bind
 * (`ExtensionBinder.call`), never a held binding: the operation is a single describe or a single
 * export, not a showing.
 *
 * **The seam: the host renders, the extension assembles.** A PDF exporter can never receive the
 * `.soil` — no key crosses an extension seam — so this service declares
 * [ExporterContract.SOURCE_PAGES] and the host bakes every page full-fidelity into a
 * [com.symmetricalpalmtree.notesproutsn.extension.PageBundle] container in its own cache. That
 * container arriving on the read fd is **the only inbound**: no `.soil`, no key, no path, no
 * notebook id ever reaches this process, and the only thing this process can write to is the
 * destination fd it was handed — the writes-nothing-to-disk rule, kept to the letter. "What a
 * notebook is" therefore stays a host question: a future page kind renders host-side and this
 * exporter does not change.
 *
 * **The output is a transform, not a copy**, which is the one way this exporter differs from
 * `NSE · Soil Export` at the seam. A PDF's size is not the container's, so the verbatim
 * `bytesWritten == streamBytes` equality does not apply here and the host corroborates against the
 * destination's own answers instead. What this side owes is an **honest count**: what was actually
 * written to the destination stream, never a guess and never the container's length.
 *
 * **D1 declares no options.** Not-built controls do not exist (the J4 rule; the E1→E2 precedent for
 * a descriptor that flips when the host work lands): the page-template and password toggles are D2,
 * so until then a spec carrying either is a mismatch this exporter refuses rather than quietly
 * exporting a file that is not what was asked for — see [PdfExportSpec] for why that refusal points
 * the opposite way from the soil exporter's ignore-the-unknown rule.
 */
class PdfExporterService : Service() {

    private val binder = object : INotebookExporter.Stub() {

        override fun describe(): ExporterInfo {
            enforce()
            return ExporterInfo(
                formatLabel = "PDF document",
                fileExtension = "pdf",
                mimeType = "application/pdf",
                // No keying: the trio is `.soil`-specific and the device key is the host's business.
                // No options at all this phase — D2 lands the pair.
                options = emptyList(),
                sourceKind = ExporterContract.SOURCE_PAGES,
            )
        }

        /**
         * The export: a page bundle in, a PDF out, **one page in memory at a time** — decode page
         * N, draw it, finish it, recycle it, and only then read N+1. A whole notebook of full-size
         * bitmaps is an OOM on a 3 GB device, so the loop never holds two.
         *
         * The whole method is one `try`/`finally` around the two descriptors: they are this
         * process's dups and are closed here whatever happens — success, refusal or crash — because
         * a leaked fd on an e-ink device outlives the call that made it. The caller check is
         * **inside** the try for exactly that reason: a `SecurityException` thrown above it would
         * leak both. (The streams below take ownership too; closing a `ParcelFileDescriptor` twice
         * is a no-op.)
         *
         * **Only marshalable exceptions leave** ([SecurityException] / [IllegalArgumentException] /
         * [IllegalStateException]): anything else kills the transaction silently and the host reads
         * an empty reply as success. So every `IOException` becomes an `IllegalStateException`
         * naming the stage it failed in — never a path, never a page's content.
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
                val asked = spec ?: throw IllegalArgumentException("no export spec")
                // Refused before a single byte is read: an option or a secret this build cannot act
                // on would otherwise produce a file the user did not ask for.
                PdfExportSpec.require(asked.values, asked.exportSecret)
                return ExportResult(PdfAssembly.assemble(src, dst, TAG))
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
        const val TAG = "PdfExporter"
    }
}
