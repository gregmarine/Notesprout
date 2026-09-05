package com.symmetricalpalmtree.notesproutsn.data.backup

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.data.extensionStorePackage
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex
import com.symmetricalpalmtree.notesproutsn.data.indexFile
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import com.symmetricalpalmtree.notesproutsn.export.ExportVerification
import com.symmetricalpalmtree.notesproutsn.extension.CloudClient
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import com.symmetricalpalmtree.notesproutsn.extension.CloudNetworkException
import com.symmetricalpalmtree.notesproutsn.extension.CloudNotConnectedException
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.ProviderRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The cloud leg of a backup run** (arc 25 / V4) — the same run, the same order, a second
 * destination: `Backups/<device folder>/` under the provider's own root.
 *
 * It lives beside [BackupEngine] rather than inside it so neither file grows past its budget, and
 * it **calls** the shared pieces rather than copying them: `compactPass`, the work list, the store
 * checkpoint and the index purge are the engine's, and a second copy of any of them would be a
 * second answer that can disagree.
 *
 * What is genuinely different here, and why:
 *
 *  - **Every uploaded file is self-contained** ([SelfContainedSnapshot]). The local leg can land a
 *    `.soil` and its `-wal` as a near-atomic pair; two uploads can tear, and a fresh main file with
 *    a stale sidecar corrupts on restore. So the WAL is absorbed into a cache copy first and only
 *    a copy that comes out whole is sent. A file that will not snapshot is **refused this run** —
 *    counted failed, retried next run, nothing uploaded.
 *  - **One listing, not one per file.** A `list` is most of a second on this seam, so the leg takes
 *    the device folder's listing once at the start and keeps it current itself. It serves the one
 *    thing the listing is for: the stale `<name>-wal` that must be verifiably gone **before** the
 *    stamp — the local leg's rule, kept for the day something else ever puts one there. **That
 *    delete is the only remote delete in this arc.** A corroboration miss never deletes anything.
 *  - **Stamps are the cloud's own** ([BackupConfig.cloudStamps]) and are written per success, the
 *    moment it is earned.
 *  - **The leg stops where it stands** on a not-connected, a network failure or a no-answer, and
 *    returns the counts so far: every stamp already earned stays, and piling 60–120 second upload
 *    budgets onto a dead link turns a failed backup into a frozen screen. A retry is safe, because
 *    an upload is replace-by-name.
 *
 * Nothing here logs a folder name, a file name, an account or a URL — counts, booleans, durations.
 */
internal object CloudBackupLeg {

    private const val TAG = "CloudBackupLeg"

    /** Every backup file is opaque bytes to the provider: a `.soil` and the index are databases. */
    private const val MIME = "application/octet-stream"

    /** What one file's upload came to. */
    private sealed class Sent {
        /** It landed and the provider's account of it agrees. */
        object Ok : Sent()

        /** This one file could not be sent; the leg carries on. */
        object Refused : Sent()

        /** The link, the account or the provider itself is gone — the leg ends here. */
        class Stopped(val problem: BackupEngine.Problem) : Sent()
    }

    suspend fun run(
        app: Context,
        ref: ProviderRef,
        state: RunState,
        work: BackupPredicates.WorkList,
        storeFiles: List<File>,
        aliveIds: Set<String>,
        compacted: MutableSet<String>,
        tick: () -> Unit,
    ): BackupEngine.Result = try {
        runLeg(app, ref, state, work, storeFiles, aliveIds, compacted, tick)
    } finally {
        // A key-shaped copy of the library has no business outliving the run.
        SelfContainedSnapshot.clean(app)
    }

