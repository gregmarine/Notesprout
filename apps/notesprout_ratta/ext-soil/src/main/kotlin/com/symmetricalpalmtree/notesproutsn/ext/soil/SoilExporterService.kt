package com.symmetricalpalmtree.notesproutsn.ext.soil

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
import com.symmetricalpalmtree.notesproutsn.extension.OptionDescriptor

/**
 * Notesprout SN's `NOTEBOOK_EXPORTER` point (arc 15 / E1), implemented as the format-native
 * exporter: raw `.soil`. Bound stateless — one call per bind (`ExtensionBinder.call`), never a
 * held binding — because the operation is a single describe or a single export, not a showing.
 *
 * Every stub method proves the caller is the host before anything else. The seam is fds: the host
 * keys (checkpoint, keying transform, SAF destination) and this extension only streams a
 * host-prepared artifact to a host-granted destination — no passphrase, no path, no SQLCipher ever
 * crosses, and **this extension writes nothing to disk of its own**: the only thing it can write to
 * is the fd it was handed.
 *
 * A Keep export is therefore a **pure copy**, verified on both sides — which is what makes the
 * arc's proof `cmp` against the Garden file rather than an argument.
 */
class SoilExporterService : Service() {

    private val binder = object : INotebookExporter.Stub() {

        override fun describe(): ExporterInfo {
            enforce()
            return ExporterInfo(
                formatLabel = "Notesprout notebook (.soil)",
                fileExtension = "soil",
                mimeType = "application/octet-stream",
                options = listOf(
                    // The full trio (E2) — declared here, EXECUTED BY THE HOST: the reserved id is
                    // recognized host-side, the transform runs there, and a typed passphrase never
                    // reaches this process. Whatever was chosen, this exporter streams the artifact
                    // the host prepared.
                    OptionDescriptor(
                        id = ExporterContract.OPTION_KEYING,
                        label = "Encryption",
                        kind = ExporterContract.KIND_SINGLE_CHOICE,
                        choiceIds = listOf(
                            ExporterContract.KEYING_KEEP,
                            ExporterContract.KEYING_REKEY,
                            ExporterContract.KEYING_PLAIN,
                        ),
                        choiceLabels = listOf(
                            "Keep encrypted (this device's key)",
                            "New passphrase…",
                            "Remove encryption",
                        ),
                        defaultValue = ExporterContract.KEYING_KEEP,
                    ),
                ),
            )
        }

        /**
         * The export: a **streamed, verified copy** of the artifact the host prepared onto the
         * destination the host opened. A Keep export is a pure byte copy, which is exactly why it
         * is the one an arc can prove with `cmp`.
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
        override fun export(
            source: ParcelFileDescriptor?,
            destination: ParcelFileDescriptor?,
            spec: ExportSpec?,
        ): ExportResult {
            try {
                // Inside the try: a refused caller still owns two received dups, and the finally
                // is what closes them.
                enforce()
                val src = source ?: throw IllegalArgumentException("no source descriptor")
                val dst = destination ?: throw IllegalArgumentException("no destination descriptor")
                val values = (spec ?: throw IllegalArgumentException("no export spec")).values
                // A keying outside the declared trio was never offered, and a refusal here costs
                // the user a dialog rather than a file that is not what they asked for.
                SoilExportSpec.keying(values)
                return ExportResult(SoilStreams.streamCopy(src, dst, TAG, "export"))
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
        const val TAG = "SoilExporter"
    }
}
