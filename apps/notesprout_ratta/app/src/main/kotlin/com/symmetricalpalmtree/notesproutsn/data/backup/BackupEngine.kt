package com.symmetricalpalmtree.notesproutsn.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.BuildConfig
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.crypto.SoilFileKind
import com.symmetricalpalmtree.notesproutsn.data.extensionStoreFiles
import com.symmetricalpalmtree.notesproutsn.data.extensionStorePackage
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex
import com.symmetricalpalmtree.notesproutsn.data.indexFile
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilCompactor
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One manual backup run (arc 17 / K2) — og's engine reshaped for SN's single LOCAL destination.
 * **The order is the design** (og D9, index last):
 *
 *  1. Resolve the destination — the persisted SAF tree, plus `dev/` in a debug build (debug and
 *     release coexist on the Nomad and must not share a root). Fail-fast with a problem the
 *     screen can name.
 *  2. Build the work list ([BackupPredicates.workList]) over every alive notebook and the stamp
 *     map — og's D8: no stamp or edited since; excluded and up-to-date are counted, not visited.
 *  3. Per notebook: skip a [SoilOpenFiles]-held file (never copy under a live writer — the arc-15
 *     lesson, **counted** so the summary is honest); compact it through the one open door
 *     ([SoilCompactor], the K1 pass — best effort, a file that will not open is still copied as
 *     the bytes it is, og's rule); copy the `.soil` atomically; a **non-empty** WAL is copied
 *     alongside and both must land before the stamp, while an absorbed WAL deletes the stale
 *     destination sidecar (a fresh `.soil` + an old `-wal` corrupts on restore); then stamp —
 *     per success, immediately, with the `updatedAt` the work list read (a failed copy never
 *     stamps and retries next run).
 *  4. **Every extension store** (arc 21 / W5): each `Garden/<pkg>.db`, checkpointed if this process
 *     holds it open, then copied through the same snapshot-and-probe path the index takes. **Every
 *     pass, unconditionally** — there is no stamp bookkeeping for them: a store is small, it has no
 *     `updatedAt` to compare against (its edits are an extension's, not the library's), and inventing
 *     a clock for one would be a second answer that can disagree with the file.
 *  5. The index, last, after every stamp: purge ([SnIndex.compactIfNeeded]) + checkpoint, then
 *     **snapshot to a local temp and probe it** before streaming — a torn copy of the live index
 *     is worse than no backup; only a failed snapshot falls back to streaming the live file. The
 *     WAL-alongside rule applies here too: a non-empty post-checkpoint index `-wal` travels with
 *     the snapshot, both landing before the copy counts (K3 review).
 *  6. `lastRunAt` — only when at least one destination write succeeded — and the stamp map pruned
 *     of purged notebooks.
 *
 * Compaction, stamps and the run itself never bump a notebook's `updatedAt` (og's rule — it is
 * the needs-backup flag; a bump would re-flag the file just backed up).
 *
 * The screen owns the one-run-at-a-time guard and every dialog; this object is headless IO and
 * never throws — every failure becomes a count or a [Problem].
 */
object BackupEngine {

    private const val TAG = "BackupEngine"

    /** The cache subdirectory holding the index snapshot, wiped per run. */
    private const val DIR = "backup"

    /** Why a run could not start at all. Each maps to one sentence on screen. */
    enum class Problem {
        /** No backup folder has been chosen yet. */
        NO_FOLDER,

        /** The chosen folder no longer resolves — deleted, ejected, or the grant was revoked. */
        FOLDER_GONE,

        /** No key session (the process was killed and nothing has unlocked since). */
        NO_KEY,
    }

