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
import androidx.activity.OnBackPressedCallback
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
import com.symmetricalpalmtree.notesproutsn.crypto.ExportKeying
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
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
import java.io.File

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
 *  4. **Prepare, key, hand over, verify.** What gets prepared depends on the exporter's declared
 *     source kind, and that is the only place the two kinds differ (arc 18 / D1): a
 *     [ExporterContract.SOURCE_SOIL] exporter streams the `.soil` — [ExportArtifact] seals a cold
 *     copy into the cache and [ExportKeying] runs the reserved keying option's transform on it (E2
 *     — the exporter never learns which, and the typed passphrase stays in this process) — while a
 *     [ExporterContract.SOURCE_PAGES] exporter never could (no key crosses), so [ExportRender]
 *     draws every page here and hands over a bundle of images, with no keying step at all. From
 *     the fds on, the flow does not ask which it was. Then `export()` runs under its own timeout
 *     and [ExportVerification] judges the result **per source kind** before anything says the word
 *     "exported". An exporter that died mid-stream must never read as success.
 *  5. **Confirm and finish**, back to the library: a dialog, not a toast, because this screen is
 *     closing under it and a toast would confirm something the user no longer has a screen to read.
 *     Every failure instead explains itself in a dialog naming what went wrong —
 *     never a path, never a secret — and removes the half-written destination where removing it
 *     cannot cost the user anything: a pre-existing file the picker offered to overwrite is
 *     deleted only after the truncating open has already destroyed its old content, and the
 *     dialog says what actually happened to the file either way.
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

    /** The passphrase typed for a *rekey*, held only from the Export tap to the end of the flow.
     *  It is never saved into instance state, never put in an Intent, never logged — the host
     *  collects it, the host consumes it, and nothing about it crosses the exporter seam. */
    private var typedPassphrase: String? = null

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runExport(uri)
        } else {
            // Cancelled at the picker: nothing was created, nothing to explain, screen stays.
            // The secret collected at the tap goes with the flow it was collected for — its
            // documented lifetime ends here, not at the next tap (arc-15 review).
            typedPassphrase = null
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
        // Leaving mid-export would cancel the flow past its verification and cleanup while the
        // extension's un-cancellable Binder stream keeps writing — an unverified file standing
        // silently (arc-15 review). Both doors out are latched on busy; the dialog explains why
        // the tap did nothing (the toast-vs-dialog rule).
        binding.btnBack.setOnClickListener { if (busy) showBusyGuard() else finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) showBusyGuard() else finish()
            }
        })
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
            // Never under a running export (arc-15 review): a screen rebuilt behind the picker
            // starts this discovery from onCreate, and the pending SAF result lands before the
            // continuation — a substitution or a problemAndClose here would swap the exporter
            // (values reset, keying back to Keep) or close the screen under the flow. runExport
            // does its own no-substitution reselect.
            if (busy || isFinishing || isDestroyed) return@launch
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
        // A different exporter is a different question: what was typed for the last one goes with it.
        if (!keepValues) {
            binding.editPassphrase.setText("")
            binding.editPassphraseConfirm.setText("")
        }
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

        // The two keying consequences the host owns (E2). Both are XML-static, so a half-typed
        // passphrase survives the rebuild the options loop above just did.
        val info = c.info
        binding.passphraseBlock.visibility =
            if (ExportOptions.needsPassphrase(info, values)) View.VISIBLE else View.GONE
        binding.plainWarning.visibility =
            if (ExportOptions.showsPlainWarning(info, values)) View.VISIBLE else View.GONE
    }

    private fun showBusy(running: Boolean) {
        binding.status.visibility = if (running) View.VISIBLE else View.GONE
    }

    /** The one line of running commentary this screen has — the stages are long enough on e-ink
     *  that a single unchanging "Exporting…" would read as a stall. */
    private fun stage(@StringRes textRes: Int) {
        binding.status.setText(textRes)
    }

    /** The same line with a count in it — the render's per-page commentary. Main thread only. */
    private fun stage(text: String) {
        binding.status.text = text
    }

    // ── The export ───────────────────────────────────────────────────────────

    /**
     * Ask where to put it. The filename is offered, not imposed — the picker's field is the user's.
     *
     * A rekey's fields are checked **before** the picker, because a passphrase problem found after
     * the user has named a file is a dialog on top of a document that then has to be deleted. Both
     * refusals are dialogs, not toasts: each explains why the tap did nothing. The IME is left
     * exactly as it is — on Ratta, hiding it takes the hardware keys with it.
     */
    private fun onExportTap() {
        if (busy) { Slog.d(TAG) { "export tap ignored: already running" }; return }
        val c = current() ?: return
        if (ExportOptions.needsPassphrase(c.info, values)) {
            val typed = binding.editPassphrase.text?.toString().orEmpty()
            val confirm = binding.editPassphraseConfirm.text?.toString().orEmpty()
            if (typed.isEmpty() || confirm.isEmpty()) {
                Dialogs.problem(
                    this, R.string.export_passphrase_missing_title, R.string.export_passphrase_missing_body
                )
                return
            }
            if (typed != confirm) {
                Dialogs.problem(
                    this, R.string.export_passphrase_mismatch_title, R.string.export_passphrase_mismatch_body
                )
                return
            }
            typedPassphrase = typed
        } else {
            typedPassphrase = null
        }
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
        stage(R.string.export_preparing)
        showBusy(true)
        lifecycleScope.launch {
            // What a failure may delete (arc-15 review): the picker's overwrite confirmation hands
            // back a PRE-EXISTING document's URI, and a failure that never wrote a byte must not
            // take the user's previous good file with it. Deletion is allowed only once the
            // truncating open has destroyed the old content anyway — or when the document was
            // verifiably empty to begin with (a fresh creation).
            val sizesAtStart = withContext(Dispatchers.IO) { destinationSizes(uri) }
            val emptyAtStart = sizesAtStart.isNotEmpty() && sizesAtStart.all { it == 0L }
            var destinationTouched = false
            suspend fun failed(@StringRes titleRes: Int, message: String) =
                fail(uri, titleRes, message, mayDelete = destinationTouched || emptyAtStart)
            try {
                // DocumentsUI is another process on a memory-tight e-ink device, so this screen can
                // be rebuilt behind it: the result arrives before the recreated screen's discovery
                // has answered. Ask again rather than drop the tap and leave an empty document.
                val c = current() ?: reselectAfterRestore()
                if (c == null) {
                    failed(R.string.export_failed_title, getString(R.string.export_gone_body))
                    return@launch
                }
                // Built before either source kind's preparation: the spec depends on neither, and a
                // spec the contract rejects has no business costing a cache copy or a page bake
                // first. `exportSecret` stays absent — D1 collects no export secret, and D2 is
                // where the host starts sending one.
                val spec = try {
                    ExportSpec(
                        values = ExportOptions.specValues(c.info, values),
                        notebookName = ExportNaming.specName(notebookName, notebookId),
                    )
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "spec rejected", e)
                    failed(R.string.export_failed_title, getString(R.string.export_failed_body))
                    return@launch
                }

                // The two source kinds part here and rejoin at the fds — a keyed `.soil` artifact,
                // or a bundle of pages this host drew. From the open below, nothing asks which.
                val prepared = if (c.info.sourceKind == ExporterContract.SOURCE_PAGES) {
                    renderedPages()
                } else {
                    keyedArtifact(c)
                }
                val streamFile = when (prepared) {
                    is StreamSource.Failed -> {
                        failed(R.string.export_failed_title, prepared.message)
                        return@launch
                    }
                    is StreamSource.Ready -> prepared.file
                }
                val streamBytes = withContext(Dispatchers.IO) { streamFile.length() }
                stage(R.string.export_exporting)

                val source = withContext(Dispatchers.IO) {
                    runCatching {
                        ParcelFileDescriptor.open(streamFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    }.getOrNull()
                }
                if (source == null) {
                    failed(R.string.export_failed_title, getString(R.string.export_prepare_failed_body))
                    return@launch
                }
                val destination = withContext(Dispatchers.IO) { openDestination(uri) }
                if (destination == null) {
                    withContext(Dispatchers.IO) { runCatching { source.close() } }
                    failed(R.string.export_failed_title, getString(R.string.export_destination_body))
                    return@launch
                }
                // The truncating open has run: whatever the document held is gone, and from here a
                // failure's delete removes only wreckage, never the user's old file.
                destinationTouched = true

                // Both descriptors are the client's from here — it closes them in `finally`,
                // success, failure or timeout.
                val result = try {
                    ExporterClient(this@ExportActivity, c.ref).export(source, destination, spec)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Slog.d(TAG) { "export call failed: ${e.message}" }
                    failed(R.string.export_failed_title, getString(R.string.export_failed_body))
                    return@launch
                }

                // What "exported" is allowed to mean lives in ExportVerification, per source kind
                // (arc 18 / D1): the verbatim-streaming equality against the file actually handed
                // over is the soil contract and no other, while the destination's own account is
                // corroboration for both — never authority (arc-15 review). A cloud provider's
                // metadata can lag the write it just took, and deleting a fully-written export
                // over a stale answer would destroy the very thing that was just made; any
                // agreeing answer is enough, and a unanimous disagreement is an honest
                // check-the-file dialog rather than a delete.
                val onDisk = withContext(Dispatchers.IO) { destinationSizes(uri) }
                when (ExportVerification.verdict(c.info.sourceKind, result.bytesWritten, streamBytes, onDisk)) {
                    ExportVerification.Verdict.SHORT -> {
                        Log.w(TAG, "short export: ${result.bytesWritten} written, $streamBytes streamed, destination $onDisk")
                        failed(R.string.export_failed_title, getString(R.string.export_short_body))
                        return@launch
                    }
                    ExportVerification.Verdict.UNCONFIRMED -> {
                        Log.w(TAG, "destination reports $onDisk for ${result.bytesWritten} bytes")
                        if (!isFinishing && !isDestroyed) {
                            Dialogs.problem(
                                this@ExportActivity, R.string.export_verify_title, getString(R.string.export_verify_body)
                            )
                        }
                        return@launch
                    }
                    ExportVerification.Verdict.OK -> Unit
                }

                Slog.d(TAG) { "exported ${result.bytesWritten} bytes" }
                if (isFinishing || isDestroyed) return@launch
                Dialogs.confirm(this@ExportActivity, R.string.export_done_title, R.string.export_done_body) {
                    finish()
                }
            } finally {
                // The artifact is a copy of the user's notes; it has no business outliving the
                // export that made it, whichever way the export ended — a screen destroyed
                // mid-export included, which is why the wipe is NonCancellable: a plain
                // withContext in an already-cancelled scope throws before it runs anything.
                // The wipe takes the transform's output with it — it is a sibling in the same
                // cache directory — and the secret that made it has no business outliving the
                // flow either.
                withContext(NonCancellable + Dispatchers.IO) { ExportArtifact.clean(applicationContext) }
                typedPassphrase = null
                busy = false
                if (!isFinishing && !isDestroyed) showBusy(false)
            }
        }
    }

    /** The file the exporter will actually stream, or the sentence saying why there is none. The
     *  two source kinds answer with the same two shapes, which is what lets the flow stop caring
     *  which one it asked at the line after this. */
    private sealed class StreamSource {
        class Ready(val file: File) : StreamSource()
        class Failed(val message: String) : StreamSource()
    }

    /**
     * [ExporterContract.SOURCE_SOIL]: the cold cache copy, then the host-executed keying step (E2).
     * The transform runs on the artifact, beside the crypto, and produces the file the exporter
     * will stream — which from there on is the only file this flow talks about.
     */
    private suspend fun keyedArtifact(c: Candidate): StreamSource {
        val prepared = ExportArtifact.prepare(applicationContext, notebookId, repo, versionCode())
        if (prepared is ExportArtifact.Outcome.Failed) {
            return StreamSource.Failed(prepareMessage(prepared.problem))
        }
        val artifact = prepared as ExportArtifact.Outcome.Ready
        val keying = ExportOptions.keying(c.info, values)
        val plan = try {
            ExportKeying.plan(keying, typedPassphrase != null)
        } catch (e: IllegalArgumentException) {
            // Rekey armed with nothing in hand: the screen was rebuilt behind the picker and the
            // fields went with it (saveEnabled=false, deliberately). Say so — a silent Keep would
            // hand the user a file keyed the way they asked it not to be.
            Log.w(TAG, "keying plan rejected: ${e.javaClass.simpleName}")
            return StreamSource.Failed(getString(R.string.export_passphrase_lost_body))
        }
        if (plan == ExportKeying.Plan.KEEP) return StreamSource.Ready(artifact.file)
        // Both transforms read the artifact, so both need the device key. Keep needs none, which is
        // why the session is only asked here.
        val devicePassphrase = KeySession.get()
            ?: return StreamSource.Failed(getString(R.string.export_locked_body))
        stage(if (plan == ExportKeying.Plan.REKEY) R.string.export_rekeying else R.string.export_decrypting)
        return try {
            StreamSource.Ready(ExportKeying.apply(artifact.file, devicePassphrase, plan, typedPassphrase))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class name only: a transform message can carry a path, and a passphrase is never
            // anywhere near a log line.
            Log.w(TAG, "keying transform failed: ${e.javaClass.simpleName}")
            StreamSource.Failed(getString(R.string.export_transform_body))
        }
    }

    /**
     * [ExporterContract.SOURCE_PAGES]: the host draws the notebook and hands over the pages.
     *
     * **No keying, and nothing to skip past.** Such an exporter declares no keying option, so
     * [ExportOptions.needsPassphrase] was false at the tap and there is no typed secret in this
     * process to consult — the key is used here only to open the notebook for reading, and never
     * leaves. The stage line carries the page count because a long notebook is otherwise a long
     * silence; the callback arrives on IO, so it hops to Main to touch the view.
     */
    private suspend fun renderedPages(): StreamSource {
        val outcome = ExportRender.render(applicationContext, notebookId) { page, count ->
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) stage(getString(R.string.export_rendering, page, count))
            }
        }
        return when (outcome) {
            is ExportRender.Outcome.Ready -> StreamSource.Ready(outcome.file)
            is ExportRender.Outcome.Failed -> StreamSource.Failed(renderMessage(outcome.problem))
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

    /** Every account the destination provider will give of what it now holds — the SIZE column and
     *  the reopened fd's stat, in that order. Empty when it will not say at all. Two answers rather
     *  than one because neither is authoritative alone: a cloud provider's metadata can lag a write
     *  it just took, and a proxy fd can refuse to stat (arc-15 review). */
    private fun destinationSizes(uri: Uri): List<Long> {
        val sizes = ArrayList<Long>(2)
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) sizes += c.getLong(0)
            }
        }
        runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize.takeIf { it >= 0L }?.let { sizes += it }
            }
        }
        return sizes
    }

    /** The busy latch's voice: why the door out did nothing (a silent ignore reads as broken). */
    private fun showBusyGuard() {
        Dialogs.problem(this, R.string.export_busy_title, getString(R.string.export_busy_body))
    }

    /**
     * Every failure path ends here. The created document goes **only when [mayDelete]** — the
     * caller answers for whether what stands at [uri] is this export's wreckage (a truncating
     * open ran, or the document was verifiably empty at the start) or a pre-existing file the
     * picker offered to overwrite, which a failure that never wrote a byte must not destroy
     * (arc-15 review). The dialog then says what actually happened to the file — removed, possibly
     * remaining, or untouched — because the delete is best-effort and a claim of removal that
     * did not happen is a partial file the user was told is gone. No path and no secret in the
     * message.
     */
    private suspend fun fail(uri: Uri, @StringRes titleRes: Int, message: String, mayDelete: Boolean) {
        val removed = if (mayDelete) withContext(Dispatchers.IO) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                .onFailure { Log.w(TAG, "could not remove the partial export: ${it.message}") }
                .getOrDefault(false)
        } else false
        if (isFinishing || isDestroyed) return
        val note = getString(
            when {
                removed -> R.string.export_removed_note
                mayDelete -> R.string.export_remains_note
                else -> R.string.export_untouched_note
            }
        )
        Dialogs.problem(this, titleRes, "$message $note")
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

    /** The render's problems in the same voice — what went wrong, and that the notebook is as it
     *  was. Four of the six are the prepare's own refusals by another road, and say the same thing. */
    private fun renderMessage(problem: ExportRender.Problem): String = getString(
        when (problem) {
            ExportRender.Problem.IN_USE -> R.string.export_in_use_body
            ExportRender.Problem.NO_KEY -> R.string.export_locked_body
            ExportRender.Problem.MISSING -> R.string.export_missing_body
            ExportRender.Problem.UNREADABLE -> R.string.export_unreadable_body
            ExportRender.Problem.EMPTY -> R.string.export_empty_body
            ExportRender.Problem.RENDER_FAILED -> R.string.export_render_failed_body
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
