package com.symmetricalpalmtree.notesproutsn.importing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.AttemptLimiter
import com.symmetricalpalmtree.notesproutsn.crypto.ImportKeying
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.crypto.SoilFileKind
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupStore
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.ImportSpec
import com.symmetricalpalmtree.notesproutsn.extension.ImporterClient
import com.symmetricalpalmtree.notesproutsn.extension.ImporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.ProviderRef
import com.symmetricalpalmtree.notesproutsn.library.FolderPickerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * **Import** (arc 16 / I1) — the host's whole side of getting a notebook *into* the app, hung off
 * the library's Import button (bottom-right group, before Templates).
 *
 * The seam in one sentence, and it is the exporter's reversed: *the host keys, the extension
 * delivers.* This class owns the entry, the picker, the cache, the probe, the unlock, the re-key,
 * every question the user is asked and both writes at the end; an importer extension receives two
 * `ParcelFileDescriptor`s and an [ImportSpec] and streams bytes. No passphrase, path or SQLCipher
 * ever crosses.
 *
 * The pipeline, and each step is a rule:
 *
 *  1. **Discover, then ask what they accept.** A `describe()` that fails drops that importer with a
 *     log line, never a crash. Their declared MIME types seed the picker's filter (plus a wildcard —
 *     providers mislabel a `.soil` routinely); their declared *extensions* are what actually choose
 *     one ([ImporterMatch]).
 *  2. **Deliver into the cache.** `cacheDir/import/` is wiped per import and is the only place
 *     anything happens until step 7 — the Garden and the index cannot be harmed by a failure before
 *     then, by construction rather than by care.
 *  3. **Probe, then unlock.** The bytes are a stranger's. Plaintext or encrypted is decided by the
 *     header; an encrypted file is tried against this device's key **first** (a same-device Keep
 *     export just opens), and only a foreign one raises the passphrase prompt — rate-limited on its
 *     own [AttemptLimiter] bucket, looping in place, never logging what was typed.
 *  4. **Re-key to this device, always** ([ImportKeying]). SN opens files under one key; an import
 *     that kept a foreign one would be a notebook the library could not open tomorrow.
 *  5. **Read the manifest, and trust none of it** ([SafeImportId]). No `notebook` table is a
 *     rejection; no `notebook_meta` is not — that file imports under the picked name.
 *  6. **Ask the three questions**: id collision, placement, name conflict. Any of them can be
 *     cancelled, and cancelling costs nothing but the cache.
 *  7. **Remap, write, register — in that order.** The in-file re-identification runs in the cache
 *     (without it a fresh-id import opens empty — the arc-15 / E3 finding); the Garden write is a
 *     staged rename; and the index row lands **last**, so a crash mid-import leaves the library
 *     exactly as it was.
 *
 * Every failure after the picker is a **dialog** naming what went wrong — never a path, never a
 * secret — and the only thing any of them deletes is the import cache (the toast-vs-dialog rule,
 * and arc 15's honesty rules carried over whole).
 */
class ImportFlow(
    private val activity: AppCompatActivity,
    private val repo: IndexRepository,
    /** The library button this owns (bottom-right group, before Templates): `VISIBLE` only while
     *  a trusted importer is installed. */
    private val button: View,
    /** Where the library is standing — used only to decide whether the confirm dialog names a folder. */
    private val currentFolder: () -> String?,
    /** Retire a notebook the user chose to replace: the library's own delete (index row, recents,
     *  file, cached key). Runs **after** the import has fully committed, never before. */
    private val retireNotebook: suspend (id: String) -> Unit,
    /** Rebuild the library listing — the import landed. */
    private val onImported: suspend () -> Unit,
) {

    /** One installed importer and what it said it accepts. */
    private class Candidate(val ref: ProviderRef, val info: ImporterInfo)

    private var candidates: List<Candidate> = emptyList()
    private var installed = false

    /** True from the Import tap until the flow ends — the tap latch, closing the e-ink feedback gap
     *  between the tap and the picker. A second tap in it is simply dropped: nothing has started
     *  yet, so there is nothing to explain. */
    private var isBusy = false

    /**
     * True only while the pipeline itself is running — from the picker's result to the end. **This**
     * is what latches the way out: a Binder call cannot be cancelled, so leaving mid-import would
     * abandon the flow past its verification and cleanup, and the tap that finds it up gets a dialog
     * rather than silence (the toast-vs-dialog rule).
     */
    var isImporting: Boolean = false
        private set

    private var discovering = false

    /** The folder "Choose folder…" is waiting on. Completed by the picker's result. */
    private var folderPick: CompletableDeferred<String?>? = null

    private val openLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runImport(uri)
        } else {
            // Cancelled at the picker: nothing was copied, nothing to explain.
            isBusy = false
            Slog.d(TAG) { "document picker cancelled" }
        }
    }

    private val folderLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = folderPick
        folderPick = null
        if (pending == null) {
            // The flow died behind the picker (a process kill on a memory-tight device). The user
            // chose a folder and nothing is waiting for the answer — say so rather than let the tap
            // vanish (arc 15's rebuilt-behind-the-picker lesson, in its import shape).
            if (result.resultCode == Activity.RESULT_OK) {
                Dialogs.problem(activity, R.string.import_failed_title, activity.getString(R.string.import_interrupted_body))
            }
            return@registerForActivityResult
        }
        pending.complete(
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringExtra(FolderPickerActivity.EXTRA_PICKED_FOLDER_ID) ?: ROOT
            } else null
        )
    }

    // ── The button ───────────────────────────────────────────────────────────

    /**
     * Re-discover and show or hide the button. Called from the library's `onResume` — a package can
     * be disabled or replaced under a standing screen, and a button that lies is worse than one that
     * is absent. Never `isEnabled = false`: a disabled control is invisible on e-ink.
     */
    fun refresh() {
        if (discovering) return
        discovering = true
        activity.lifecycleScope.launch {
            val refs = try {
                discover()
            } finally {
                discovering = false
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            installed = refs.isNotEmpty()
            // Not under a running import: the button is unreachable behind the overlay anyway, and
            // taking it away mid-flow would say something about a flow that is still going.
            if (!isImporting) button.visibility = if (installed) View.VISIBLE else View.GONE
        }
    }

    /** The busy latch's voice: why the door did nothing (a silent ignore reads as broken). */
    fun showBusyGuard() =
        Dialogs.problem(activity, R.string.import_busy_title, R.string.import_busy_body)

    /**
     * The tap. The importers are asked what they accept **before** the picker, because their MIME
     * types are its filter; the beat that costs is latched like every other e-ink feedback gap.
     */
    fun onTap() {
        if (isImporting) { showBusyGuard(); return }
        if (isBusy) { Slog.d(TAG) { "import tap ignored: the picker is already coming" }; return }
        isBusy = true
        activity.lifecycleScope.launch {
            var launched = false
            try {
                val cands = loadCandidates().also { candidates = it }
                if (cands.isEmpty()) {
                    problem(R.string.import_none_title, activity.getString(R.string.import_none_body))
                    refresh()
                    return@launch
                }
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(ImporterMatch.ANY_TYPE)
                    .putExtra(Intent.EXTRA_MIME_TYPES, ImporterMatch.mimeFilter(cands.map { it.info.mimeTypes }))
                try {
                    openLauncher.launch(intent)
                    launched = true
                } catch (e: Exception) {
                    Log.w(TAG, "no document picker: ${e.javaClass.simpleName}")
                    problem(R.string.import_no_picker_title, activity.getString(R.string.import_no_picker_body))
                }
            } finally {
                // The latch is handed to the picker's result callback when the picker actually
                // opened; otherwise it is released here or the button is dead for good.
                if (!launched) isBusy = false
            }
        }
    }

    /**
     * Discovery, with `PackageManager`'s weather kept out of the coroutine: `queryIntentServices`
     * and `checkSignatures` can throw under a system-server hiccup or a package replaced mid-query,
     * and an uncaught throw here would crash the library on a screen the user merely resumed (the
     * I2 review's finding). No importers is the honest degraded answer either way.
     */
    private suspend fun discover(): List<ProviderRef> = try {
        ExtensionRegistry.importers(activity)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "importer discovery failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    private suspend fun loadCandidates(): List<Candidate> {
        val refs = discover()
        val kept = ArrayList<Candidate>(refs.size)
        for (ref in refs) {
            val info = try {
                ImporterClient(activity, ref).describe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Inward is untrusted and unmarshal is the validation: a descriptor over the caps
                // fails inside the call, and that importer is simply not in the list.
                Slog.d(TAG) { "dropping ${ref.packageName}: describe failed (${e.message})" }
                null
            } ?: continue
            kept += Candidate(ref, info)
        }
        Slog.d(TAG) { "${kept.size} usable importer(s)" }
        return kept
    }

    // ── The pipeline ─────────────────────────────────────────────────────────

    private fun runImport(uri: Uri) {
        isBusy = true
        isImporting = true
        ImportOverlay.show(activity, R.string.import_stage_reading)
        activity.lifecycleScope.launch {
            try {
                import(uri)
            } catch (e: NotebookImport.ImportProblem) {
                Slog.d(TAG) { "import problem: ${e.problem}" }
                problem(R.string.import_failed_title, activity.getString(problemBody(e.problem)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class name only: a message from down here can carry a path, and a passphrase
                // is never anywhere near a log line.
                Log.w(TAG, "import failed: ${e.javaClass.simpleName}")
                problem(R.string.import_failed_title, activity.getString(R.string.import_generic_body))
            } finally {
                // The incoming copy is the user's notes; it has no business outliving the import
                // that made it, whichever way it ended — a screen destroyed mid-import included,
                // which is why the wipe is NonCancellable (a plain withContext in an
                // already-cancelled scope throws before it runs anything).
                withContext(NonCancellable + Dispatchers.IO) { NotebookImport.clean(activity) }
                isBusy = false
                isImporting = false
                folderPick = null
                ImportOverlay.hide(activity)
            }
        }
    }

    private suspend fun import(uri: Uri) {
        val displayName = withContext(Dispatchers.IO) { displayNameOf(uri) }
        val cands = candidates.ifEmpty { loadCandidates().also { candidates = it } }
        if (cands.isEmpty()) {
            problem(R.string.import_none_title, activity.getString(R.string.import_none_body))
            return
        }
        val chosen = chooseImporter(cands, displayName) ?: return

        // 1 · Deliver into the cache.
        val incoming = withContext(Dispatchers.IO) { NotebookImport.prepareCache(activity) }
        deliver(chosen, uri, incoming, displayName)

        // 2 · Probe, unlock, re-key to this device's key. Every accepted import ends up under it.
        val global = KeySession.get() ?: throw NotebookImport.ImportProblem(NotebookImport.Problem.NO_KEY)
        val opening = unlock(incoming, global) ?: return
        ImportOverlay.stage(activity, R.string.import_stage_keying)
        val keyed = try {
            ImportKeying.toGlobal(incoming, opening, global)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "import keying failed: ${e.javaClass.simpleName}")
            throw NotebookImport.ImportProblem(NotebookImport.Problem.KEYING, e)
        }

        // 3 · Read what it says about itself, and trust none of it.
        val manifest = NotebookImport.readManifest(keyed, global)
        val name = ImportNames.notebookName(manifest.meta?.name, displayName)

        // 4 · The three questions — decided here, written nowhere: cancelling any of them must
        //     cost nothing but the cache, so no index row (the recreated folders included — the I2
        //     review's finding) lands until every question has an answer.
        val identity = resolveIdentity(manifest) ?: return
        val landing = if (identity.placementDecided) Landing(identity.parentId)
        else placement(manifest) ?: return
        val naming = resolveName(name, landing.parentId, identity.notebookId, identity.keepBothChosen) ?: return

        // 5 · Commit: remap in the cache, then the Garden, then the index — folders first, the
        //     notebook row last — in that order.
        ImportOverlay.stage(activity, R.string.import_stage_importing)
        val oldId = manifest.rawFileId
        if (oldId != null && oldId != identity.notebookId) {
            NotebookImport.remap(keyed, global, oldId, identity.notebookId)
        }
        NotebookImport.placeInGarden(activity, keyed, identity.notebookId)
        var parentId = landing.parentId
        for (create in landing.create) {
            if (!repo.createFolderWithId(create.id, create.name, create.parentId)) {
                // Something took the id between the read and the write. The rule is the planner's:
                // never mutate, land one level up. (The name was resolved against the planned
                // folder — a clash after this fallback is a cosmetic duplicate, not a hole.)
                Slog.d(TAG) { "folder id taken under us — landing one level up" }
                parentId = create.parentId
                break
            }
        }
        val now = System.currentTimeMillis()
        // The arriving file's own nature (arc 19 / M2), read once and used for both writes — the
        // index row (the authority) and the meta refresh (its mirror). Untrusted like the rest of
        // the manifest, and harmless if wrong: the worst it costs is a notebook that opens on the
        // wrong one of its two surfaces.
        val textDocument = manifest.meta?.textDocument == true
        repo.importNotebookRow(
            id = identity.notebookId,
            name = naming.name,
            parentId = parentId,
            pageCount = manifest.pageCount,
            createdAt = manifest.meta?.createdAt ?: now,
            updatedAt = manifest.meta?.updatedAt ?: now,
            templateKind = manifest.templateKind,
            textDocument = textDocument,
        )
        // The replaced notebook goes only now — cancelling anywhere above left it untouched.
        naming.retireId?.let { retireNotebook(it) }
        // A landed import can carry an updatedAt OLDER than the id's standing backup stamp, and
        // og's D8 would then read "up to date" forever — the stamp is a statement about content
        // this import just replaced, so it goes (K3 review). Best effort: a failed clear only
        // delays the re-copy until the next edit.
        runCatching { BackupStore().clearStamp(identity.notebookId) }
            .onFailure { Log.w(TAG, "backup stamp clear skipped: ${it.javaClass.simpleName}") }

        // 6 · Best effort from here: the notebook is in the library either way.
        ImportOverlay.stage(activity, R.string.import_stage_finishing)
        runCatching {
            NotebookImport.refreshMeta(
                context = activity,
                notebookId = identity.notebookId,
                name = naming.name,
                folderPath = repo.ancestry(parentId),
                passphrase = global,
                appVersionCode = versionCode(),
                textDocument = textDocument,
            )
        }.onFailure { Log.w(TAG, "meta refresh skipped: ${it.javaClass.simpleName}") }

        ImportOverlay.hide(activity)
        onImported()
        confirmImported(parentId)
    }

    // ── Step 1: the delivery ─────────────────────────────────────────────────

    /** One match is no question; several is a chooser; none is a dialog — an importer picked at
     *  random would stream a document into a probe that was always going to refuse it. */
    private suspend fun chooseImporter(cands: List<Candidate>, displayName: String): Candidate? {
        val matches = ImporterMatch.matching(cands.map { it.info.fileExtensions }, displayName)
        return when {
            matches.isEmpty() -> {
                problem(R.string.import_unsupported_title, activity.getString(R.string.import_unsupported_body))
                null
            }
            matches.size == 1 -> cands[matches.first()]
            else -> {
                val pick = ImportDialogs.pickFromList(
                    activity, R.string.import_format_title, matches.map { cands[it].info.formatLabel },
                ) ?: return null
                cands[matches[pick]]
            }
        }
    }

    /**
     * Two fds, one call. Both descriptors are the client's from that point — it closes them in
     * `finally`, success, failure or timeout.
     *
     * The byte count is then checked twice, and the second check is **corroboration, not
     * authority** (arc 15's verify lesson): against the file that actually landed, which must
     * agree exactly, and against whatever the source provider will say about the document —
     * where it says nothing at all, the stream is accepted on its own terms rather than refused
     * for a number nobody can supply.
     */
    private suspend fun deliver(chosen: Candidate, uri: Uri, incoming: File, displayName: String) {
        val sizes = withContext(Dispatchers.IO) { sourceSizes(uri) }
        val source = withContext(Dispatchers.IO) {
            runCatching { activity.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        } ?: throw NotebookImport.ImportProblem(NotebookImport.Problem.DELIVERY)
        val destination = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(
                    incoming,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                )
            }.getOrNull()
        }
        if (destination == null) {
            withContext(Dispatchers.IO) { runCatching { source.close() } }
            throw NotebookImport.ImportProblem(NotebookImport.Problem.WRITE)
        }
        val spec = try {
            ImportSpec(emptyMap(), ImportNames.specDisplayName(displayName, ExporterContract.MAX_NAME_CHARS))
        } catch (_: IllegalArgumentException) {
            // A display name this build cannot express is not worth failing an import over; the
            // spec's name is display-only and the importer does not need it.
            ImportSpec(emptyMap(), "")
        }
        val result = try {
            ImporterClient(activity, chosen.ref).importDocument(source, destination, spec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "import call failed: ${e.message}" }
            throw NotebookImport.ImportProblem(NotebookImport.Problem.DELIVERY, e)
        }
        val landed = withContext(Dispatchers.IO) { incoming.length() }
        if (landed != result.bytesWritten) {
            Log.w(TAG, "delivery landed $landed of ${result.bytesWritten} reported bytes")
            throw NotebookImport.ImportProblem(NotebookImport.Problem.SHORT)
        }
        if (landed == 0L) throw NotebookImport.ImportProblem(NotebookImport.Problem.NOT_A_NOTEBOOK)
        // Corroboration, not authority (arc 15's verify lesson, honoured in both halves): a
        // provider claiming MORE than landed describes bytes that never arrived — a truncated
        // stream both first-hand counts agreed on — and fails. A provider claiming less or zero is
        // contradicted by two agreeing first-hand counts (streaming providers routinely report a
        // stale or placeholder SIZE — the I2 review's finding); it is logged, and the probe and the
        // keying acceptance downstream still answer for what the bytes actually are.
        if (sizes.any { it > landed }) {
            Log.w(TAG, "source reports $sizes for $landed delivered bytes")
            throw NotebookImport.ImportProblem(NotebookImport.Problem.SHORT)
        }
        if (sizes.isNotEmpty() && sizes.none { it == landed }) {
            Log.w(TAG, "source size accounts $sizes disagree with $landed delivered bytes")
        }
        Slog.d(TAG) { "delivered $landed bytes" }
    }

    // ── Step 3: the unlock ───────────────────────────────────────────────────

    /**
     * How the incoming file opens. This device's key is tried **first** — a Keep export coming home
     * simply opens, and asking for a passphrase the user never set would be the wrong question. Only
     * a genuinely foreign file raises the prompt, and the prompt loops in place under the `"IMPORT"`
     * [AttemptLimiter] bucket (its own, so a wrong guess here never counts against the library's
     * unlock). Null = the user backed out; nothing has been written anywhere.
     */
    private suspend fun unlock(incoming: File, global: String): ImportKeying.Opening? {
        when (withContext(Dispatchers.IO) { SoilCrypto.probe(incoming) }) {
            SoilFileKind.Invalid -> throw NotebookImport.ImportProblem(NotebookImport.Problem.NOT_A_NOTEBOOK)
            SoilFileKind.Plaintext -> return ImportKeying.Opening.Plaintext
            SoilFileKind.Encrypted -> Unit
        }
        if (withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(incoming, global) }) {
            Slog.d(TAG) { "incoming opens under this device's key" }
            return ImportKeying.Opening.Encrypted(global)
        }
        ImportOverlay.stage(activity, R.string.import_stage_unlocking)
        var errorRes: Int? = null
        while (true) {
            val until = AttemptLimiter.check(activity, ATTEMPT_BUCKET)
            val remaining = until - System.currentTimeMillis()
            if (remaining > 0) {
                problem(
                    R.string.import_locked_out_title,
                    activity.getString(R.string.import_locked_out_body, formatSeconds(remaining)),
                )
                return null
            }
            val typed = ImportDialogs.passphrase(
                activity, R.string.import_passphrase_title, R.string.import_passphrase_body, errorRes,
            ) ?: return null
            if (withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(incoming, typed) }) {
                AttemptLimiter.recordSuccess(activity, ATTEMPT_BUCKET)
                return ImportKeying.Opening.Encrypted(typed)
            }
            AttemptLimiter.recordFailure(activity, ATTEMPT_BUCKET)
            errorRes = R.string.import_passphrase_wrong
        }
    }

    // ── Step 4: the three questions ──────────────────────────────────────────

    /**
     * The id the import will land under. [placementDecided] is what separates *Replace existing* —
     * which keeps the row's own folder, root included, and asks no placement question — from every
     * other outcome, where [parentId] means nothing yet.
     */
    private class Identity(
        val notebookId: String,
        val parentId: String? = null,
        val placementDecided: Boolean = false,
        /** True only when the user *answered* Keep both at the id-collision dialog — a fresh UUID
         *  minted silently (no meta, an id taken by something else) is not an answer. The name
         *  question honours it: one Keep both is one answer (the user's arc-16 polish call). */
        val keepBothChosen: Boolean = false,
    )

    /**
     * **Id collision.** A live notebook already under this file's id is the one case that asks:
     * *Replace existing* keeps that row's placement and skips the placement question entirely (it
     * is the same notebook, coming back), *Keep both* mints a fresh UUID — which is what forces the
     * in-file remap.
     *
     * Every other way the id can be taken — a soft-deleted notebook, a folder, a list sentinel —
     * takes a fresh UUID with no question asked: reviving or overwriting a row that is not a live
     * notebook of the user's would be a mutation nobody asked for.
     */
    private suspend fun resolveIdentity(manifest: NotebookImport.Manifest): Identity? {
        val fileId = manifest.fileId ?: return Identity(UUID.randomUUID().toString())
        val existing = repo.get(fileId)
        if (existing == null) return Identity(fileId)
        if (existing.deletedAt != null || existing.type != ObjectType.NOTEBOOK) {
            Slog.d(TAG) { "id taken by a ${existing.type} — importing under a fresh id" }
            return Identity(UUID.randomUUID().toString())
        }
        val choice = ImportDialogs.choose(
            activity,
            R.string.import_collision_title,
            activity.getString(R.string.import_collision_body, existing.name),
            R.string.import_replace,
            R.string.import_keep_both,
        ) ?: return null
        return when (choice) {
            ImportDialogs.Choice.PRIMARY -> {
                // One file, one connection: an overwrite under a live writer is exactly what the
                // registry exists to refuse.
                if (withContext(Dispatchers.IO) { SoilOpenFiles.isOpen(soilFile(activity, fileId)) }) {
                    throw NotebookImport.ImportProblem(NotebookImport.Problem.IN_USE)
                }
                Identity(fileId, existing.parentId, placementDecided = true)
            }
            ImportDialogs.Choice.SECONDARY -> Identity(UUID.randomUUID().toString(), keepBothChosen = true)
        }
    }

    /** Where the notebook will land: the folder (null = the library root) plus the folders that
     *  must be created for it — **at commit, never here**: the questions only decide. */
    private class Landing(val parentId: String?, val create: List<AncestryPlan.Create> = emptyList())

    /**
     * **Placement.** *Notebook's folders* plans the file's remembered ancestry, strictly
     * create-only ([AncestryPlan]) — a segment that is anything but a live folder of the user's own
     * stops the descent one level up, and nothing existing is ever touched. *Choose folder…* hands
     * the question to the library's own picker. Null means the user backed out.
     *
     * Nothing is written here: the planned creates run in the commit step, after the last
     * question, so a cancel at the name dialog cannot leave empty folders behind (the I2 review's
     * finding).
     */
    private suspend fun placement(manifest: NotebookImport.Manifest): Landing? {
        val choice = ImportDialogs.choose(
            activity,
            R.string.import_placement_title,
            activity.getString(R.string.import_placement_body),
            R.string.import_placement_own,
            R.string.import_placement_choose,
        ) ?: return null
        if (choice == ImportDialogs.Choice.SECONDARY) {
            val deferred = CompletableDeferred<String?>()
            folderPick = deferred
            folderLauncher.launch(FolderPickerActivity.pickIntent(activity, currentFolder()))
            val picked = deferred.await() ?: return null
            return Landing(if (picked == ROOT) null else picked)
        }
        // The plan is decided from reads taken up front, so the rule itself stays pure.
        val path = manifest.folderPath
        val slots = HashMap<String, AncestryPlan.Slot>(path.size)
        for (ref in path) {
            val id = SafeImportId.orNull(ref.id) ?: continue
            if (id in slots) continue
            val row = repo.get(id)
            slots[id] = when {
                row == null -> AncestryPlan.Slot.MISSING
                row.deletedAt == null && row.type == ObjectType.FOLDER -> AncestryPlan.Slot.LIVE_FOLDER
                else -> AncestryPlan.Slot.BLOCKED
            }
        }
        val plan = AncestryPlan.plan(path) { slots[it] ?: AncestryPlan.Slot.BLOCKED }
        if (plan.truncated) Slog.d(TAG) { "ancestry truncated — landing one level up" }
        return Landing(plan.parentId, plan.create)
    }

    /** The name the notebook lands under, and the notebook (if any) that is retired for it. */
    private class Naming(val name: String, val retireId: String?)

    /**
     * **Name conflict.** *Replace* retires the notebook that had the name — but only after the
     * import has fully committed, so cancelling later leaves it intact — and refuses outright if its
     * file is open in this process. *Keep both* takes the first free `… Copy` name.
     *
     * **Asked at most once per import** (the user's arc-16 polish call): when the id-collision
     * dialog was already answered *Keep both*, a name clash here is usually against the very
     * notebook the user just chose to keep — asking again is redundant, and answering Replace
     * would delete it. So [keepBothChosen] skips the question and takes the `… Copy` name
     * silently; the dialog remains for the only case that has not been asked anything yet, a
     * foreign notebook clashing by name alone.
     *
     * The siblings are read once and answered from, so the "already taken?" question and the
     * "which Copy is free?" question can never disagree with each other.
     */
    private suspend fun resolveName(
        name: String,
        parentId: String?,
        notebookId: String,
        keepBothChosen: Boolean,
    ): Naming? {
        val siblings = repo.notebooks(parentId).filter { it.id != notebookId }
        val clash: ObjectSummary = siblings.firstOrNull { it.name == name } ?: return Naming(name, null)
        if (keepBothChosen) {
            val taken = siblings.map { it.name }.toHashSet()
            return Naming(ImportNames.keepBothName(name) { it in taken }, null)
        }
        val choice = ImportDialogs.choose(
            activity,
            R.string.import_name_title,
            activity.getString(R.string.import_name_body, name),
            R.string.import_replace,
            R.string.import_keep_both,
        ) ?: return null
        return when (choice) {
            ImportDialogs.Choice.PRIMARY -> {
                if (withContext(Dispatchers.IO) { SoilOpenFiles.isOpen(soilFile(activity, clash.id)) }) {
                    throw NotebookImport.ImportProblem(NotebookImport.Problem.IN_USE)
                }
                Naming(name, clash.id)
            }
            ImportDialogs.Choice.SECONDARY -> {
                val taken = siblings.map { it.name }.toHashSet()
                Naming(ImportNames.keepBothName(name) { it in taken }, null)
            }
        }
    }

    // ── Endings ──────────────────────────────────────────────────────────────

    /** A dialog, not a toast: it names the folder when the notebook did not land where the user is
     *  standing, and that's exactly the information a missed toast would take with it — a card that
     *  is not on screen otherwise reads as an import that did nothing. */
    private suspend fun confirmImported(parentId: String?) {
        if (activity.isFinishing || activity.isDestroyed) return
        val message = if (parentId == currentFolder()) {
            activity.getString(R.string.import_done_body)
        } else {
            val folder = parentId?.let { repo.alive(it)?.name } ?: activity.getString(R.string.library_root)
            activity.getString(R.string.import_done_body_in, folder)
        }
        Dialogs.confirm(activity, R.string.import_done_title, message)
    }

    private fun problem(@StringRes titleRes: Int, message: String) {
        ImportOverlay.hide(activity)
        Dialogs.problem(activity, titleRes, message)
    }

    @StringRes
    private fun problemBody(problem: NotebookImport.Problem): Int = when (problem) {
        NotebookImport.Problem.DELIVERY -> R.string.import_delivery_body
        NotebookImport.Problem.SHORT -> R.string.import_short_body
        NotebookImport.Problem.NOT_A_NOTEBOOK -> R.string.import_not_a_notebook_body
        NotebookImport.Problem.NO_KEY -> R.string.import_locked_body
        NotebookImport.Problem.KEYING -> R.string.import_keying_body
        NotebookImport.Problem.UNREADABLE -> R.string.import_unreadable_body
        NotebookImport.Problem.IN_USE -> R.string.import_in_use_body
        NotebookImport.Problem.WRITE -> R.string.import_write_body
    }

    // ── Provider questions ───────────────────────────────────────────────────

    /** What the picker called the document. The extension match rides on it, so a provider that
     *  will not say gets the URI's last segment — which usually still carries the extension. */
    private fun displayNameOf(uri: Uri): String {
        runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    /** Every account the source provider will give of the document's size — the SIZE column and the
     *  fd's stat. Empty when it will not say at all; neither is authoritative alone. */
    private fun sourceSizes(uri: Uri): List<Long> {
        val sizes = ArrayList<Long>(2)
        runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) sizes += c.getLong(0)
            }
        }
        runCatching {
            activity.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize.takeIf { it >= 0L }?.let { sizes += it }
            }
        }
        return sizes
    }

    private fun versionCode(): Int = runCatching {
        activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    private fun formatSeconds(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }

    private companion object {
        const val TAG = "ImportFlow"

        /** The unlock bucket for foreign import files — its own, so a wrong guess at a stranger's
         *  passphrase never counts against unlocking the library itself. */
        const val ATTEMPT_BUCKET = "IMPORT"

        /** "The library root", as a value a nullable String cannot express beside "cancelled". */
        const val ROOT = "@root"
    }
}
