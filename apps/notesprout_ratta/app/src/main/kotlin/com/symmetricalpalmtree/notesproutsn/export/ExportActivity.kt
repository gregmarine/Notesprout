package com.symmetricalpalmtree.notesproutsn.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityExportBinding
import com.symmetricalpalmtree.notesproutsn.extension.ExporterClient
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.ExportSpec
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.ProviderRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Export** (arc 15 / E1) — the host's whole side of getting a notebook out of the app.
 *
 * The seam in one sentence: *the host keys, the extension delivers.* This screen owns the entry, the
 * choice of format, the options panel, the transient checkpoint, the cache copy and the SAF
 * destination; an exporter extension receives two `ParcelFileDescriptor`s and an [ExportSpec] and
 * produces the bytes. No passphrase, path or SQLCipher ever crosses.
 *
 * What the screen does, in order, and each step is a rule:
 *
 *  1. **Discover and describe.** Every trusted exporter is asked what it offers. A `describe()` that
 *     fails, or a descriptor this build cannot draw ([ExportOptions.isRenderable] — E1 renders
 *     single-choice and toggle, not passphrase), **drops that exporter with a log line, never a
 *     crash**. None left is a problem dialog and the screen closes: a screen with nothing on it
 *     would be a tap that did nothing.
 *  2. **Show what there is to decide.** One exporter collapses the chooser to a plain label, and a
 *     single-choice option with one choice collapses the same way — a control that cannot be
 *     operated reads as broken, not as settled. Re-run on every resume, because a package can be
 *     disabled or replaced under a standing screen.
 *  3. **Export → the picker.** SAF `ACTION_CREATE_DOCUMENT` with the exporter's MIME type and the
 *     filename [ExportNaming] made from the index name. (An `ActivityResultContracts.CreateDocument`
 *     takes its MIME at *registration*, and this screen does not know it until `describe()` has
 *     answered — so it is the family's explicit-Intent form, as the templates screen uses.)
 *  4. **Prepare, hand over, verify.** [ExportArtifact] seals a cold copy into the cache; the fds are
 *     opened; `export()` runs under its own timeout; and the byte count is checked against the
 *     artifact — **and against the destination where the provider can answer** — before anything
 *     says the word "exported". An exporter that died mid-stream must never read as success.
 *  5. **Toast and finish**, back to the library: a toast only ever confirms something that has
 *     already happened. Every failure instead deletes the half-written destination and explains
 *     itself in a dialog naming what went wrong — never a path, never a secret.
 */
class ExportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportBinding
    private lateinit var panel: ExportPanel
    private val repo by lazy { IndexRepository() }

    private lateinit var notebookId: String
    private lateinit var notebookName: String

    /** One installed exporter and what it said it offers. */
    private class Candidate(val ref: ProviderRef, val info: ExporterInfo)

    private var candidates: List<Candidate> = emptyList()

    /** The pick is state the **host** owns (the G6 lesson) — saved, restored, re-matched by package. */
    private var chosenPackage: String? = null

    /** Option id → chosen value, for the exporter in [chosenPackage]. Validated again at spec time. */
    private val values = LinkedHashMap<String, String>()

    /** True from the Export tap until the flow ends. A second tap in the e-ink feedback gap does
     *  nothing — and it also stands down the resume-time re-discovery, which would otherwise
     *  rebuild the panel under an export that is already running. */
    private var busy = false

    private var discovering = false

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runExport(uri)
        } else {
            // Cancelled at the picker: nothing was created, nothing to explain, screen stays.
            busy = false
            Slog.d(TAG) { "destination picker cancelled" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        notebookId = intent.getStringExtra(EXTRA_NOTEBOOK_ID).orEmpty()
        notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME).orEmpty()
        if (notebookId.isEmpty()) { finish(); return }

        binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The guard is 0 on Ratta — chrome sits flush at the top edge; the inset pass is still how
        // the screen clears a navigation bar if the device has one.
        TopGuard.applyInsetPadding(binding.root)
        panel = ExportPanel(this)

        binding.notebookName.text = notebookName
        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnExport.setOnClickListener { onExportTap() }

        savedInstanceState?.let { state ->
            chosenPackage = state.getString(KEY_PACKAGE)
            state.getBundle(KEY_VALUES)?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { values[k] = it } } }
        }
        discover()
    }

    override fun onResume() {
        super.onResume()
        // A package can be disabled or replaced under a standing screen — but not under a running
        // export, which is also the resume that arrives when the picker comes back.
        if (!busy && ::binding.isInitialized) discover()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PACKAGE, chosenPackage)
        outState.putBundle(KEY_VALUES, Bundle().also { b -> values.forEach { (k, v) -> b.putString(k, v) } })
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    /**
     * Ask every trusted exporter what it offers. Inward is untrusted: a call that fails and a
     * descriptor over the [ExporterContract] caps both surface as an exception inside `describe()`,
     * and either way that exporter is simply not in the list.
     */
    private fun discover() {
        if (discovering) return
        discovering = true
        lifecycleScope.launch {
            val kept = try {
                loadCandidates()
            } finally {
                discovering = false
            }
            if (isFinishing || isDestroyed) return@launch
            candidates = kept
            Slog.d(TAG) { "${kept.size} usable exporter(s)" }
            if (kept.isEmpty()) {
                problemAndClose(R.string.export_none_title, R.string.export_none_body)
                return@launch
            }
            val standing = kept.firstOrNull { it.ref.packageName == chosenPackage }
            // A re-discovery keeps what the user already answered — the descriptor is usually the
            // same one — and falling back to another exporter starts from its own defaults.
            select(standing ?: kept.first(), keepValues = standing != null)
        }
    }

    /** Discovery + `describe()` with nothing of the screen in it, so the export flow can run it too
     *  when it finds itself rebuilt behind the picker with no descriptors in hand. */
    private suspend fun loadCandidates(): List<Candidate> {
        val refs = ExtensionRegistry.exporters(this)
        val kept = ArrayList<Candidate>(refs.size)
        for (ref in refs) {
            val info = describe(ref) ?: continue
            if (!ExportOptions.isRenderable(info)) {
                Slog.d(TAG) { "dropping ${ref.packageName}: an option kind this build cannot draw" }
                continue
            }
            kept += Candidate(ref, info)
        }
        return kept
    }

    private suspend fun describe(ref: ProviderRef): ExporterInfo? = try {
        ExporterClient(this, ref).describe()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Slog.d(TAG) { "dropping ${ref.packageName}: describe failed (${e.message})" }
        null
    }

    /**
     * Adopt [c] as the chosen exporter and rebuild the panel from its descriptor. The values go
     * through [ExportOptions.specValues], which is what guarantees the panel is showing exactly
     * what an export would send: a value that is not a declared choice becomes that option's
     * default here, not silently at spec time.
     */
    private fun select(c: Candidate, keepValues: Boolean) {
        chosenPackage = c.ref.packageName
        val merged = ExportOptions.specValues(c.info, if (keepValues) values else emptyMap())
        values.clear()
        values.putAll(merged)
        render()
    }

    private fun current(): Candidate? = candidates.firstOrNull { it.ref.packageName == chosenPackage }

    // ── The panel ────────────────────────────────────────────────────────────

    /** The chooser and the options, rebuilt whole after every pick — one frame, one deliberate act. */
    private fun render() {
        val c = current() ?: return
        binding.chooser.removeAllViews()
        if (candidates.size == 1) {
            // No radio for a choice that does not exist.
            binding.chooser.addView(panel.value(c.info.formatLabel))
        } else {
            for (candidate in candidates) {
                val checked = candidate.ref.packageName == chosenPackage
                binding.chooser.addView(
                    panel.choice(candidate.info.formatLabel, checked) { select(candidate, keepValues = false) }
                )
            }
        }

        binding.options.removeAllViews()
        for (d in c.info.options) {
            when {
                ExportOptions.isFixed(d) -> {
                    binding.options.addView(panel.caption(d.label))
                    binding.options.addView(
                        panel.value(ExportOptions.choiceLabel(d, values[d.id] ?: d.defaultValue))
                    )
                }
                d.kind == ExporterContract.KIND_SINGLE_CHOICE -> {
                    binding.options.addView(panel.caption(d.label))
                    d.choiceIds.forEachIndexed { i, choiceId ->
                        binding.options.addView(
                            panel.choice(d.choiceLabels[i], values[d.id] == choiceId) {
                                values[d.id] = choiceId
                                render()
                            }
                        )
                    }
                }
                d.kind == ExporterContract.KIND_TOGGLE -> {
                    val on = values[d.id] == "1"
                    binding.options.addView(
                        panel.toggle(d.label, on) {
                            values[d.id] = if (on) "0" else "1"
                            render()
                        }
                    )
                }
                // No other kind reaches here: an exporter declaring one was dropped at discovery.
            }
        }
    }

    private fun showBusy(running: Boolean) {
        binding.status.visibility = if (running) View.VISIBLE else View.GONE
    }

    // ── The export ───────────────────────────────────────────────────────────

    /** Ask where to put it. The filename is offered, not imposed — the picker's field is the user's. */
    private fun onExportTap() {
        if (busy) { Slog.d(TAG) { "export tap ignored: already running" }; return }
        val c = current() ?: return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(c.info.mimeType)
            .putExtra(
                Intent.EXTRA_TITLE,
                ExportNaming.suggestedFileName(notebookName, notebookId, c.info.fileExtension),
            )
        busy = true
        try {
            saveLauncher.launch(intent)
        } catch (e: Exception) {
            busy = false
            Log.w(TAG, "no document creator: $e")
            Dialogs.problem(this, R.string.export_no_picker_title, R.string.export_no_picker_body)
        }
    }

    /**
     * The whole flow behind the picker: prepare, hand over, verify, confirm. Every failure deletes
     * the document the picker created — a partial file must never stand there silently — and says
     * what happened.
     */
    private fun runExport(uri: Uri) {
        busy = true
        showBusy(true)
        lifecycleScope.launch {
            try {
                // DocumentsUI is another process on a memory-tight e-ink device, so this screen can
                // be rebuilt behind it: the result arrives before the recreated screen's discovery
                // has answered. Ask again rather than drop the tap and leave an empty document.
                val c = current() ?: reselectAfterRestore()
                if (c == null) {
                    fail(uri, R.string.export_failed_title, getString(R.string.export_gone_body))
                    return@launch
                }
                val prepared = ExportArtifact.prepare(applicationContext, notebookId, repo, versionCode())
                if (prepared is ExportArtifact.Outcome.Failed) {
                    fail(uri, R.string.export_failed_title, prepareMessage(prepared.problem))
                    return@launch
                }
                val artifact = (prepared as ExportArtifact.Outcome.Ready)

                val spec = try {
                    ExportSpec(
                        values = ExportOptions.specValues(c.info, values),
                        notebookName = ExportNaming.specName(notebookName, notebookId),
                    )
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "spec rejected", e)
                    fail(uri, R.string.export_failed_title, getString(R.string.export_failed_body))
                    return@launch
                }

                val source = withContext(Dispatchers.IO) {
                    runCatching {
                        ParcelFileDescriptor.open(artifact.file, ParcelFileDescriptor.MODE_READ_ONLY)
                    }.getOrNull()
                }
                if (source == null) {
                    fail(uri, R.string.export_failed_title, getString(R.string.export_prepare_failed_body))
                    return@launch
                }
                val destination = withContext(Dispatchers.IO) { openDestination(uri) }
                if (destination == null) {
                    withContext(Dispatchers.IO) { runCatching { source.close() } }
                    fail(uri, R.string.export_failed_title, getString(R.string.export_destination_body))
                    return@launch
                }

                // Both descriptors are the client's from here — it closes them in `finally`,
                // success, failure or timeout.
                val result = try {
                    ExporterClient(this@ExportActivity, c.ref).export(source, destination, spec)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Slog.d(TAG) { "export call failed: ${e.message}" }
                    fail(uri, R.string.export_failed_title, getString(R.string.export_failed_body))
                    return@launch
                }

                if (result.bytesWritten != artifact.bytes) {
                    Log.w(TAG, "short export: ${result.bytesWritten} of ${artifact.bytes} bytes")
                    fail(uri, R.string.export_failed_title, getString(R.string.export_short_body))
                    return@launch
                }
                val onDisk = withContext(Dispatchers.IO) { destinationSize(uri) }
                if (onDisk != null && onDisk != artifact.bytes) {
                    Log.w(TAG, "destination holds $onDisk of ${artifact.bytes} bytes")
                    fail(uri, R.string.export_failed_title, getString(R.string.export_short_body))
                    return@launch
                }

                Slog.d(TAG) { "exported ${artifact.bytes} bytes" }
                if (isFinishing || isDestroyed) return@launch
                Toast.makeText(this@ExportActivity, R.string.export_done_toast, Toast.LENGTH_SHORT).show()
                finish()
            } finally {
                // The artifact is a copy of the user's notes; it has no business outliving the
                // export that made it, whichever way the export ended — a screen destroyed
                // mid-export included, which is why the wipe is NonCancellable: a plain
                // withContext in an already-cancelled scope throws before it runs anything.
                withContext(NonCancellable + Dispatchers.IO) { ExportArtifact.clean(applicationContext) }
                busy = false
                if (!isFinishing && !isDestroyed) showBusy(false)
            }
        }
    }

    /**
     * Re-run discovery for an export whose screen was rebuilt behind the picker, and take back the
     * exporter the user actually chose. **Only that one**: falling back to whatever else is
     * installed would export a different format into a file already named for the first, so a
     * chosen exporter that has gone is a problem dialog, not a substitution.
     */
    private suspend fun reselectAfterRestore(): Candidate? {
        candidates = loadCandidates()
        val pick = candidates.firstOrNull { it.ref.packageName == chosenPackage }
            ?: candidates.takeIf { chosenPackage == null }?.firstOrNull()
        if (pick != null && !isFinishing && !isDestroyed) select(pick, keepValues = true)
        return pick
    }

    /** Best-effort truncating write handle. Providers differ on which modes they accept, so the
     *  documented one is tried first and plain "w" is the fallback. */
    private fun openDestination(uri: Uri): ParcelFileDescriptor? {
        for (mode in arrayOf("rwt", "wt", "w")) {
            val pfd = runCatching { contentResolver.openFileDescriptor(uri, mode) }.getOrNull()
            if (pfd != null) return pfd
        }
        Log.w(TAG, "could not open the destination for writing")
        return null
    }

    /** What the destination provider says it now holds, or null when it will not say. */
    private fun destinationSize(uri: Uri): Long? {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        }
        runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                return pfd.statSize.takeIf { it >= 0L }
            }
        }
        return null
    }

    /**
     * Every failure path ends here: the created document goes (a half-written export standing in
     * the user's files under a name that says "notebook" is worse than none at all), then the
     * dialog. No path and no secret in the message.
     */
    private suspend fun fail(uri: Uri, @StringRes titleRes: Int, message: String) {
        withContext(Dispatchers.IO) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                .onFailure { Log.w(TAG, "could not remove the partial export: ${it.message}") }
        }
        if (isFinishing || isDestroyed) return
        Dialogs.problem(this, titleRes, message)
    }

    private fun prepareMessage(problem: ExportArtifact.Problem): String = getString(
        when (problem) {
            ExportArtifact.Problem.IN_USE -> R.string.export_in_use_body
            ExportArtifact.Problem.NO_KEY -> R.string.export_locked_body
            ExportArtifact.Problem.MISSING -> R.string.export_missing_body
            ExportArtifact.Problem.UNREADABLE -> R.string.export_unreadable_body
            ExportArtifact.Problem.COPY_FAILED -> R.string.export_prepare_failed_body
        }
    )

    /** A dialog that explains why there is nothing to do here, and closes the screen behind it. */
    private fun problemAndClose(@StringRes titleRes: Int, @StringRes bodyRes: Int) {
        if (isFinishing || isDestroyed) { finish(); return }
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(R.string.ok, null)
                .create()
        ).also { it.setOnDismissListener { finish() } }.show()
    }

    private fun versionCode(): Int = runCatching {
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    companion object {
        private const val TAG = "ExportActivity"
        private const val KEY_PACKAGE = "export.package"
        private const val KEY_VALUES = "export.values"

        /** Identity travels as id + name — never a `File`, never a path (the family rule). */
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        fun intent(context: Context, notebookId: String, notebookName: String): Intent =
            Intent(context, ExportActivity::class.java)
                .putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                .putExtra(EXTRA_NOTEBOOK_NAME, notebookName)
    }
}
