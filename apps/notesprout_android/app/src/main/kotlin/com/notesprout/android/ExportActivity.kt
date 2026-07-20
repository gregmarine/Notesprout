package com.notesprout.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.TopGuard
import com.notesprout.android.crypto.EncryptionInfo
import com.notesprout.android.crypto.KeyResolver
import com.notesprout.android.crypto.KeyScope
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.crypto.PassphrasePrompt
import com.notesprout.android.crypto.PassphraseStore
import com.notesprout.android.data.PageRef
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.loadPageRefs
import com.notesprout.android.data.soilFile
import com.notesprout.android.databinding.ActivityExportBinding
import com.notesprout.android.export.ExportDelivery
import com.notesprout.android.export.ExportDestination
import com.notesprout.android.export.ExportEngine
import com.notesprout.android.export.ExportFormat
import com.notesprout.android.export.ExportNaming
import com.notesprout.android.export.ExportSpec
import com.notesprout.android.export.PageScope
import com.notesprout.android.export.SoilKeying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single export screen.
 *
 * Replaces the three near-identical dialog chains that used to live in [NotebookActivity],
 * [MainActivity] and [PageIndexActivity] — format sheet → sub-choice → encryption prompt →
 * progress dialog → save/share dialog. Everything is now visible at once and the Export button
 * runs the whole job, destination included.
 *
 * Callers pass notebook identity plus optional page context; see [intentFor]. The screen opens the
 * `.soil` itself (read-only, via [loadPageRefs]) and never receives a `File` or a live database
 * handle, per the architecture rule.
 *
 * **Callers must flush unsaved ink before launching** — this screen renders from the file on disk,
 * so anything still sitting in a live drawing view will silently be missing from the output.
 */
class ExportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTEBOOK_ID = "notebook_id"
        const val EXTRA_NOTEBOOK_NAME = "notebook_name"
        const val EXTRA_CURRENT_PAGE_ID = "current_page_id"
        const val EXTRA_SELECTED_PAGE_IDS = "selected_page_ids"

        /**
         * Build the launch intent. [currentPageId] enables the "Current page" scope;
         * [selectedPageIds] (display-ordered) enables and pre-selects the "Selected pages" scope.
         */
        fun intentFor(
            context: Context,
            notebookId: String,
            notebookName: String,
            currentPageId: String? = null,
            selectedPageIds: List<String>? = null,
        ): Intent = Intent(context, ExportActivity::class.java).apply {
            putExtra(EXTRA_NOTEBOOK_ID, notebookId)
            putExtra(EXTRA_NOTEBOOK_NAME, notebookName)
            currentPageId?.let { putExtra(EXTRA_CURRENT_PAGE_ID, it) }
            selectedPageIds?.takeIf { it.isNotEmpty() }
                ?.let { putStringArrayListExtra(EXTRA_SELECTED_PAGE_IDS, ArrayList(it)) }
        }
    }

    private lateinit var binding: ActivityExportBinding

    private val indexRepo: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    private lateinit var notebookId: String
    private lateinit var notebookName: String
    private lateinit var soilPath: String
    private var currentPageId: String? = null
    private var selectedPageIds: List<String> = emptyList()

    /** Every page in the notebook, display-ordered. Loaded once in [onCreate]. */
    private var allPages: List<PageRef> = emptyList()

    private var encryptionInfo: EncryptionInfo = EncryptionInfo.NONE
    private var passphrase: String? = null
    /** True when the notebook is encrypted but its key could not be resolved — export is blocked. */
    private var locked = false

    // ── User selections ──────────────────────────────────────────────────────

    private var scope = PageScope.ALL
    private var format = ExportFormat.PDF
    private var destination = ExportDestination.SAVE
    private var keying = SoilKeying.KEEP
    /** Set when "Protect PDF with a password" is checked and a password has been entered. */
    private var pdfPassword: String? = null
    /** Set when "Set a new passphrase…" is chosen and a passphrase has been entered. */
    private var newSoilPassphrase: String? = null

    private var runningJob: Job? = null

    // Eagerly constructed on purpose: ExportDelivery registers activity-result launchers in its
    // constructor, and those must be registered before the activity reaches STARTED. A `by lazy`
    // here compiles fine and then throws IllegalStateException the first time an export completes.
    private val delivery = ExportDelivery(
        activity = this,
        // Deferred so the index isn't touched during field initialization.
        indexRepo = { indexRepo },
        onFinished = { message ->
            if (message.isNotEmpty()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
        },
        // Backing out of a picker returns to the options rather than discarding the export.
        onCancelled = { showOptions() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        notebookId = intent.getStringExtra(EXTRA_NOTEBOOK_ID) ?: run { finish(); return }
        notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: "notebook"
        soilPath = soilFile(this, notebookId).absolutePath
        currentPageId = intent.getStringExtra(EXTRA_CURRENT_PAGE_ID)
        selectedPageIds = intent.getStringArrayListExtra(EXTRA_SELECTED_PAGE_IDS) ?: emptyList()

        // Seed the scope from how we were opened: a selection means the user already chose pages.
        scope = if (selectedPageIds.isNotEmpty()) PageScope.SELECTED else PageScope.ALL

        binding.tvTitle.text = notebookName
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnRunExport.setOnClickListener { runExport() }

        // Back cancels a running export and returns to the options; otherwise it leaves the screen.
        onBackPressedDispatcher.addCallback(this) {
            val job = runningJob
            if (job != null && job.isActive) {
                job.cancel()
                runningJob = null
                showOptions()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        wireRows()
        binding.checkTemplate.setOnCheckedChangeListener { _, _ -> refreshOptions() }
        binding.checkStickyEndnotes.setOnCheckedChangeListener { _, _ -> refreshOptions() }
        binding.checkPdfPassword.setOnCheckedChangeListener { _, checked -> onPdfPasswordToggled(checked) }

        // Resolve the key and load the page list before the options can mean anything.
        lifecycleScope.launch {
            binding.btnRunExport.isVisible = false
            encryptionInfo = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(notebookId) }
            passphrase = resolveKey()
            locked = encryptionInfo.encrypted && passphrase == null
            allPages = withContext(Dispatchers.IO) { loadPageRefs(soilPath, passphrase) }
            binding.btnRunExport.isVisible = !locked
            refreshOptions()
        }
    }

    /**
     * Resolve the notebook's SQLCipher key, preferring the foreground session so an already-open
     * notebook never re-prompts. A closed GLOBAL-scope notebook uses the cached device passphrase;
     * a closed NOTEBOOK-scope one prompts. Null means we could not unlock it.
     */
    private suspend fun resolveKey(): String? {
        if (!encryptionInfo.encrypted) return null
        KeySession.getFor(notebookId)?.let { return it }
        return if (encryptionInfo.keyScope == KeyScope.GLOBAL) {
            PassphraseStore.getGlobalPassphrase(this)
        } else {
            KeyResolver.resolveForOpen(this, notebookId, encryptionInfo)
        }
    }

    // ── Option rows ──────────────────────────────────────────────────────────

    private fun wireRows() = with(binding) {
        rowScopeAll.setOnClickListener { scope = PageScope.ALL; refreshOptions() }
        rowScopeCurrent.setOnClickListener { scope = PageScope.CURRENT; refreshOptions() }
        rowScopeSelected.setOnClickListener { scope = PageScope.SELECTED; refreshOptions() }

        rowFormatPdf.setOnClickListener { selectFormat(ExportFormat.PDF) }
        rowFormatPng.setOnClickListener { selectFormat(ExportFormat.PNG) }
        rowFormatMarkdown.setOnClickListener { selectFormat(ExportFormat.MARKDOWN) }
        rowFormatText.setOnClickListener { selectFormat(ExportFormat.TEXT) }
        rowFormatSoil.setOnClickListener { selectFormat(ExportFormat.SOIL) }

        rowKeyKeep.setOnClickListener { keying = SoilKeying.KEEP; refreshOptions() }
        rowKeyRemove.setOnClickListener { confirmRemoveEncryption() }
        rowKeyNew.setOnClickListener { promptNewSoilPassphrase() }

        rowDestSave.setOnClickListener { destination = ExportDestination.SAVE; refreshOptions() }
        rowDestShare.setOnClickListener { destination = ExportDestination.SHARE; refreshOptions() }
        rowDestTemplate.setOnClickListener { destination = ExportDestination.TEMPLATE; refreshOptions() }
    }

    private fun selectFormat(next: ExportFormat) {
        format = next
        // "Save as template" only exists for PNG — fall back rather than leave an impossible pair.
        if (destination == ExportDestination.TEMPLATE && next != ExportFormat.PNG) {
            destination = ExportDestination.SAVE
        }
        refreshOptions()
    }

    /** The pages the current scope covers, in display order. */
    private fun scopedPages(): List<PageRef> = when (scope) {
        PageScope.ALL -> allPages
        PageScope.CURRENT -> allPages.filter { it.id == currentPageId }
        PageScope.SELECTED -> allPages.filter { it.id in selectedPageIds }
    }

    /**
     * Single source of truth for what the screen shows. Called after every selection change, so
     * each rule lives in exactly one place rather than being scattered across click handlers.
     */
    private fun refreshOptions() = with(binding) {
        if (locked) {
            tvLocked.isVisible = true
            sectionPages.isVisible = false
            sectionOptions.isVisible = false
            sectionEncryption.isVisible = false
            listOf(rowFormatPdf, rowFormatPng, rowFormatMarkdown, rowFormatText, rowFormatSoil,
                   rowDestSave, rowDestShare, rowDestTemplate, tvFilename)
                .forEach { it.isVisible = false }
            return@with
        }

        val pages = scopedPages()

        // ── Pages ──
        rowScopeAll.text = "All pages (${allPages.size})"
        rowScopeCurrent.isVisible = currentPageId != null
        allPages.firstOrNull { it.id == currentPageId }?.let {
            rowScopeCurrent.text = "Current page (${it.number})"
        }
        rowScopeSelected.isVisible = selectedPageIds.isNotEmpty()
        rowScopeSelected.text = "Selected (${selectedPageIds.size})"
        // Only worth showing the section when there is an actual choice to make.
        sectionPages.isVisible = rowScopeCurrent.isVisible || rowScopeSelected.isVisible
        select(rowScopeAll to PageScope.ALL, rowScopeCurrent to PageScope.CURRENT,
               rowScopeSelected to PageScope.SELECTED, current = scope)

        // ── Format ── .soil carries the whole notebook, so a page subset can't produce one.
        rowFormatSoil.isVisible = scope == PageScope.ALL
        if (format == ExportFormat.SOIL && !rowFormatSoil.isVisible) format = ExportFormat.PDF
        select(rowFormatPdf to ExportFormat.PDF, rowFormatPng to ExportFormat.PNG,
               rowFormatMarkdown to ExportFormat.MARKDOWN, rowFormatText to ExportFormat.TEXT,
               rowFormatSoil to ExportFormat.SOIL, current = format)

        // ── Options ── raster formats only; endnotes need sticky notes to exist.
        sectionOptions.isVisible = format.isRaster
        checkStickyEndnotes.isVisible = format == ExportFormat.PDF

        // ── Encryption ──
        refreshEncryptionSection()

        // ── Destination ──
        rowDestTemplate.isVisible = format == ExportFormat.PNG
        select(rowDestSave to ExportDestination.SAVE, rowDestShare to ExportDestination.SHARE,
               rowDestTemplate to ExportDestination.TEMPLATE, current = destination)

        // ── Filename ──
        tvFilename.isVisible = destination != ExportDestination.TEMPLATE
        tvFilename.text = when {
            pages.isEmpty() -> "No pages to export"
            format == ExportFormat.PNG && pages.size > 1 -> "${pages.size} PNG files into a folder you pick"
            else -> outputName(pages)
        }
    }

    /** The name the output will carry, matching what the exporters generate. */
    private fun outputName(pages: List<PageRef>): String {
        val safe = ExportNaming.sanitizeFile(notebookName, "notebook")
        return if (format == ExportFormat.PNG && pages.size == 1) {
            "${ExportNaming.pngFileSpecs(notebookName, pages).first().second}.png"
        } else {
            "$safe.${format.extension}"
        }
    }

    private fun refreshEncryptionSection() = with(binding) {
        val pdfPasswordable = format == ExportFormat.PDF
        val soilKeyable = format == ExportFormat.SOIL && encryptionInfo.encrypted

        checkPdfPassword.isVisible = pdfPasswordable
        groupKeying.isVisible = soilKeyable

        if (soilKeyable) {
            rowKeyKeep.text = if (encryptionInfo.keyScope == KeyScope.NOTEBOOK)
                "Keep encryption (this notebook's passphrase)"
            else
                "Keep encryption (this device's global passphrase)"
            select(rowKeyKeep to SoilKeying.KEEP, rowKeyRemove to SoilKeying.REMOVE,
                   rowKeyNew to SoilKeying.NEW, current = keying)
        }

        // The warning states plainly when the file leaves the app readable by anyone. A .soil that
        // keeps or renews its key is not a leak, so it says nothing.
        val leaks = encryptionInfo.encrypted && when (format) {
            ExportFormat.SOIL -> keying == SoilKeying.REMOVE
            ExportFormat.PDF -> pdfPassword == null
            else -> true
        }
        tvEncryptionWarning.isVisible = leaks
        tvEncryptionWarning.text =
            "This notebook is encrypted. The exported file will be unencrypted — anyone with " +
            "access to it can read its contents. Your library copy stays encrypted."

        sectionEncryption.isVisible = checkPdfPassword.isVisible || groupKeying.isVisible ||
            tvEncryptionWarning.isVisible
    }

    /** Mark whichever row matches [current] as selected; bg_toolbar_button draws the border. */
    private fun <T> select(vararg rows: Pair<TextView, T>, current: T) {
        rows.forEach { (row, value) -> row.isSelected = value == current }
    }

    // ── Encryption prompts ───────────────────────────────────────────────────
    // These two still prompt because they need typed input — but they fire from the checkbox/row,
    // not after Export, so pressing Export never opens a dialog.

    private fun onPdfPasswordToggled(checked: Boolean) {
        if (!checked) { pdfPassword = null; refreshOptions(); return }
        lifecycleScope.launch {
            val pass = PassphrasePrompt.promptForPassphrase(
                this@ExportActivity,
                title = "PDF password",
                message = "Set a password for the exported PDF. You'll need it to open the file " +
                    "in any PDF reader.",
                confirm = true,
            )
            pdfPassword = pass
            // A cancelled prompt unchecks itself rather than silently leaving the box ticked.
            if (pass == null) binding.checkPdfPassword.isChecked = false
            refreshOptions()
        }
    }

    private fun confirmRemoveEncryption() {
        keying = SoilKeying.REMOVE
        refreshOptions()   // the inline warning is the confirmation — no dialog needed
    }

    private fun promptNewSoilPassphrase() {
        lifecycleScope.launch {
            val pass = PassphrasePrompt.promptForPassphrase(
                this@ExportActivity,
                title = "Export passphrase",
                message = "Set a passphrase for the exported copy. You'll need it to open the " +
                    "file later (or a device that uses it as its global passphrase).",
                confirm = true,
            )
            if (pass != null) {
                newSoilPassphrase = pass
                keying = SoilKeying.NEW
            }
            refreshOptions()
        }
    }

    // ── Run ──────────────────────────────────────────────────────────────────

    private fun buildSpec(pages: List<PageRef>) = ExportSpec(
        notebookId = notebookId,
        notebookTitle = notebookName,
        soilPath = soilPath,
        pages = pages,
        format = format,
        destination = destination,
        passphrase = passphrase,
        includeTemplate = binding.checkTemplate.isChecked,
        stickyEndnotes = binding.checkStickyEndnotes.isChecked,
        pdfPassword = pdfPassword,
        soilKeying = keying,
        newSoilPassphrase = newSoilPassphrase,
    )

    private fun runExport() {
        if (locked) return
        val pages = scopedPages()
        if (pages.isEmpty() && format != ExportFormat.SOIL) {
            Toast.makeText(this, "No pages to export", Toast.LENGTH_SHORT).show()
            return
        }
        val spec = buildSpec(pages)

        showProgress("Exporting…")
        runningJob = lifecycleScope.launch {
            val files = try {
                withContext(Dispatchers.IO) {
                    ExportEngine.run(this@ExportActivity, indexRepo, spec) { phase, current, total ->
                        val verb = when (phase) {
                            ExportEngine.Phase.RENDERING -> "Rendering"
                            ExportEngine.Phase.RECOGNIZING -> "Recognizing"
                            ExportEngine.Phase.PACKAGING -> "Packaging"
                        }
                        runOnUiThread {
                            binding.tvProgress.text =
                                if (phase == ExportEngine.Phase.PACKAGING) "Packaging notebook…"
                                else "$verb page $current of $total…"
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ExportActivity", "Export failed", e)
                showOptions()
                Toast.makeText(this@ExportActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            // A text export with no ready recognizer still produces a file from cached page text.
            if (spec.format.textFormat != null &&
                com.notesprout.android.recognition.HandwritingRecognizerProvider.instance?.isReady() != true
            ) {
                Toast.makeText(
                    this@ExportActivity,
                    "Handwriting model not ready — exported cached text only.",
                    Toast.LENGTH_LONG,
                ).show()
            }

            binding.tvProgress.text = "Delivering…"
            delivery.deliver(spec, files, pages)
        }
    }

    private fun showProgress(message: String) {
        binding.tvProgress.text = message
        binding.tvProgress.isVisible = true
        binding.scrollOptions.isVisible = false
        binding.btnRunExport.isVisible = false
    }

    private fun showOptions() {
        binding.tvProgress.isVisible = false
        binding.scrollOptions.isVisible = true
        binding.btnRunExport.isVisible = !locked
    }

    override fun onDestroy() {
        runningJob?.cancel()
        super.onDestroy()
    }
}