    private suspend fun runLeg(
        app: Context,
        ref: ProviderRef,
        state: RunState,
        work: BackupPredicates.WorkList,
        storeFiles: List<File>,
        aliveIds: Set<String>,
        compacted: MutableSet<String>,
        tick: () -> Unit,
    ): BackupEngine.Result {
        val folder = deviceFolder(state)
        val path = arrayOf(BackupPredicates.CLOUD_BACKUPS_FOLDER, folder)

        // Fail fast, before a single byte is read: with no folder there is nowhere to put anything,
        // and the listing is what every stale-sidecar decision below is made from.
        try {
            CloudClient.ensureFolder(app, ref, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BackupEngine.Result(problem = problemFor(app, e))
        }
        val listing = ArrayList<CloudEntry>()
        try {
            listing += CloudClient.list(app, ref, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BackupEngine.Result(problem = problemFor(app, e))
        }

        var stop: BackupEngine.Problem? = null
        var copied = 0
        var held = 0
        var missing = 0
        var failed = 0

        for (candidate in work.toCopy) {
            if (stop != null) break
            val source = soilFile(app, candidate.id)
            when {
                !source.exists() || source.length() == 0L -> missing++
                SoilOpenFiles.isOpen(source) -> held++
                else -> {
                    // VACUUM twice in one run is a minute for nothing: whichever leg got here first
                    // already compacted this notebook, and the file is what it now is.
                    if (compacted.add(candidate.id)) {
                        BackupEngine.compactPass(app, candidate.id, source, candidate.keyScope)
                    }
                    val name = BackupPredicates.soilName(candidate.id)
                    when (val sent = send(app, ref, path, source, name, candidate.id, listing)) {
                        is Sent.Ok -> {
                            copied++
                            state.update {
                                it.copy(cloudStamps = it.cloudStamps + (candidate.id to candidate.updatedAt))
                            }
                        }

                        is Sent.Refused -> failed++
                        is Sent.Stopped -> { failed++; stop = sent.problem }
                    }
                }
            }
            tick()
        }

        var storesCopied = 0
        var storesFailed = 0
        for (file in storeFiles) {
            if (stop != null) break
            val pkg = extensionStorePackage(file.name)
            when {
                // A zero-length store is a create that never finished — nothing in it to restore.
                file.length() == 0L -> Slog.d(TAG) { "a store is empty — nothing to upload" }
                pkg == null -> storesFailed++
                else -> {
                    ExtensionStores.checkpointIfOpen(pkg)
                    when (val sent = send(app, ref, path, file, file.name, ExtensionStores.fileIdFor(pkg), listing)) {
                        is Sent.Ok -> storesCopied++
                        is Sent.Refused -> storesFailed++
                        is Sent.Stopped -> { storesFailed++; stop = sent.problem }
                    }
                }
            }
            tick()
        }

        var indexCopied = false
        if (stop == null) {
            SnIndex.compactIfNeeded(minReclaimBytes = 0L)
            SnIndex.checkpoint()
            when (
                val sent = send(
                    app, ref, path, indexFile(app), BackupPredicates.INDEX_NAME,
                    KeyMaterial.INDEX_FILE_ID, listing,
                )
            ) {
                is Sent.Ok -> indexCopied = true
                is Sent.Refused -> Unit
                is Sent.Stopped -> stop = sent.problem
            }
            tick()
        }

        val result = BackupEngine.Result(
            problem = stop,
            copied = copied, upToDate = work.upToDate, excluded = work.excluded,
            held = held, missing = missing, failed = failed,
            storesCopied = storesCopied, storesFailed = storesFailed, indexCopied = indexCopied,
        )
        if (result.succeeded) {
            state.update {
                it.copy(
                    cloudLastRunAt = System.currentTimeMillis(),
                    cloudLastCopied = result.copied,
                    cloudLastSkipped = result.upToDate + result.excluded + result.held + result.missing,
                    cloudStamps = BackupPredicates.pruneStamps(it.cloudStamps, aliveIds),
                )
            }
        }
        Slog.d(TAG) {
            "cloud: $copied copied, ${result.upToDate} up to date, ${result.excluded} excluded, " +
                "$held held, $missing missing, $failed failed, " +
                "stores $storesCopied copied / $storesFailed failed, index=$indexCopied, " +
                "stopped=${stop != null}"
        }
        return result
    }

    /**
     * The device's folder name, minted here if the Backup screen never got the chance (a run
     * started before the Cloud section was ever rendered). A **fresh** name has never seen a file,
     * so the cloud stamp map is cleared with it — the same reasoning the screen's Rename… keeps.
     */
    private suspend fun deviceFolder(state: RunState): String {
        state.config.cloudDeviceFolder?.let { return it }
        val minted = DeviceFolder.mint()
        state.update { it.copy(cloudDeviceFolder = minted, cloudStamps = emptyMap()) }
        Slog.d(TAG) { "device folder minted (${minted.length} chars)" }
        return minted
    }

    /**
     * One file, whole: snapshot → upload → corroborate → the stale-sidecar check. [listing] is kept
     * current in place — an upload replaces its row, a delete removes one.
     *
     * A corroboration miss is **per file and never a delete** (the arc's standing trap: a provider's
     * metadata can lag its own write). The file is counted failed and retried next run, which is
     * safe because an upload is replace-by-name.
     */
    private suspend fun send(
        app: Context,
        ref: ProviderRef,
        path: Array<String>,
        live: File,
        name: String,
        fileId: String,
        listing: MutableList<CloudEntry>,
    ): Sent {
        val snapshot = withContext(Dispatchers.IO) { SelfContainedSnapshot.of(app, live, name, fileId) }
            ?: return Sent.Refused
        val bytes = snapshot.length()
        val pfd = withContext(Dispatchers.IO) {
            runCatching { ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        } ?: return Sent.Refused
        // The client owns the descriptor from here and closes it on every path, refusals included.
        val entry = try {
            CloudClient.upload(app, ref, path, name, MIME, pfd, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Sent.Stopped(problemFor(app, e))
        }
        listing.removeAll { it.name == name && !it.isFolder }
        listing.add(entry)
        if (ExportVerification.cloudVerdict(entry.sizeBytes, bytes) != ExportVerification.Verdict.OK) {
            Slog.d(TAG) { "upload not corroborated: sent $bytes B, reported ${entry.sizeBytes} B — kept, retried next run" }
            return Sent.Refused
        }
        return dropStaleSidecar(app, ref, name, listing)
    }

    /**
     * The one remote delete in this arc: a `<name>-wal` left in the folder by something that is not
     * this leg. It must be **verifiably** gone before the stamp — a swallowed failure here would
     * pair a fresh main file with an old sidecar forever, which is the corruption the whole
     * self-contained-snapshot rule exists to prevent.
     */
    private suspend fun dropStaleSidecar(
        app: Context,
        ref: ProviderRef,
        name: String,
        listing: MutableList<CloudEntry>,
    ): Sent {
        val stale = CloudBackupRules.staleSidecar(listing, name) ?: return Sent.Ok
        return try {
            CloudClient.delete(app, ref, stale.id)
            listing.remove(stale)
            Slog.d(TAG) { "a stale sidecar was removed before the stamp" }
            Sent.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Sent.Stopped(problemFor(app, e))
        }
    }

    /**
     * Which of the four cloud problems [e] is. The two typed refusals say themselves; a provider
     * that did not answer at all is asked about once — if discovery no longer finds it, it was
     * uninstalled under the run and *gone* is the truthful word, otherwise nothing is known and the
     * honest answer is that it did not answer.
     */
    private suspend fun problemFor(app: Context, e: Exception): BackupEngine.Problem {
        val failure = when (e) {
            is CloudNotConnectedException -> CloudBackupRules.Failure.NOT_CONNECTED
            is CloudNetworkException -> CloudBackupRules.Failure.NETWORK
            is ExtensionCallException ->
                if (ExtensionRegistry.cloud(app) == null) CloudBackupRules.Failure.GONE
                else CloudBackupRules.Failure.UNANSWERED

            else -> {
                Log.w(TAG, "cloud leg failed unexpectedly", e)
                CloudBackupRules.Failure.UNANSWERED
            }
        }
        Slog.d(TAG) { "cloud leg stopping: $failure" }
        return CloudBackupRules.problemFor(failure)
    }
}
