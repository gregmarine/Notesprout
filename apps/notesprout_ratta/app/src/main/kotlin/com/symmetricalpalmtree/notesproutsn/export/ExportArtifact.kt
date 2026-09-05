package com.symmetricalpalmtree.notesproutsn.export

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.crypto.KeyScope
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMetaStore
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * **The thing that gets exported** (arc 15 / E1): a cold, checkpointed copy of the notebook's
 * `.soil` in the host's own cache, ready to be streamed through a read fd.
 *
 * Everything that touches a key happens here, in the host, and the exporter never learns any of it
 * — that is the seam. The order below *is* the design:
 *
 *  1. **Open behind the family's guards** ([ExportOpen] — the file is there, the file is not held,
 *     there is a key, and the open is sealed in a `finally`), and inside them a **best-effort**
 *     `notebook_meta` refresh with `exportedAt` stamped: the file stays self-describing, and a meta
 *     that will not write is not worth failing an export over (og's upkeep rule).
 *  2. **Seal** — `PRAGMA wal_checkpoint(TRUNCATE)` then close, which is exactly what
 *     [SoilDatabase.seal] is, and which [ExportOpen] does on the way out. After it the whole
 *     notebook is in the main file.
 *  3. **Copy the main file only** into [freshDir], wiped and recreated per export. Never `-wal`,
 *     never `-shm`: after the checkpoint they hold nothing, and a copy that took them would export
 *     a second, stale story of the same data.
 *
 * **The Garden file is never mutated beyond the meta refresh.** Every transform (E2's keying) runs
 * on the cache copy, so a failure anywhere leaves the source byte-identical — and the cache copy is
 * never deleted on a *read* failure either: never-delete-on-corruption covers the temp too.
 */
object ExportArtifact {

    private const val TAG = "ExportArtifact"

    /** The cache subdirectory, wiped and recreated per export — og's `exported_notebooks` hygiene.
     *  **One directory for both source kinds** (arc 18 / D1): [ExportRender]'s page bundle lands
     *  here too, so the screen's single [clean] in its `finally` takes whichever artifact was made. */
    internal const val DIR = "export"

    /** Why a prepare could not produce an artifact. Each maps to one sentence on screen. */
    enum class Problem {
        /** A connection to this `.soil` is open in this process — never copy under a live writer. */
        IN_USE,

        /** No key session (the process was killed and nothing has unlocked since). */
        NO_KEY,

        /** This notebook has its own passphrase and nothing in this process has typed it
         *  (arc 26 / U4) — not a missing key, one notebook that is still shut. */
        LOCKED,

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
        /** The key the screen already holds (arc 26 / U4) — what the person typed for a
         *  `NOTEBOOK`-scope notebook. Null lets [ExportOpen] resolve it, which is right for every
         *  `GLOBAL` notebook and answers `LOCKED` for a notebook nobody has unlocked. */
        resolved: KeyResolver.Resolved? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        val source = soilFile(context, notebookId)
        // The stamp runs inside the open and the copy after it: the seal is what makes the whole
        // notebook be in the main file, so nothing may be copied while the connection still stands.
        val opened = ExportOpen.readOnly(context, notebookId, "export", resolved) { db ->
            stampExportedAt(db, notebookId, repo, appVersionCode)
        }
        if (opened is ExportOpen.Opened.Blocked) return@withContext Outcome.Failed(problemOf(opened.guard))
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

        val artifact = try {
            val out = File(freshDir(context), "$notebookId.soil")
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

    /** [DIR], emptied and remade — the hygiene every artifact writer shares (this copy,
     *  [ExportRender]'s bundle, [ExportText]'s text file, [DocumentPdfRender]'s bundle), so a
     *  previous export's leftovers can never travel with this one. Throws when the directory cannot
     *  be made: a write into a directory that is not there fails later and says less. */
    internal fun freshDir(context: Context): File {
        val dir = File(context.cacheDir, DIR)
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("could not create the export cache directory")
        return dir
    }

    /** The family's guards in this preparer's own words — the copy's reasons, one for one. */
    private fun problemOf(guard: ExportOpen.Guard): Problem = when (guard) {
        ExportOpen.Guard.MISSING -> Problem.MISSING
        ExportOpen.Guard.IN_USE -> Problem.IN_USE
        ExportOpen.Guard.NO_KEY -> Problem.NO_KEY
        ExportOpen.Guard.LOCKED -> Problem.LOCKED
        ExportOpen.Guard.UNREADABLE -> Problem.UNREADABLE
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
            // From the index row, never from `existing` — the same authority rule the
            // `textDocument` bit keeps below (og's meta-refresh-wipe trap).
            val scope = KeyScope.of(row.keyScope)
            val existing = NotebookMetaStore.read(db.raw())
            NotebookMetaStore.write(
                db.raw(),
                NotebookMeta(
                    notebookId = notebookId,
                    name = row.name,
                    createdAt = existing?.createdAt ?: row.createdAt,
                    updatedAt = row.updatedAt,
                    // Arc 26 / U4, decision 11: a `NOTEBOOK`-scope notebook has no cover anywhere
                    // — the index blob is nulled with the scope, and the meta must not carry one
                    // out of the app either.
                    cover = if (scope == KeyScope.NOTEBOOK) null else existing?.cover,
                    keyScope = row.keyScope ?: KEY_SCOPE_GLOBAL,
                    folderPath = repo.ancestry(row.parentId),
                    exportedAt = System.currentTimeMillis(),
                    appVersionCode = appVersionCode,
                    // From the index bit, never from `existing` (arc 19 / M2): the index is the
                    // authority and the meta field mirrors it, so carrying the previous meta
                    // forward is how the flag gets silently wiped (og's meta-refresh-wipe trap).
                    textDocument = ((row.flags ?: 0) and NotebookFlags.TEXT_DOCUMENT) != 0,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "meta refresh skipped", e)
        }
    }
}
