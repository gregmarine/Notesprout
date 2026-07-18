package com.notesprout.android.data.backup

import android.content.Context
import com.notesprout.android.crypto.KeyMaterial
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.crypto.PassphraseStore
import com.notesprout.android.data.index.NotesproutIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Restores a backup into the app, REPLACING the entire current library.
 *
 * Staged first: the backup is fetched into a cache dir, so a network/IO failure leaves the live
 * library untouched. Only after the fetch succeeds do we seal the index, clear the cached global +
 * raw keys, wipe the current Garden + index, and move the staged files into place. The restored index
 * is encrypted under the backup device's global passphrase, so the next launch lands in NEEDS_UNLOCK —
 * the caller restarts into the bootstrap gate to prompt for the recovery key.
 */
object RestoreEngine {

    sealed class Result {
        data class Success(val notebookCount: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun restore(
        context: Context,
        source: RestoreSource,
        deviceIndex: Int,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val staging = File(app.cacheDir, "restore_staging")
        try {
            staging.deleteRecursively(); staging.mkdirs()
            val stagedIndex = File(staging, "notesprout.db")
            val stagedGarden = File(staging, "Garden").apply { mkdirs() }

            // 1. Fetch into staging — the live library is still intact if this throws.
            val count = source.fetchInto(deviceIndex, stagedIndex, stagedGarden, onProgress)
            if (!stagedIndex.exists() || stagedIndex.length() == 0L) {
                return@withContext Result.Failed("The backup index could not be read.")
            }

            // 2. Commit: close the live index, drop all key state, wipe the current library.
            runCatching { NotesproutIndex.seal() }
            PassphraseStore.clearGlobalPassphrase(app)
            KeyMaterial.clearAll(app)
            KeySession.clear()

            val extDir = app.getExternalFilesDir(null) ?: return@withContext Result.Failed("No storage available.")
            extDir.listFiles { f -> f.name.startsWith("notesprout.db") }?.forEach { it.delete() }
            val garden = File(extDir, "Garden").apply { mkdirs() }
            garden.listFiles()?.forEach { it.delete() }

            // 3. Move staged files into place.
            stagedIndex.copyTo(File(extDir, "notesprout.db"), overwrite = true)
            stagedGarden.listFiles()?.forEach { it.copyTo(File(garden, it.name), overwrite = true) }

            Result.Success(count)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Restore failed.")
        } finally {
            staging.deleteRecursively()
        }
    }
}
