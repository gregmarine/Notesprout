package com.symmetricalpalmtree.notesproutsn.export

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMetaStore
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The thing that gets exported** (arc 15 / E1): a cold, checkpointed copy of the notebook's
 * `.soil` in the host's own cache, ready to be streamed through a read fd.
 *
 * Everything that touches a key happens here, in the host, and the exporter never learns any of it
 * — that is the seam. The order below *is* the design:
 *
 *  1. **Assert the file is not held.** One file, one connection. The library context means no
 *     notebook session has it open, but [SoilOpenFiles] is the door written down, so the code
 *     checks rather than assumes and a held file is a problem dialog, not a copy.
 *  2. **Transient open** through the one [SoilDatabase.open] door, and a **best-effort**
 *     `notebook_meta` refresh with `exportedAt` stamped — the file stays self-describing, and a
 *     meta that will not write is not worth failing an export over (og's upkeep rule).
 *  3. **Seal** — `PRAGMA wal_checkpoint(TRUNCATE)` then close, which is exactly what
 *     [SoilDatabase.seal] is. After it the whole notebook is in the main file.
 *  4. **Copy the main file only** into `cacheDir/export/`, wiped and recreated per export. Never
 *     `-wal`, never `-shm`: after the checkpoint they hold nothing, and a copy that took them
 *     would export a second, stale story of the same data.
 *
 * **The Garden file is never mutated beyond the meta refresh.** Every transform (E2's keying) runs
 * on the cache copy, so a failure anywhere leaves the source byte-identical — and the cache copy is
 * never deleted on a *read* failure either: never-delete-on-corruption covers the temp too.
 */
object ExportArtifact {

    private const val TAG = "ExportArtifact"

    /** The cache subdirectory, wiped and recreated per export — og's `exported_notebooks` hygiene. */
    private const val DIR = "export"

    /** Why a prepare could not produce an artifact. Each maps to one sentence on screen. */
    enum class Problem {
        /** A connection to this `.soil` is open in this process — never copy under a live writer. */
        IN_USE,

        /** No key session (the process was killed and nothing has unlocked since). */
        NO_KEY,

        /** The `.soil` is missing or empty — the index row outlived its file. */
        MISSING,

        /** The file would not open, or would not read (wrong key, damaged). */
        UNREADABLE,

        /** The cache copy failed or came out the wrong length — no room, or IO died mid-copy. */
        COPY_FAILED,
    }

    sealed class Outcome {
        /** [file] lives in the cache dir; [bytes] is what the destination must end up holding. */
        class Ready(val file: File, val bytes: Long) : Outcome()
        class Failed(val problem: Problem) : Outcome()
    }

    /**
     * Prepare [notebookId] for export. IO throughout; never touches the UI, never logs a name or a
     * path (a `.soil` filename is a UUID, which is an id, not a secret — but the parent path is
     * still never printed).
     */
    suspend fun prepare(
        context: Context,
        notebookId: String,
        repo: IndexRepository,
        appVersionCode: Int,
    ): Outcome = withContext(Dispatchers.IO) {
        val source = soilFile(context, notebookId)
        if (!source.exists() || source.length() == 0L) return@withContext Outcome.Failed(Problem.MISSING)
        if (SoilOpenFiles.isOpen(source)) {
            Log.w(TAG, "refusing to export a notebook that is open in this process")
            return@withContext Outcome.Failed(Problem.IN_USE)
        }
        val passphrase = KeySession.get() ?: return@withContext Outcome.Failed(Problem.NO_KEY)

        val db = try {
            SoilDatabase.open(context, notebookId, source, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "export open failed", e)
            return@withContext Outcome.Failed(Problem.UNREADABLE)
        }
        try {
            stampExportedAt(db, notebookId, repo, appVersionCode)
        } finally {
            // Checkpoint + close, always — an unsealed open would strand the connection and its WAL
            // sidecar for the process lifetime (the R6 lesson) and the copy would be of a file that
            // is still being written back into.
            db.seal(source)
        }
        // seal() swallows a failed checkpoint by contract (a claim must never be left standing),
        // so the copy re-checks the one thing the copy depends on: everything is in the main file.
        // A `-wal` still holding frames means the newest writes never made it across — a copy now
        // would pass every downstream check (the main file is self-consistent) and still be stale
        // (arc-15 review).
        val wal = File(source.path + "-wal")
        if (wal.exists() && wal.length() > 0L) {
            Log.w(TAG, "WAL not checkpointed (${wal.length()} bytes) — refusing a stale copy")
            return@withContext Outcome.Failed(Problem.COPY_FAILED)
        }

        val dir = File(context.cacheDir, DIR)
        val artifact = try {
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw java.io.IOException("could not create the export cache directory")
            val out = File(dir, "$notebookId.soil")
            source.copyTo(out, overwrite = true)
            out
        } catch (e: Exception) {
            Log.w(TAG, "export cache copy failed", e)
            return@withContext Outcome.Failed(Problem.COPY_FAILED)
        }
        // The source is cold and sealed, so the two lengths must agree; if they do not, the copy is
        // short and nothing downstream would notice — the exporter would stream a truncated file
        // and report a byte count that matched it.
        if (artifact.length() != source.length() || artifact.length() == 0L) {
            Log.w(TAG, "export copy is ${artifact.length()} of ${source.length()} bytes")
            return@withContext Outcome.Failed(Problem.COPY_FAILED)
        }
        Slog.d(TAG) { "prepared ${artifact.length()} bytes for export" }
        Outcome.Ready(artifact, artifact.length())
    }

    /** Wipe the cache dir. Called in a `finally` — the artifact is a copy of the user's notes and
     *  has no business outliving the export that made it. Best effort by design. */
    fun clean(context: Context) {
        runCatching { File(context.cacheDir, DIR).deleteRecursively() }
    }

    /**
     * The `notebook_meta` refresh — the same fields [com.symmetricalpalmtree.notesproutsn.notebook.NotebookSession.refreshMeta]
     * writes (name and folder ancestry from the index, so the file stays portable on its own), plus
     * `exportedAt`. **Best effort**: an index read or a meta write that fails is logged and the
     * export goes ahead with whatever the file already said about itself.
     */
    private suspend fun stampExportedAt(
        db: SoilDatabase,
        notebookId: String,
        repo: IndexRepository,
        appVersionCode: Int,
    ) {
        try {
            // The blob-free projection: the full row would drag the cover out of the encrypted
            // index only to discard it, and nothing here needs the pixels.
            val row = repo.summary(notebookId) ?: return
            val existing = NotebookMetaStore.read(db.raw())
            NotebookMetaStore.write(
                db.raw(),
                NotebookMeta(
                    notebookId = notebookId,
                    name = row.name,
                    createdAt = existing?.createdAt ?: row.createdAt,
                    updatedAt = row.updatedAt,
                    cover = existing?.cover,
                    folderPath = repo.ancestry(row.parentId),
                    exportedAt = System.currentTimeMillis(),
                    appVersionCode = appVersionCode,
                    textDocument = existing?.textDocument ?: false,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "meta refresh skipped", e)
        }
    }
}
