package com.notesprout.android.crypto

import android.content.Context
import com.notesprout.android.core.Slog
import com.notesprout.android.data.hwr.HwrTrainingDatabase
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates a global passphrase rotation: re-keys every GLOBAL-scoped notebook from the old
 * passphrase to the new one, with crash-resumable state stored in PassphraseStore.
 *
 * The rotation is idempotent per notebook — if a notebook already opens with the new passphrase
 * (from a prior interrupted run), it is skipped. The cached global passphrase is updated only
 * after every notebook has been successfully re-keyed, so the window between start and finish
 * is the only time some notebooks require the new key and others the old.
 *
 * Cancel: stops after the current notebook completes. The marker keeps remaining IDs so the user
 * can resume from EncryptionSettingsActivity.
 */
object GlobalRotation {

    private const val TAG = "GlobalRotation"

    sealed class Result {
        data class Complete(val count: Int) : Result()
        data class Cancelled(val rotated: Int, val remaining: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    fun hasMarker(context: Context): Boolean =
        PassphraseStore.getRotationMarker(context) != null

    /**
     * Starts a fresh rotation for all GLOBAL notebooks.
     *
     * Writes the rotation marker (pendingIds + newPassphrase) before touching any file, so
     * a crash immediately after start is still resumable.
     */
    suspend fun start(
        context: Context,
        repository: IndexRepository,
        oldPassphrase: String,
        newPassphrase: String,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
        cancelSignal: AtomicBoolean = AtomicBoolean(false),
    ): Result = withContext(Dispatchers.IO) {
        val ids = repository.getGlobalNotebookIds()

        // Write the marker before touching any file so a crash here is recoverable.
        PassphraseStore.setRotationMarker(context, RotationMarker(ids, newPassphrase))

        if (ids.isEmpty()) {
            return@withContext try {
                finishRotation(context, oldPassphrase, newPassphrase)
                Result.Complete(0)
            } catch (e: Exception) {
                Result.Failed(e.message ?: "Failed to finalize rotation.")
            }
        }

        rotate(context, ids.toMutableList(), oldPassphrase, newPassphrase, onProgress, cancelSignal, ids.size)
    }

    /**
     * Resumes an interrupted rotation.
     *
     * The new passphrase comes from the stored marker (it was written to EncryptedSharedPreferences
     * when the rotation began). The old passphrase is still the cached global — we never call
     * setGlobalPassphrase(new) until the rotation fully completes.
     *
     * If the global passphrase is no longer cached (user ran "Forget on This Device" mid-rotation),
     * returns Failed so the caller can prompt and re-enter it.
     */
    suspend fun resume(
        context: Context,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
        cancelSignal: AtomicBoolean = AtomicBoolean(false),
    ): Result = withContext(Dispatchers.IO) {
        val marker = PassphraseStore.getRotationMarker(context)
            ?: return@withContext Result.Failed("No rotation in progress.")
        val oldPassphrase = PassphraseStore.getGlobalPassphrase(context)
            ?: return@withContext Result.Failed("no_cached_global")
        val pending = marker.pendingIds.toMutableList()
        rotate(context, pending, oldPassphrase, marker.newPassphrase, onProgress, cancelSignal, pending.size)
    }

    /**
     * Discards the rotation marker without finishing. Notebooks already re-keyed remain on the
     * new passphrase; the global cache is left unchanged (still the old passphrase). This leaves
     * the library in a mixed state — callers should warn the user before calling this.
     */
    fun abandonMarker(context: Context) {
        PassphraseStore.clearRotationMarker(context)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun rotate(
        context: Context,
        pending: MutableList<String>,
        oldPassphrase: String,
        newPassphrase: String,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
        cancelSignal: AtomicBoolean,
        total: Int,
    ): Result {
        var rotated = 0
        val snapshot = pending.toList()

        for (id in snapshot) {
            if (cancelSignal.get()) {
                PassphraseStore.updateRotationPending(context, pending)
                return Result.Cancelled(rotated, pending.size)
            }

            val file = soilFile(context, id)
            if (!file.exists()) {
                // If the main .soil is gone but a completed .rekey.tmp exists, the process was
                // killed between deleteSoilAndSidecars and renameTo — finish the rename.
                val tmp = File("${file.absolutePath}.rekey.tmp")
                if (tmp.exists() && SoilCrypto.verifyPassphrase(tmp, newPassphrase)) {
                    if (!tmp.renameTo(file)) {
                        return Result.Failed("Failed to recover re-keyed temp for notebook $id.")
                    }
                    Slog.d(TAG) { "Recovered orphaned rekey.tmp for $id." }
                    // Fall through — the idempotency check below will confirm and mark done.
                } else {
                    // Notebook genuinely deleted since rotation started — skip.
                    pending.remove(id)
                    PassphraseStore.updateRotationPending(context, pending)
                    rotated++
                    onProgress(rotated, total)
                    Slog.d(TAG) { "Notebook $id missing — skipped." }
                    continue
                }
            }

            // Idempotency: already re-keyed in a prior interrupted run.
            if (SoilCrypto.verifyPassphrase(file, newPassphrase)) {
                pending.remove(id)
                PassphraseStore.updateRotationPending(context, pending)
                rotated++
                onProgress(rotated, total)
                Slog.d(TAG) { "Notebook $id already uses new key — skipped." }
                continue
            }

            try {
                SoilMigrator.rekeyInPlace(file, oldPassphrase, newPassphrase)
            } catch (e: Exception) {
                // Leave remaining pending so the user can resume.
                PassphraseStore.updateRotationPending(context, pending)
                Slog.d(TAG) { "rekeyInPlace failed for $id: ${e.message}" }
                return Result.Failed("Re-key failed for notebook $id: ${e.message}")
            }

            pending.remove(id)
            PassphraseStore.updateRotationPending(context, pending)
            rotated++
            onProgress(rotated, total)
        }

        // All notebooks done — re-key the index + training store, commit the new global, clear marker.
        return try {
            finishRotation(context, oldPassphrase, newPassphrase)
            Result.Complete(rotated)
        } catch (e: Exception) {
            // Notebooks are re-keyed; the marker stays so the index/training step can be resumed.
            Result.Failed(e.message ?: "Failed to finalize rotation.")
        }
    }

    /**
     * Final, resumable step of every rotation: re-key the two non-notebook global files (the
     * encrypted index and the HWR training store) so the new passphrase is the single secret that
     * unlocks everything, then commit it as the cached global and clear the marker.
     *
     * Both re-keys are idempotent, so a crash that re-enters here (marker still present) is safe.
     * Ordered so the passphrase commit is last: if we crash after re-keying the index but before the
     * commit, the index still opens via its refreshed raw-key cache, and a resume finishes cleanly.
     */
    private suspend fun finishRotation(context: Context, oldPassphrase: String, newPassphrase: String) {
        NotesproutIndex.rekey(context, oldPassphrase, newPassphrase)
        HwrTrainingDatabase.rekey(context, oldPassphrase, newPassphrase)
        PassphraseStore.setGlobalPassphrase(context, newPassphrase)
        PassphraseStore.clearRotationMarker(context)
    }
}
