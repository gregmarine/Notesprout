package com.notesprout.android.crypto

import android.content.Context
import com.notesprout.android.core.Slog
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 4 bulk-convert sweep: encrypts every remaining plaintext notebook to GLOBAL scope under the
 * cached global passphrase, with crash-resumable state stored in PassphraseStore.
 *
 * A conversion never changes the global passphrase — it only encrypts plaintext .soil files that are
 * currently unencrypted and marks their index rows encrypted/GLOBAL.
 *
 * Idempotent + self-healing per notebook:
 *  - Plaintext file → encrypt in place, then mark the index row GLOBAL.
 *  - Already-encrypted file (a prior interrupted run, or a crash between encryptInPlace and the index
 *    write) → only reconcile the index row, and ONLY if the file actually opens with the global
 *    passphrase. A file encrypted under some other secret is left untouched and counted as skipped —
 *    never falsely relabelled GLOBAL (that is exactly what stalls rotation; see GlobalRotation).
 *  - One notebook failing never aborts the sweep; it is counted and the sweep continues.
 *
 * Cancel: stops after the current notebook completes; the marker keeps the remaining ids so the user
 * can resume from EncryptionSettingsActivity.
 */
object GlobalConversion {

    private const val TAG = "GlobalConversion"

    sealed class Result {
        /** [converted] were encrypted (or reconciled); [skipped] could not be encrypted and were left as-is. */
        data class Complete(val converted: Int, val skipped: Int = 0) : Result()
        data class Cancelled(val converted: Int, val remaining: Int, val skipped: Int = 0) : Result()
        data class Failed(val message: String) : Result()
    }

    fun hasMarker(context: Context): Boolean =
        PassphraseStore.getConversionPending(context) != null

    /** Starts a fresh sweep over every plaintext notebook. Writes the marker before touching a file. */
    suspend fun start(
        context: Context,
        repository: IndexRepository,
        globalPassphrase: String,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
        cancelSignal: AtomicBoolean = AtomicBoolean(false),
    ): Result = withContext(Dispatchers.IO) {
        val ids = repository.getPlaintextNotebookIds()
        PassphraseStore.setConversionPending(context, ids)
        if (ids.isEmpty()) {
            PassphraseStore.clearConversionMarker(context)
            return@withContext Result.Complete(0)
        }
        sweep(context, repository, ids.toMutableList(), globalPassphrase, onProgress, cancelSignal, ids.size)
    }

    /**
     * Resumes an interrupted sweep from the stored pending ids, using the cached global passphrase.
     * If the global passphrase is no longer cached ("Forget on This Device" mid-sweep), returns
     * Failed("no_cached_global") so the caller can prompt.
     */
    suspend fun resume(
        context: Context,
        repository: IndexRepository,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
        cancelSignal: AtomicBoolean = AtomicBoolean(false),
    ): Result = withContext(Dispatchers.IO) {
        val pending = PassphraseStore.getConversionPending(context)
            ?: return@withContext Result.Failed("No conversion in progress.")
        val globalPassphrase = PassphraseStore.getGlobalPassphrase(context)
            ?: return@withContext Result.Failed("no_cached_global")
        if (pending.isEmpty()) {
            PassphraseStore.clearConversionMarker(context)
            return@withContext Result.Complete(0)
        }
        sweep(context, repository, pending.toMutableList(), globalPassphrase, onProgress, cancelSignal, pending.size)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun sweep(
        context: Context,
        repository: IndexRepository,
        pending: MutableList<String>,
        globalPassphrase: String,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
        cancelSignal: AtomicBoolean,
        total: Int,
    ): Result {
        var converted = 0
        var skipped = 0
        var processed = 0
        val snapshot = pending.toList()

        for (id in snapshot) {
            if (cancelSignal.get()) {
                PassphraseStore.setConversionPending(context, pending)
                return Result.Cancelled(converted, pending.size, skipped)
            }

            val file = soilFile(context, id)
            when {
                !file.exists() -> {
                    // Deleted since the sweep started — nothing to convert.
                    Slog.d(TAG) { "Notebook $id missing — skipped." }
                }
                else -> try {
                    when (SoilCrypto.probe(file)) {
                        SoilFileKind.Plaintext -> {
                            SoilMigrator.encryptInPlace(file, globalPassphrase)
                            repository.setEncryptionState(id, encrypted = true, keyScope = KeyScope.GLOBAL)
                            converted++
                        }
                        SoilFileKind.Encrypted -> {
                            // Crash between encryptInPlace and the index write, OR a file that was
                            // already encrypted under another secret. Only claim GLOBAL if it truly
                            // opens with the global passphrase; otherwise leave it and skip.
                            if (SoilCrypto.verifyPassphrase(file, globalPassphrase)) {
                                repository.setEncryptionState(id, encrypted = true, keyScope = KeyScope.GLOBAL)
                                converted++
                                Slog.d(TAG) { "Notebook $id already global-encrypted — index reconciled." }
                            } else {
                                skipped++
                                Slog.d(TAG) { "Notebook $id encrypted under another secret — left as-is." }
                            }
                        }
                        SoilFileKind.Invalid -> {
                            skipped++
                            Slog.d(TAG) { "Notebook $id is not a valid database — skipped." }
                        }
                    }
                } catch (e: Exception) {
                    // One notebook failing must never stall the sweep.
                    skipped++
                    Slog.d(TAG) { "encryptInPlace failed for $id: ${e.message}" }
                }
            }

            pending.remove(id)
            PassphraseStore.setConversionPending(context, pending)
            processed++
            onProgress(processed, total)
        }

        PassphraseStore.clearConversionMarker(context)
        return Result.Complete(converted, skipped)
    }
}
