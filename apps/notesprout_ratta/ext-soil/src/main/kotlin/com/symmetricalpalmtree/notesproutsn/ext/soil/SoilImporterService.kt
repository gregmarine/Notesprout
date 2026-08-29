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
         * file. The export's `streamCopy` in the other direction, and deliberately the same code
         * shape — one seam, one copy, two names.
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
                return ImportResult(streamCopy(src, dst))
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

    /**
     * Read [src] to the end, write every byte to [dst], and return the count — verified against the
     * source's own length where the fd will say, because **a short copy that reports its own short
     * count would read as a success** on both sides. The host verifies the same number again from
     * the outside; this is the inside half of that check.
     *
     * A picked document often arrives through a proxy fd that will not stat (a cloud provider, a
     * pipe): `statSize` is -1 then and there is nothing to compare against — the host's own
     * corroboration takes over, and the stream is accepted on its own terms rather than refused for
     * a number nobody can supply.
     *
     * The `fsync` before the close is what makes the returned count mean something durable: without
     * it the bytes may still be in a page cache when the host probes the file. The destination here
     * is always a host cache file, so a sync failure is unexpected — but it is still logged and
     * stepped over rather than failing a delivery that has already landed.
     */
    private fun streamCopy(src: ParcelFileDescriptor, dst: ParcelFileDescriptor): Long {
        var total = 0L
        ParcelFileDescriptor.AutoCloseInputStream(src).use { input ->
            val expected = src.statSize.takeIf { it >= 0L }
                ?: runCatching { input.channel.size() }.getOrNull()?.takeIf { it > 0L }
            ParcelFileDescriptor.AutoCloseOutputStream(dst).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    total += n
                }
                output.flush()
                try {
                    output.fd.sync()
                } catch (e: Exception) {
                    Log.w(TAG, "destination could not be synced: ${e.javaClass.simpleName}")
                }
            }
            if (expected != null && total != expected) {
                throw IllegalStateException("short import: $total of $expected bytes")
            }
        }
        return total
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.HOST_PACKAGE)

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "SoilImporter"

        /** One flash page-cluster's worth per hop — the size the family copies files at. */
        const val BUFFER_BYTES = 64 * 1024
    }
}
