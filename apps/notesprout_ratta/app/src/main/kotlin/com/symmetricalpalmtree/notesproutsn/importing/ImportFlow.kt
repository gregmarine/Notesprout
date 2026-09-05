package com.symmetricalpalmtree.notesproutsn.importing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.cloud.CloudBrowserDialog
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
import com.symmetricalpalmtree.notesproutsn.export.ExportDestination
import com.symmetricalpalmtree.notesproutsn.extension.CloudClient
import com.symmetricalpalmtree.notesproutsn.extension.CloudConnectEntry
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import com.symmetricalpalmtree.notesproutsn.extension.CloudNetworkException
import com.symmetricalpalmtree.notesproutsn.extension.CloudNotConnectedException
import com.symmetricalpalmtree.notesproutsn.extension.CloudStatus
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
 *  0. **Ask where the file is** (arc 25 / V5), and only when there is a second place it could be:
 *     with a trusted cloud provider installed the tap asks *Import from* — this device, or the
 *     provider — and without one it goes straight to the document picker exactly as it always did.
 *     What the cloud answer then does is [ExportDestination.onCloudTap], the Export screen's own
 *     rule reused: connected browses, not configured says so, anything else offers Connect inline.
 *  1. **Discover, then ask what they accept.** A `describe()` that fails drops that importer with a
 *     log line, never a crash. Their declared MIME types seed the picker's filter (plus a wildcard —
 *     providers mislabel a `.soil` routinely); their declared *extensions* are what actually choose
 *     one ([ImporterMatch]).
 *  2. **Deliver into the cache.** `cacheDir/import/` is wiped per import and is the only place
 *     anything happens until step 7 — the Garden and the index cannot be harmed by a failure before
 *     then, by construction rather than by care.
 *  2a. **Fetch, for a cloud source** (arc 25 / V5). The importer is matched **before** anything is
 *     downloaded — a file no importer accepts must cost no bytes — and the provider then streams
 *     the file into a sibling of the incoming copy, never over it. What it says it wrote, what
 *     landed and what the listing claimed are corroborated by [CloudImportRules.downloadVerdict],
 *     and from there the bytes are delivered through the matched importer exactly as a picked
 *     document's are: **every import goes through an importer**, whichever side of the seam the
 *     bytes came from.
 *  2b. **Fork on what the bytes are** (arc 19 / M8). Delivery is the same for every importer; the
 *     descriptor's `resultKind` says what arrived. Text (`.md`/`.txt`) takes its own three-step
 *     branch — decode, create, open — and never reaches the pipeline below. An absent tail means
 *     a notebook, so every pre-arc-19 importer keeps exactly the behaviour it had.
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
 *
 * **Over the ~800-line rule, with reason (arc 19 / M8):** the growth is step 2b's text-import fork
 * — decode, create, open — and it belongs beside the pipeline it forks from. It shares the delivery
 * that precedes it, the cache and its wipe, the corroboration of what the extension claims it wrote,
 * the candidate/descriptor machinery and every dialog and failure sentence around it; split out, the
 * two halves would still have to agree on all of that across a seam, and the one thing a reader most
 * needs to see — *where* the bytes stop being a notebook — would be in the other file. Arc 25 / V5
 * adds the second **source** for the same reason: a cloud import differs from a picked document in
 * exactly one step — where the bytes come from — and everything after it, the latch and the cache
 * wipe included, is the one pipeline both of them run.
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
    /**
     * Open what a **text import** just created (arc 19 / M8). A `.soil` import ends with a
     * confirming dialog and leaves the user in the library — it may have landed in another folder,
     * and that is the information worth showing. A text document ends the other way: it is one
     * document, the user picked it seconds ago, and og opens straight into the editor. So there is
     * no confirm dialog on this path; the screen that appears *is* the confirmation.
     */
    private val openImported: (id: String, name: String) -> Unit,
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

    // ── The cloud source (arc 25 / V5) ───────────────────────────────────────

    /**
     * The connect door, built here because it registers an `ActivityResultLauncher` and the library
     * builds this class in `onCreate`. Closed by [close] from the library's `onDestroy` — a held
     * bind must not outlive the screen that opened it.
     */
    private val cloud = CloudConnectEntry(activity) { wasConnected -> onConnectResult(wasConnected) }

    /** The provider the **current tap** found, and what it said about itself. Both are re-read at
     *  every tap and never cached across taps: the Connect door changes the answer under a standing
     *  library screen, and a stale "connected" would open a browser at a cloud that is not there. */
    private var cloudRef: ProviderRef? = null
    private var cloudStatus: CloudStatus? = null

    /** True only while a sign-in this flow opened is out — so a connect result that belongs to some
     *  other beat can never resume a pipeline nobody started. */
    private var connectPending = false

    /** The browser while it is up, so [close] can take it down with the screen. */
    private var browser: CloudBrowserDialog? = null

    /** The library's `onDestroy` backstop: the browser is attached to that window and the connect
     *  bind must not outlive it. */
    fun close() {
        browser?.dismiss()
        browser = null
        connectPending = false
        cloud.close()
    }

    private val openLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runImport(Origin.Document(uri))
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
     *
     * With a cloud provider installed (arc 25 / V5) the tap first asks **where** the file is. Both
     * the discovery and the `status()` behind that question are made here, on every tap: the
     * Connect door changes the answer under a standing library screen, so an answer remembered from
     * the last tap would be a question asked about a cloud that has since gone.
     */
    fun onTap() {
        if (isImporting) { showBusyGuard(); return }
        if (isBusy) { Slog.d(TAG) { "import tap ignored: the picker is already coming" }; return }
        isBusy = true
        activity.lifecycleScope.launch {
            var handed = false
            try {
                val cands = loadCandidates().also { candidates = it }
                if (cands.isEmpty()) {
                    problem(R.string.import_none_title, activity.getString(R.string.import_none_body))
                    refresh()
                    return@launch
                }
                loadCloud()
                val installed = cloudRef != null
                if (!ImportSource.asksSource(installed)) {
                    // No second source: today's behaviour, with nothing in the way of it.
                    handed = launchDocumentPicker(cands)
                    return@launch
                }
                val answer = ImportDialogs.pickFromList(
                    activity,
                    R.string.import_source_title,
                    listOf(activity.getString(R.string.import_source_device), cloudName()),
                )
                // Dismissed: the picker's own cancel. Nothing has happened, so nothing is said.
                val source = answer?.let { ImportSource.sourceAt(it, installed) } ?: return@launch
                handed = when (source) {
                    ImportSource.Source.LOCAL -> launchDocumentPicker(cands)
                    ImportSource.Source.CLOUD -> onCloudSourceChosen()
                }
            } finally {
                // The latch is handed on when a picker, a browser or a sign-in actually opened;
                // otherwise it is released here or the button is dead for good.
                if (!handed) isBusy = false
            }
        }
    }

    /** SAF, as it always was. True when the picker actually opened — which is when the latch is the
     *  result callback's to release. */
    private fun launchDocumentPicker(cands: List<Candidate>): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(ImporterMatch.ANY_TYPE)
            .putExtra(Intent.EXTRA_MIME_TYPES, ImporterMatch.mimeFilter(cands.map { it.info.mimeTypes }))
        return try {
            openLauncher.launch(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "no document picker: ${e.javaClass.simpleName}")
            problem(R.string.import_no_picker_title, activity.getString(R.string.import_no_picker_body))
            false
        }
    }

    // ── The cloud source (arc 25 / V5) ───────────────────────────────────────

    /**
     * Is a trusted provider installed, and what does it say about itself? Both questions are
     * wrapped: a `PackageManager` hiccup or a provider that will not answer is "no cloud" and "no
     * status", never a crash on a screen the person merely tapped.
     */
    private suspend fun loadCloud() {
        val ref = try {
            cloud.discover()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cloud discovery failed: ${e.javaClass.simpleName}")
            null
        }
        cloudRef = ref
        if (ref == null) { cloudStatus = null; return }
        cloudStatus = try {
            CloudClient.status(activity, ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "cloud status unavailable: ${e.javaClass.simpleName}" }
            null
        }
    }

    /** What every cloud sentence here calls the provider — its own name, or the extension's label
     *  when it gave none. */
    private fun cloudName(): String =
        ExportDestination.providerName(cloudStatus, cloudRef?.label?.toString().orEmpty())

    /**
     * The cloud answer to the source question. The decision is [ExportDestination.onCloudTap] — the
     * Export screen's rule, reused rather than copied, so "connected?" is answered in one place.
     * True when the latch has been handed on (a browser or an offer is up).
     */
    private fun onCloudSourceChosen(): Boolean = when (ExportDestination.onCloudTap(cloudStatus)) {
        ExportDestination.Tap.SELECT -> { openCloudBrowser(); true }
        ExportDestination.Tap.NOT_CONFIGURED -> {
            Dialogs.problem(activity, R.string.cloud_not_configured_title, R.string.cloud_not_configured_body)
            false
        }
        ExportDestination.Tap.OFFER_CONNECT -> offerConnect()
    }

    /**
     * The inline Connect offer, in its import wording: nothing is *downloaded* until a file is
     * chosen — the export sentence's promise is about uploading and would be the wrong one here.
     * True when the dialog is up and therefore owns the latch.
     */
    private fun offerConnect(): Boolean {
        if (!cloud.isAvailable) return false
        if (activity.isFinishing || activity.isDestroyed) return false
        val name = cloudName()
        var connecting = false
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.cloud_connect_offer_title, name))
                .setMessage(activity.getString(R.string.import_cloud_connect_offer_body, name))
                .setPositiveButton(R.string.cloud_connect) { _, _ ->
                    connecting = true
                    connectPending = true
                    cloud.open()
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        // Cancel, back and a tap outside are one answer, and that answer unlatches: the person is
        // exactly where they were before the tap.
        dialog.setOnDismissListener { if (!connecting) isBusy = false }
        dialog.show()
        return true
    }

    /**
     * The sign-in came back. A connected account continues the beat the person was in — the browser
     * opens on the provider's root, which is what they asked for a moment ago. Anything else
     * unlatches in silence: the sign-in screen has already said whatever there was to say.
     */
    private fun onConnectResult(wasConnected: Boolean) {
        if (!connectPending) return
        connectPending = false
        if (!wasConnected) { isBusy = false; return }
        activity.lifecycleScope.launch {
            loadCloud()
            if (activity.isFinishing || activity.isDestroyed) { isBusy = false; return@launch }
            if (cloudRef != null && cloudStatus?.connected == true) openCloudBrowser() else isBusy = false
        }
    }

    /**
     * The cloud's stand-in for the document picker: the host-drawn browser over the provider's
     * **root**, so `Exports/` and `Backups/` are each one tap away and Up stops there.
     *
     * Nothing is filtered by extension — which importer can read the tapped file is
     * [chooseImporter]'s question afterwards, exactly as it is for a SAF document, and a file no
     * importer accepts gets the same "Can't import that file" dialog it would have got there. The
     * browser hiding the very file the person came for would be the worse answer.
     */
    private fun openCloudBrowser() {
        val ref = cloudRef
        if (ref == null) {
            // Disabled or replaced between the tap and here. One sentence, said in one place.
            isBusy = false
            cloudProblem(CloudImportFailure.Kind.GONE)
            return
        }
        browser?.dismiss()
        val dialog = CloudBrowserDialog(
            activity = activity,
            ref = ref,
            providerName = cloudName(),
            mode = CloudBrowserDialog.Mode.PICK_FILE,
            basePath = emptyList(),
            onPicked = { pick ->
                browser = null
                when (pick) {
                    is CloudBrowserDialog.Pick.File -> runImport(Origin.Cloud(ref, pick.entry))
                    // PICK_FILE cannot answer with a folder; if it ever did, there is nothing to
                    // import from it and ending the beat is the honest thing.
                    is CloudBrowserDialog.Pick.Folder -> isBusy = false
                }
            },
            onNotConnected = {
                browser = null
                // The account went away between the tap and the listing. Connect is the only thing
                // that helps, and nothing was downloaded to say otherwise.
                activity.lifecycleScope.launch {
                    loadCloud()
                    if (activity.isFinishing || activity.isDestroyed) { isBusy = false; return@launch }
                    if (!offerConnect()) isBusy = false
                }
            },
            onCancelled = {
                browser = null
                isBusy = false
                Slog.d(TAG) { "cloud browser cancelled" }
            },
        )
        browser = dialog
        dialog.show()
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

    /**
     * Where the bytes are coming from (arc 25 / V5) — the **one** thing a cloud import and a picked
     * document do not share. Everything after the fetch is the same pipeline, under the same latch
     * and the same cache wipe.
     */
    private sealed class Origin {
        /** A document the SAF picker named. */
        class Document(val uri: Uri) : Origin()

        /** A file in the provider's tree, as the browser listed it. */
        class Cloud(val ref: ProviderRef, val entry: CloudEntry) : Origin()
    }

    private fun runImport(origin: Origin) {
        isBusy = true
        isImporting = true
        ImportOverlay.show(activity, R.string.import_stage_reading)
        activity.lifecycleScope.launch {
            try {
                import(origin)
            } catch (e: NotebookImport.ImportProblem) {
                Slog.d(TAG) { "import problem: ${e.problem}" }
                problem(R.string.import_failed_title, activity.getString(problemBody(e.problem)))
            } catch (e: CloudImportFailure) {
                // Its own type because one of the four is not a plain problem dialog: no connected
                // account is answered with a Connect button (the export upload's precedent).
                Slog.d(TAG) { "cloud import failed: ${e.kind}" }
                cloudProblem(e.kind)
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

    private suspend fun import(origin: Origin) {
        val displayName = when (origin) {
            is Origin.Document -> withContext(Dispatchers.IO) { displayNameOf(origin.uri) }
            // The provider's own name for the file — the extension match rides on it exactly as it
            // rides on a picker's display name.
            is Origin.Cloud -> origin.entry.name
        }
        val cands = candidates.ifEmpty { loadCandidates().also { candidates = it } }
        if (cands.isEmpty()) {
            problem(R.string.import_none_title, activity.getString(R.string.import_none_body))
            return
        }
        // Before any download: a file no importer accepts, or a chooser the person backs out of,
        // must cost no bytes and no waiting.
        val chosen = chooseImporter(cands, displayName) ?: return

        // 1 · Deliver into the cache — fetching it first where the bytes are not on this device.
        val incoming = withContext(Dispatchers.IO) { NotebookImport.prepareCache(activity) }
        val delivery = when (origin) {
            is Origin.Document -> Delivery.Document(origin.uri)
            is Origin.Cloud -> download(origin, incoming, chosen.info.resultKind)
        }
        deliver(chosen, delivery, incoming, displayName)

        // 1b · The fork (arc 19 / M8). Delivery is identical for every importer — two fds, a
        //      verbatim stream, a count checked twice — and the descriptor's result kind is the
        //      only thing that decides what the bytes then ARE. Text takes its own short pipeline
        //      and returns; everything below is the `.soil` one, untouched (an absent tail means
        //      RESULT_NOTEBOOK, so a pre-arc-19 importer lands there exactly as it always did).
        if (ImportRouting.isTextDocument(chosen.info.resultKind)) {
            importTextDocument(incoming, displayName)
            return
        }

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

    // ── The text branch (arc 19 / M8) ────────────────────────────────────────

    /**
     * A `.md` / `.markdown` / `.txt` delivery: decode it, name it, create a text document in the
     * folder the library is standing in, and open it.
     *
     * Short by design, and short because of what it does **not** do. There is no probe, no unlock
     * and no re-key (the bytes are text, not a database), and — the rule — **no question**. A text
     * import always creates something new: it never replaces, so it never asks whether to; a name
     * that clashes with a sibling is deduped silently to `… Copy` ([ImportNames.freeName], the
     * siblings read once). og's rule, and the honest one: the user picked a file to import, not a
     * notebook to overwrite.
     *
     * The cap is checked **first-hand and before the read** — `File.length()` on what actually
     * landed, the arc-16 corroboration discipline — so a 400 MB text file is refused rather than
     * pulled into memory to be measured. [TextImport] then owns everything about the bytes.
     *
     * The text is the user's content: never logged, lengths only.
     */
    private suspend fun importTextDocument(incoming: File, displayName: String) {
        val landed = withContext(Dispatchers.IO) { incoming.length() }
        if (landed > TextImport.MAX_TEXT_BYTES) {
            Log.w(TAG, "text import refused: $landed bytes over the cap")
            throw NotebookImport.ImportProblem(NotebookImport.Problem.TEXT_TOO_LONG)
        }
        // The library's own key — a text document is a `.soil` like any other and is encrypted
        // under it. Absent means nothing has unlocked since a process kill.
        val global = KeySession.get() ?: throw NotebookImport.ImportProblem(NotebookImport.Problem.NO_KEY)
        val text = try {
            TextImport.decode(withContext(Dispatchers.IO) { incoming.readBytes() })
        } catch (e: TextImport.TextProblem) {
            throw NotebookImport.ImportProblem(
                when (e.refusal) {
                    TextImport.Refusal.NOT_TEXT -> NotebookImport.Problem.NOT_TEXT
                    TextImport.Refusal.TOO_LONG -> NotebookImport.Problem.TEXT_TOO_LONG
                },
                e,
            )
        }

        // Where the user is standing, and a name free among its siblings — decided from one read.
        val parentId = currentFolder()
        val taken = repo.notebooks(parentId).map { it.name }.toHashSet()
        val name = ImportNames.freeName(ImportNames.fromDisplayName(displayName), { it in taken })

        ImportOverlay.stage(activity, R.string.import_stage_importing)
        val id = TextDocumentCreate.create(
            context = activity,
            repo = repo,
            name = name,
            parentFolderId = parentId,
            text = text,
            passphrase = global,
        )
        Slog.d(TAG) { "imported ${text.length} chars as a text document" }
        ImportOverlay.hide(activity)
        onImported()
        if (activity.isFinishing || activity.isDestroyed) return
        openImported(id, name)
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
     * **Where the bytes to deliver are** (arc 25 / V5) — a document the picker named, or the copy a
     * cloud provider has already streamed into the import cache. Two questions each: open it for
     * reading, and say what is known about its size.
     *
     * The cache file's own length is a **first-hand** account, so the cloud path's `sizes()` is one
     * number that must agree; a picker's accounts are the provider's and are corroboration only,
     * exactly as arc 16 wrote them.
     */
    private sealed class Delivery {

        abstract fun openRead(context: Context): ParcelFileDescriptor?

        /** Every account of the source's size. Empty when nobody will say. */
        abstract fun sizes(context: Context): List<Long>

        /** The SAF path, unchanged: the resolver opens it and the provider is asked what it knows. */
        class Document(private val uri: Uri) : Delivery() {

            override fun openRead(context: Context): ParcelFileDescriptor? = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")
            }.getOrNull()

            /** The SIZE column and the fd's stat — empty when it will not say at all; neither is
             *  authoritative alone. */
            override fun sizes(context: Context): List<Long> {
                val sizes = ArrayList<Long>(2)
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) sizes += c.getLong(0)
                    }
                }
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        pfd.statSize.takeIf { it >= 0L }?.let { sizes += it }
                    }
                }
                return sizes
            }
        }

        /** A file this app downloaded into its own cache: one first-hand length, no provider to ask. */
        class Cached(private val file: File) : Delivery() {

            override fun openRead(context: Context): ParcelFileDescriptor? = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrNull()

            override fun sizes(context: Context): List<Long> = listOf(file.length())
        }
    }

    /**
     * **The download** (arc 25 / V5). The provider streams the chosen file into a **sibling** of the
     * incoming copy — never over it, because the importer is what turns downloaded bytes into
     * delivered ones and both files must exist while it does. The name of the cache file carries no
     * meaning; the display name travels separately.
     *
     * Three accounts are then corroborated by [CloudImportRules.downloadVerdict] — what the provider
     * says it wrote, what landed, and the size the listing gave. A short download stops the import;
     * a listing that merely disagrees is logged and the import goes on (a listing can lag its own
     * write — the arc's standing trap). Nothing in the cloud is touched, either way: `download` is
     * the only call this whole path makes after `list`.
     */
    private suspend fun download(origin: Origin.Cloud, incoming: File, resultKind: Int): Delivery {
        ImportOverlay.stage(activity, activity.getString(R.string.import_stage_downloading, cloudName()))
        val file = File(File(incoming.parentFile, CLOUD_DIR), CLOUD_FILE)
        val destination = withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                )
            }.getOrNull()
        } ?: throw NotebookImport.ImportProblem(NotebookImport.Problem.WRITE)
        // The client owns the descriptor from here and closes it on every path, refusals included.
        val reported = try {
            CloudClient.download(activity, origin.ref, origin.entry.id, destination)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudNotConnectedException) {
            throw CloudImportFailure(CloudImportFailure.Kind.NOT_CONNECTED, e)
        } catch (e: CloudNetworkException) {
            throw CloudImportFailure(CloudImportFailure.Kind.NETWORK, e)
        } catch (e: Exception) {
            throw CloudImportFailure(CloudImportFailure.Kind.UNANSWERED, e)
        }
        val landed = withContext(Dispatchers.IO) { file.length() }
        when (CloudImportRules.downloadVerdict(reported, landed, origin.entry.sizeBytes)) {
            CloudImportRules.Verdict.SHORT -> {
                Log.w(TAG, "download landed $landed of $reported reported (${origin.entry.sizeBytes} listed) bytes")
                throw NotebookImport.ImportProblem(NotebookImport.Problem.SHORT)
            }
            CloudImportRules.Verdict.DISAGREE ->
                Log.w(TAG, "the listing said ${origin.entry.sizeBytes} for $landed downloaded bytes")
            CloudImportRules.Verdict.OK -> Unit
        }
        // The same rule the delivery keeps, applied a step earlier: no notebook is zero bytes, and
        // streaming nothing into a probe that was always going to refuse it helps nobody.
        if (landed == 0L && ImportRouting.rejectsEmptyDelivery(resultKind)) {
            throw NotebookImport.ImportProblem(NotebookImport.Problem.NOT_A_NOTEBOOK)
        }
        Slog.d(TAG) { "downloaded $landed bytes" }
        return Delivery.Cached(file)
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
    private suspend fun deliver(chosen: Candidate, from: Delivery, incoming: File, displayName: String) {
        val sizes = withContext(Dispatchers.IO) { from.sizes(activity) }
        val source = withContext(Dispatchers.IO) {
            from.openRead(activity)
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
        // Empty is a refusal for a notebook and a legal file for text (arc 19 / M8): no `.soil` is
        // zero bytes, but an empty `.txt` imports as an empty text document, which is exactly what
        // it is. [ImportRouting] holds the rule so it is provable off-device.
        if (landed == 0L && ImportRouting.rejectsEmptyDelivery(chosen.info.resultKind)) {
            throw NotebookImport.ImportProblem(NotebookImport.Problem.NOT_A_NOTEBOOK)
        }
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

    /**
     * The four cloud failures (arc 25 / V5), each said as what it means, and every one of them
     * ending the same way: **nothing was imported**, nothing in the cloud was touched, and the cache
     * goes with the flow's own `finally`.
     *
     * No connected account is the one that gets a button rather than an explanation — Connect is the
     * only thing that helps, and taking it re-reads the status and comes back to the browser.
     */
    private fun cloudProblem(kind: CloudImportFailure.Kind) {
        ImportOverlay.hide(activity)
        if (activity.isFinishing || activity.isDestroyed) return
        val name = cloudName()
        when (kind) {
            CloudImportFailure.Kind.GONE -> Dialogs.problem(
                activity, R.string.import_failed_title, activity.getString(R.string.import_cloud_gone_body),
            )
            CloudImportFailure.Kind.NETWORK -> Dialogs.problem(
                activity, R.string.import_failed_title, activity.getString(R.string.import_cloud_network_body, name),
            )
            CloudImportFailure.Kind.UNANSWERED -> Dialogs.problem(
                activity, R.string.import_failed_title, activity.getString(R.string.import_cloud_unanswered_body, name),
            )
            CloudImportFailure.Kind.NOT_CONNECTED -> Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle(R.string.import_failed_title)
                    .setMessage(activity.getString(R.string.import_cloud_not_connected_body, name))
                    .setPositiveButton(R.string.cloud_connect) { _, _ -> connectAfterFailure() }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
            ).show()
        }
    }

    /** Connect, reached from a failed download. The status is re-read first — the account may
     *  simply have been disconnected elsewhere — and the offer is built on what is true now. */
    private fun connectAfterFailure() {
        if (isBusy || isImporting) { showBusyGuard(); return }
        isBusy = true
        activity.lifecycleScope.launch {
            var handed = false
            try {
                loadCloud()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                handed = offerConnect()
            } finally {
                if (!handed) isBusy = false
            }
        }
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
        NotebookImport.Problem.NOT_TEXT -> R.string.import_not_text_body
        NotebookImport.Problem.TEXT_TOO_LONG -> R.string.import_text_too_long_body
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

        /** Where a cloud download lands: a sibling of the incoming copy inside the same wiped
         *  import cache, under a fixed name — the file's own name carries no meaning here, and the
         *  display name (which does) travels beside it. */
        const val CLOUD_DIR = "cloud"
        const val CLOUD_FILE = "download.bin"
    }
}
