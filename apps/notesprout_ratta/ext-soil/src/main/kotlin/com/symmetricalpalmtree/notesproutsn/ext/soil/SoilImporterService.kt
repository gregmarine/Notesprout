package com.symmetricalpalmtree.notesproutsn.ext.soil

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.INotebookImporter
import com.symmetricalpalmtree.notesproutsn.extension.ImportResult
import com.symmetricalpalmtree.notesproutsn.extension.ImportSpec
import com.symmetricalpalmtree.notesproutsn.extension.ImporterInfo

/**
 * Notesprout SN's `NOTEBOOK_IMPORTER` point (arc 16 / I1) — [SoilExporterService]'s mirror, in the
 * same APK and under the same identity (**`NSE · Soil Export`** — the user's call: no rename; the
 * package serves both directions of one format).
 *
 * Bound stateless — one call per bind (`ExtensionBinder.call`), never a held binding — because the
 * operation is a single describe or a single delivery, not a showing.
 *
 * The seam is the exporter's, reversed: **the host keys, the extension delivers.** Everything that
 * touches a key — the probe, the unlock, the re-key to the device's global key, placement, the
 * remap, the Garden and index writes — runs host-side; this service receives a **read fd on the
 * user's picked document** and a **write fd on a host-owned cache file** and streams the bytes
 * across. No passphrase, no path, no SQLCipher ever crosses, and **this extension writes nothing to
 * disk of its own**: the only thing it can write to is the fd it was handed.
 *
 * The bytes it carries are untrusted — it does not probe them, and must not: recognising a `.soil`
 * is the host's job, after the copy, behind its own crypto.
 */
class SoilImporterService : Service() {

    private val binder = object : INotebookImporter.Stub() {

        override fun describe(): ImporterInfo {
            enforce()
            return ImporterInfo(
                formatLabel = "Notesprout notebook (.soil)",
                fileExtensions = listOf("soil"),
                // What the host seeds its OPEN_DOCUMENT filter with. A `.soil` has no registered
                // type, so providers hand it the generic stream one — and mislabel it often enough
                // that the host adds `*/*` of its own (og's filter).
                mimeTypes = listOf("application/octet-stream"),
            )
        }

        /**
         * The delivery: a **streamed, verified copy** of the picked document into the host's cache
         * file — [SoilStreams.streamCopy], literally the export's copy in the other direction: one
         * seam, one copy, shared.
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
                return ImportResult(SoilStreams.streamCopy(src, dst, TAG, "import"))
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
        const val TAG = "SoilImporter"
    }
}
