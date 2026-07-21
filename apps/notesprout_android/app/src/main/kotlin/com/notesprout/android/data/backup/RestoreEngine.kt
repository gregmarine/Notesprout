package com.notesprout.android.data.backup

import android.content.Context
import android.util.Log
import com.notesprout.android.crypto.KeyMaterial
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.crypto.PassphraseStore
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.crypto.SoilFileKind
import com.notesprout.android.data.index.NotesproutIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Restores a backup into the app, REPLACING the entire current library.
 *
 * Safety model — the live library is never in a state with zero intact copies:
 *  1. Fetch into a cache staging dir. Any single file failure aborts (nothing live touched).
 *  2. Validate staging: the index and every `.soil` must at least probe as a database.
 *  3. Free-space check — the swap needs the staged set to exist twice, transiently.
 *  4. Move (rename) the live index + Garden aside, copy the staged Garden in, then install the
 *     staged index last via `.part` + rename. The index is the commit marker: while it's absent,
 *     [recoverInterrupted] rolls the aside copy back on next launch.
 *  5. Only after the index is in place: clear cached keys (so the next launch lands in
 *     NEEDS_UNLOCK for the backup's passphrase) and delete the aside copy.
 *
 * Any failure before step 4 leaves the live library open and untouched; a failure inside step 4
 * rolls the aside copy back and reopens the index, so the app keeps working without a restart.
 */
object RestoreEngine {

    private const val TAG = "RestoreEngine"
    private const val ASIDE_DIR = "restore_replaced"
    private const val INDEX_NAME = "notesprout.db"

    /** Headroom on top of the staged payload for WAL growth, journal files, etc. */
    private const val FREE_SPACE_HEADROOM = 64L * 1024 * 1024

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
        val extDir = app.getExternalFilesDir(null)
            ?: return@withContext Result.Failed("No storage available.")
        val aside = File(extDir, ASIDE_DIR)
        var movedAside = false
        var committed = false
        try {
            staging.deleteRecursively(); staging.mkdirs()
            val stagedIndex = File(staging, INDEX_NAME)
            val stagedGarden = File(staging, "Garden").apply { mkdirs() }

            // 1. Fetch into staging — any failure throws; the live library is untouched.
            val count = source.fetchInto(deviceIndex, stagedIndex, stagedGarden, onProgress)

            // 2. Validate the staged set before touching anything live. probe() rejects missing,
            // empty, and not-a-database files; encrypted files pass (they can't be read deeper
            // without the backup's key, which the post-restart unlock flow will collect).
            if (SoilCrypto.probe(stagedIndex) == SoilFileKind.Invalid) {
                return@withContext Result.Failed("The backup index could not be read.")
            }
            val badSoil = stagedGarden.listFiles()
                ?.filter { it.name.endsWith(".soil") } // -wal sidecars aren't databases; skip them
                ?.firstOrNull { SoilCrypto.probe(it) == SoilFileKind.Invalid }
            if (badSoil != null) {
                return@withContext Result.Failed("\"${badSoil.name}\" in the backup is not a readable notebook.")
            }

            // 3. Free-space check (hard fail): committing copies the staged set onto the library
            // volume while the old library still exists aside.
            val stagedBytes = staging.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val needed = stagedBytes + FREE_SPACE_HEADROOM
            val usable = extDir.usableSpace
            if (usable < needed) {
                val shortMb = (needed - usable) / (1024 * 1024) + 1
                return@withContext Result.Failed(
                    "Not enough free space to restore safely — free up about $shortMb MB and try again."
                )
            }

            // 4. Commit. Close the live index, move the live library aside (renames — no copies),
            // copy the staged Garden in, and install the staged index last as the commit marker.
            try {
                NotesproutIndex.seal()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "seal before restore failed (continuing): ${e.message}")
            }

            aside.deleteRecursively(); aside.mkdirs()
            val asideGarden = File(aside, "Garden").apply { mkdirs() }
            movedAside = true
            extDir.listFiles { f -> f.isFile && f.name.startsWith(INDEX_NAME) }?.forEach { f ->
                if (!f.renameTo(File(aside, f.name))) throw IOException("Could not set ${f.name} aside.")
            }
            val garden = File(extDir, "Garden")
            garden.listFiles()?.forEach { f ->
                if (!f.renameTo(File(asideGarden, f.name))) throw IOException("Could not set ${f.name} aside.")
            }
            garden.mkdirs()

            stagedGarden.listFiles()?.forEach { it.copyTo(File(garden, it.name), overwrite = true) }
            val liveIndex = File(extDir, INDEX_NAME)
            val indexPart = File("${liveIndex.absolutePath}.part")
            stagedIndex.copyTo(indexPart, overwrite = true)
            runCatching { java.io.FileInputStream(indexPart).use { it.fd.sync() } }
            if (!indexPart.renameTo(liveIndex)) throw IOException("Could not install the restored index.")
            committed = true

            // 5. The restored library is fully in place — drop all key state so the next launch
            // lands in NEEDS_UNLOCK for the backup's passphrase, then discard the old library.
            PassphraseStore.clearGlobalPassphrase(app)
            KeyMaterial.clearAll(app)
            KeySession.clear()
            aside.deleteRecursively()

            Result.Success(count)
        } catch (e: CancellationException) {
            if (movedAside && !committed) rollBack(extDir, aside)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "restore failed", e)
            if (movedAside && !committed) {
                rollBack(extDir, aside)
                // The index was sealed for the swap — reopen it so the app keeps working
                // (keys were not cleared on this path, so this succeeds without a prompt).
                runCatching { NotesproutIndex.ensureReady(app) }
            }
            Result.Failed(e.message ?: "Restore failed.")
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Launch-time repair for a restore killed mid-commit. The installed index is the commit
     * marker: aside copy present + no live index ⇒ the swap never completed — put the old
     * library back. Aside copy present + live index present ⇒ the commit finished but the
     * success-path cleanup didn't — the aside copy is the replaced (discarded) library.
     */
    fun recoverInterrupted(context: Context) {
        val extDir = context.applicationContext.getExternalFilesDir(null) ?: return
        val aside = File(extDir, ASIDE_DIR)
        if (!aside.exists()) return
        val liveIndex = File(extDir, INDEX_NAME)
        if (File(aside, INDEX_NAME).exists() && !liveIndex.exists()) {
            Log.w(TAG, "Restore was interrupted mid-commit — rolling the previous library back")
            rollBack(extDir, aside)
        } else {
            aside.deleteRecursively()
        }
        // A `.part` index from the interrupted install, if any, is stale either way.
        File("${liveIndex.absolutePath}.part").delete()
    }

    /** Move the aside (old) library back into place, replacing any partially-installed files. */
    private fun rollBack(extDir: File, aside: File) {
        runCatching {
            val garden = File(extDir, "Garden").apply { mkdirs() }
            File(aside, "Garden").listFiles()?.forEach { f ->
                val dest = File(garden, f.name)
                dest.delete()
                f.renameTo(dest)
            }
            aside.listFiles { f -> f.isFile }?.forEach { f ->
                val dest = File(extDir, f.name)
                dest.delete()
                f.renameTo(dest)
            }
            aside.deleteRecursively()
        }.onFailure { Log.e(TAG, "rollback failed — old library remains in $ASIDE_DIR", it) }
    }
}