    /** What a finished run did — the honest per-count summary the dialog renders. */
    data class Result(
        val problem: Problem? = null,
        /** Notebooks copied and stamped. */
        val copied: Int = 0,
        /** Notebooks whose stamp already covers their `updatedAt`. */
        val upToDate: Int = 0,
        /** Notebooks carrying the exclude flag. */
        val excluded: Int = 0,
        /** Notebooks skipped because their `.soil` is open in this process. */
        val held: Int = 0,
        /** Index rows whose `.soil` is missing from the Garden — skipped, not failed. */
        val missing: Int = 0,
        /** Copies that failed and did not stamp (they retry next run). */
        val failed: Int = 0,
        /** Extension stores copied this run (arc 21 / W5 — every one, every pass). */
        val storesCopied: Int = 0,
        /** Extension stores whose copy did not land; the run reports it and retries next pass. */
        val storesFailed: Int = 0,
        val indexCopied: Boolean = false,
    ) {
        /** og D9 step 8: at least one destination write landed. */
        val succeeded: Boolean get() = copied > 0 || storesCopied > 0 || indexCopied
    }

    data class Progress(val done: Int, val total: Int)

    suspend fun run(
        context: Context,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        try {
            runInner(context.applicationContext, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The never-throws contract, held at the top (K3 review): anything that still escapes
            // the guarded steps — disk full inside a Room write, most likely — becomes a failed
            // count the dialog can report instead of an app crash under the progress dialog.
            Log.e(TAG, "backup run failed", e)
            Result(failed = 1)
        }
    }

    private suspend fun runInner(
        app: Context,
        onProgress: (Progress) -> Unit,
    ): Result {
        val store = BackupStore()
        val repo = IndexRepository()

        var config = store.read()
        val treeUri = config.treeUri ?: return Result(problem = Problem.NO_FOLDER)
        KeySession.get() ?: return Result(problem = Problem.NO_KEY)

        val writer = SafBackupWriter(app.contentResolver, Uri.parse(treeUri))
        val root = writer.root() ?: return Result(problem = Problem.FOLDER_GONE)
        val dest = if (BuildConfig.DEBUG) {
            writer.ensureDir(root, BackupPredicates.DEV_SUBDIR)
                ?: return Result(problem = Problem.FOLDER_GONE)
        } else root

        val notebooks = repo.allNotebooks()
        val work = BackupPredicates.workList(
            notebooks.map { BackupPredicates.Candidate(it.id, it.updatedAt, it.flags) },
            config.stamps,
        )
        val stores = extensionStoreFiles(app)
        val total = work.toCopy.size + stores.size + 1 // the index is the last unit of progress
        var done = 0
        onProgress(Progress(done, total))

        var copied = 0
        var held = 0
        var missing = 0
        var failed = 0
        for (candidate in work.toCopy) {
            val source = soilFile(app, candidate.id)
            when {
                !source.exists() || source.length() == 0L -> missing++
                SoilOpenFiles.isOpen(source) -> held++
                else -> {
                    compactPass(app, candidate.id, source)
                    if (copyNotebook(writer, dest, candidate.id, source)) {
                        copied++
                        // Stamp per success, immediately — a kill mid-run keeps every stamp
                        // already earned, and a failed copy below never reaches this line.
                        // Guarded: a stamp that fails to persist only re-copies next run, and
                        // must not abort the run (the never-throws contract, K3 review).
                        config = config.copy(stamps = config.stamps + (candidate.id to candidate.updatedAt))
                        runCatching { store.write(config) }
                            .onFailure { Log.w(TAG, "stamp write failed", it) }
                    } else failed++
                }
            }
            done++
            onProgress(Progress(done, total))
        }

        // Extension stores, before the index and after the notebooks: a store is content, and the
        // index is last by rule because it is the manifest of everything the run already wrote.
        var storesCopied = 0
        var storesFailed = 0
        for (file in stores) {
            when {
                // A zero-length store is a create that never finished — there is nothing in it to
                // restore, and copying it would replace a good destination copy with an empty one.
                file.length() == 0L -> Slog.d(TAG) { "store ${file.name} is empty — nothing to copy" }
                copyStore(app, writer, dest, file) -> storesCopied++
                else -> storesFailed++
            }
            done++
            onProgress(Progress(done, total))
        }

        val indexCopied = copyIndex(app, writer, dest)
        done++
        onProgress(Progress(done, total))

        val result = Result(
            copied = copied, upToDate = work.upToDate, excluded = work.excluded,
            held = held, missing = missing, failed = failed,
            storesCopied = storesCopied, storesFailed = storesFailed, indexCopied = indexCopied,
        )
        if (result.succeeded) {
            config = config.copy(
                lastRunAt = System.currentTimeMillis(),
                lastCopied = result.copied,
                lastSkipped = result.upToDate + result.excluded + result.held + result.missing,
                stamps = BackupPredicates.pruneStamps(config.stamps, notebooks.mapTo(HashSet()) { it.id }),
            )
            store.write(config)
        }
        Slog.d(TAG) {
            "run: $copied copied, ${result.upToDate} up to date, ${result.excluded} excluded, " +
                "$held held, $missing missing, $failed failed, " +
                "stores $storesCopied copied / $storesFailed failed, index=$indexCopied"
        }
        return result
    }

    /**
     * K1's purge, run on the bytes about to travel: open through the one door, purge + `VACUUM`,
     * seal — the checkpoint inside seal absorbs the result, so the main file alone is a complete
     * copy. **Best effort by og's rule**: a notebook that will not open unattended is still backed
     * up as the bytes it is — failure here is never a reason to skip the copy that follows.
     */
    private fun compactPass(context: Context, notebookId: String, source: File) {
        val passphrase = KeySession.get() ?: return
        val db = try {
            SoilDatabase.open(context, notebookId, source, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "compact pass could not open — copying as-is", e)
            return
        }
        try {
            SoilCompactor.compact(db.raw()) // never throws (its own contract)
        } finally {
            db.seal(source) // never throws; releases the SoilOpenFiles claim
        }
    }

    /**
     * The per-notebook copy, og D9 step 5 whole: `.soil` first; a still-non-empty WAL (the seal's
     * checkpoint failed) is copied alongside and **both must land before the stamp**; an absorbed
     * WAL deletes the stale destination sidecar instead.
     */
    private fun copyNotebook(writer: SafBackupWriter, dest: Uri, notebookId: String, source: File): Boolean {
        val name = BackupPredicates.soilName(notebookId)
        if (!writer.writeAtomic(dest, name, source)) return false
        val wal = File(source.path + BackupPredicates.WAL_SUFFIX)
        val walName = name + BackupPredicates.WAL_SUFFIX
        // One predicate decides the sidecar's fate everywhere ([SoilCompactor.sidecarsRemovable]).
        return if (!SoilCompactor.sidecarsRemovable(wal.exists(), if (wal.exists()) wal.length() else 0L)) {
            writer.writeAtomic(dest, walName, wal)
        } else {
            // The stale destination sidecar must be VERIFIABLY gone before the stamp (K3 review):
            // a failed listing or delete here, swallowed, would pair a fresh `.soil` with an old
            // `-wal` forever — exactly the corruption the alongside rule exists to prevent.
            val entries = writer.list(dest) ?: return false
            val stale = entries.firstOrNull { it.name == walName } ?: return true
            writer.delete(stale.uri)
        }
    }

    /**
     * og D9 steps 6–7: purge + checkpoint the live index, then copy it the live-database way
     * ([copyDatabase]).
     */
    private suspend fun copyIndex(context: Context, writer: SafBackupWriter, dest: Uri): Boolean {
        SnIndex.compactIfNeeded(minReclaimBytes = 0L) // pre-copy: every reclaimable byte matters
        SnIndex.checkpoint() // best effort; the WAL length inside copyDatabase is the honest verdict
        return copyDatabase(context, writer, dest, indexFile(context), BackupPredicates.INDEX_NAME)
    }

    /**
     * One extension store (arc 21 / W5), under its own name — `<pkg>.db`, which is both the source
     * filename and the destination's, so a restore puts it back where the host looks for it.
     *
     * A store takes the **index's** treatment rather than a notebook's, because it is a database
     * this process very likely has open: [ExtensionStores] caches every store it opens for the life
     * of the process and closes none, so the notebook rule ("never copy under a live writer, skip
     * and count it") would skip every store that has ever been used — which is every store worth
     * copying. Snapshot-and-probe is what makes the live copy safe instead.
     */
    private fun copyStore(context: Context, writer: SafBackupWriter, dest: Uri, live: File): Boolean {
        extensionStorePackage(live.name)?.let { ExtensionStores.checkpointIfOpen(it) }
        return copyDatabase(context, writer, dest, live, live.name)
    }

    /**
     * The live-database copy, shared by the index and every extension store: snapshot [live] into
     * the cache, **probe the snapshot** (still the encrypted header it claims to be, byte-for-byte
     * the live length), and stream that. Only a failed snapshot streams the live file — a last
     * resort, because a copy of a live database can tear in ways no downstream check would catch.
     *
     * **The WAL-alongside rule applies here** (K3 review): a busy checkpoint — a pooled Room reader
     * under the library screen is enough — leaves committed rows in the `-wal`, *this run's own
     * stamps included* for the index. A main-file-only copy would pass every probe and silently
     * miss them, so a non-empty post-checkpoint WAL is snapshotted and written alongside, both
     * landing before the copy counts; an absorbed WAL deletes the stale destination sidecar.
     *
     * The caller has already done whatever this file's kind needs first (a purge, a checkpoint) —
     * those differ, and everything after them does not.
     */
    private fun copyDatabase(
        context: Context,
        writer: SafBackupWriter,
        dest: Uri,
        live: File,
        destName: String,
    ): Boolean {
        val liveWal = File(live.path + BackupPredicates.WAL_SUFFIX)
        val dir = File(context.cacheDir, DIR)
        var snapshot: File? = null
        var walSnapshot: File? = null
        try {
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw java.io.IOException("could not create the backup cache directory")
            val snap = File(dir, destName)
            live.copyTo(snap, overwrite = true)
            // Main first, then the WAL: frames the WAL holds beyond the main copy replay forward
            // on open. Nothing writes this file between the two copies — the engine is sequential
            // and the run's remaining config write comes after this call returns.
            if (liveWal.exists() && liveWal.length() > 0L) {
                val walSnap = File(dir, destName + BackupPredicates.WAL_SUFFIX)
                liveWal.copyTo(walSnap, overwrite = true)
                walSnapshot = walSnap
            }
            snapshot = snap.takeIf {
                it.length() > 0L && it.length() == live.length() &&
                    SoilCrypto.probe(it) == SoilFileKind.Encrypted
            } ?: throw java.io.IOException("snapshot failed its probe")
        } catch (e: Exception) {
            Log.w(TAG, "snapshot of $destName failed — falling back to the live file", e)
            snapshot = null
            walSnapshot = null
        }
        val mainOk = writer.writeAtomic(dest, destName, snapshot ?: live)
        val walName = destName + BackupPredicates.WAL_SUFFIX
        val walSource = walSnapshot
            ?: liveWal.takeIf { snapshot == null && it.exists() && it.length() > 0L }
        val walOk = when {
            !mainOk -> false
            walSource != null -> writer.writeAtomic(dest, walName, walSource)
            else -> {
                // Absorbed (or absent) WAL: the stale destination sidecar must be verifiably gone,
                // same as the notebook copy — a fresh main file + an old -wal corrupts on restore.
                val entries = writer.list(dest)
                when {
                    entries == null -> false
                    else -> entries.firstOrNull { it.name == walName }?.let { writer.delete(it.uri) } ?: true
                }
            }
        }
        runCatching { dir.deleteRecursively() }
        return mainOk && walOk
    }
}
