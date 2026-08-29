package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.os.ParcelFileDescriptor
import com.symmetricalpalmtree.notesproutsn.core.Slog

/**
 * Bind-per-operation client for one notebook importer (arc 16 / I1) — [ExporterClient]'s mirror,
 * over [ExtensionBinder.call] (signature re-check at bind, bind ≤ 3 s, call on IO under a
 * per-method timeout, unbind in `finally`, every failure → one [ExtensionCallException]). Stateless
 * point — no store, no held binding: an import delivery is one operation.
 *
 * **Inward is untrusted, and unmarshal is the validation**: a descriptor or result over the
 * [ImporterContract] caps fails in its parcelable constructor inside the call and surfaces as
 * [ExtensionCallException] — the caller drops that importer with a log line, never crashes.
 *
 * **fd lifecycle**: [importDocument] takes ownership of both descriptors and closes them in
 * `finally` once the transaction has been marshalled — success, failure, or timeout; the extension
 * closes its own dups on its side. The host verifies the byte count against what the source
 * provider will say before it probes a byte of the result — that check is the caller's, on the
 * [ImportResult] this returns.
 *
 * Logs (tag [TAG]): bind/unbind, byte counts and durations — never a name, never a path.
 */
class ImporterClient(context: Context, private val ref: ProviderRef) {

    private val appContext = context.applicationContext

    /** The importer's descriptor. Fast; a malformed one throws [ExtensionCallException]. */
    suspend fun describe(): ImporterInfo =
        call(ImporterContract.DESCRIBE_TIMEOUT_MS) { it.describe() }

    /**
     * Run the delivery: [source] (the user's picked document, opened read-only) streamed to
     * [destination] (the host's cache file, opened for writing). Both descriptors are closed here in
     * `finally`. Returns the extension's byte count — corroborate it before probing.
     */
    suspend fun importDocument(
        source: ParcelFileDescriptor,
        destination: ParcelFileDescriptor,
        spec: ImportSpec,
    ): ImportResult {
        val t0 = System.currentTimeMillis()
        try {
            val result = call(ImporterContract.IMPORT_TIMEOUT_MS) {
                it.importDocument(source, destination, spec)
            }
            Slog.d(TAG) { "import: ${result.bytesWritten} bytes in ${System.currentTimeMillis() - t0} ms" }
            return result
        } finally {
            runCatching { source.close() }
            runCatching { destination.close() }
        }
    }

    private suspend fun <T> call(timeoutMs: Long, block: (INotebookImporter) -> T): T =
        ExtensionBinder.call(
            appContext, ref, ImporterContract.ACTION_NOTEBOOK_IMPORTER, TAG,
            asInterface = { INotebookImporter.Stub.asInterface(it) },
            callTimeoutMs = timeoutMs,
            block = block,
        )

    companion object {
        private const val TAG = "ImporterClient"
    }
}
