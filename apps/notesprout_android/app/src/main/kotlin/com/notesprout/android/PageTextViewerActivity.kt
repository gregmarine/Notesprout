package com.notesprout.android

import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.TopGuard
import com.notesprout.android.crypto.KeyResolver
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.core.markdown.MarkdownParser
import com.notesprout.android.core.markdown.MarkdownRenderer
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.soilFile
import com.notesprout.android.recognition.HandwritingRecognizerProvider
import com.notesprout.android.recognition.MarkdownText
import com.notesprout.android.recognition.PageTextRecognizer
import com.notesprout.android.recognition.PageTextRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only recognized-text viewer for a notebook. Modeled on the DayDetail "day window": a
 * simple two-view toggle — **This Page** (the page the notebook was on) and **Whole Notebook**
 * (all pages concatenated). Text is recognized on open (reusing any fresh `page_text` cache) but
 * never edited or written back — editing recognized text and reconciling it onto ink is deferred
 * (see docs/handwriting-recognition.md § "Deferred"). Renders the recognized Markdown formatted.
 */
class PageTextViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTEBOOK_ID = "notebook_id"
        const val EXTRA_NOTEBOOK_NAME = "notebook_name"
        const val EXTRA_CURRENT_PAGE_ID = "current_page_id"
    }

    private val indexRepo: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    private lateinit var contentText: AppCompatTextView
    private lateinit var statusText: AppCompatTextView
    private lateinit var btnThisPage: AppCompatButton
    private lateinit var btnWholeNotebook: AppCompatButton

    private var thisPageMarkdown: String = ""
    private var wholeNotebookMarkdown: String = ""
    private var showingWhole = false
    private var loaded = false

    private var notebookName: String = "Notebook"
    private var encrypted = false

    // ── Correct mode (This Page only) ──────────────────────────────────────────
    // Tap a recognized handwriting line to fix its text; each fix is stored as a
    // confirmed training pair. The page_text row is NOT rewritten (viewer stays
    // read-only) — the fix becomes durable through correction memory on the next
    // recognition pass.
    private lateinit var btnCorrect: AppCompatButton
    private lateinit var correctionsList: LinearLayout
    private var correctMode = false
    private var notebookId: String = ""
    private var currentPageId: String = ""
    private var thisPageLines: MutableList<com.notesprout.android.data.PageText.RecognizedLine> = mutableListOf()

    // File staged for a SAF save; the mime launcher matches the extension so the picker
    // doesn't append a second one (see PageIndexActivity).
    private var pendingExportFile: File? = null
    private val saveTextLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> writePendingExportTo(uri) }
    private val saveMarkdownLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> writePendingExportTo(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notebookId = intent.getStringExtra(EXTRA_NOTEBOOK_ID).orEmpty()
        notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME).orEmpty().ifBlank { "Notebook" }
        currentPageId = intent.getStringExtra(EXTRA_CURRENT_PAGE_ID).orEmpty()

        val root = buildUi(notebookName)
        setContentView(root)
        TopGuard.applyInsetPadding(root)
        updateToggleState()

        if (notebookId.isEmpty()) {
            statusText.text = "No notebook."
            return
        }
        loadText(notebookId, currentPageId)
    }

    // ── UI (built programmatically to honor the e-ink design system) ─────────────

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(notebookName: String): View {
        val paper = ContextCompat.getColor(this, R.color.paperWhite)
        val ink = ContextCompat.getColor(this, R.color.inkBlack)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top border — content starts below the system bar inset, so it needs its own 1dp rule.
        root.addView(View(this).apply {
            setBackgroundColor(ink)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        // Header: title + Done
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val title = AppCompatTextView(this).apply {
            text = notebookName
            setTextColor(ink)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnCorrect = AppCompatButton(this).apply {
            text = "Correct"
            setTextColor(ink)
            setBackgroundResource(R.drawable.shape_bordered)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            visibility = View.GONE // shown after load when correction is possible
            setOnClickListener { toggleCorrectMode() }
        }
        val export = AppCompatButton(this).apply {
            text = "Export"
            setTextColor(ink)
            setBackgroundResource(R.drawable.shape_bordered)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onExportClicked() }
        }
        val done = AppCompatButton(this).apply {
            text = "Done"
            setTextColor(ink)
            setBackgroundResource(R.drawable.shape_bordered)
            setOnClickListener { finish() }
        }
        header.addView(title)
        header.addView(btnCorrect)
        header.addView(export)
        header.addView(done)
        root.addView(header)

        // Divider
        root.addView(View(this).apply {
            setBackgroundColor(ink)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        // View toggle: This Page / Whole Notebook
        val toggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        btnThisPage = AppCompatButton(this).apply {
            text = "This Page"
            setBackgroundResource(R.drawable.shape_bordered)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
            setOnClickListener { showingWhole = false; render() }
        }
        btnWholeNotebook = AppCompatButton(this).apply {
            text = "Whole Notebook"
            setBackgroundResource(R.drawable.shape_bordered)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showingWhole = true; render() }
        }
        toggle.addView(btnThisPage)
        toggle.addView(btnWholeNotebook)
        root.addView(toggle)

        statusText = AppCompatTextView(this).apply {
            setTextColor(ContextCompat.getColor(this@PageTextViewerActivity, R.color.inkLight))
            textSize = 13f
            setPadding(dp(16), dp(4), dp(16), dp(4))
            text = "Recognizing…"
        }
        root.addView(statusText)

        // Scrollable body. A slightly larger body size + line spacing reads like a document
        // rather than a cramped label; block spacing between paragraphs is added by the renderer.
        contentText = AppCompatTextView(this).apply {
            setTextColor(ink)
            textSize = 18f
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        correctionsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
            visibility = View.GONE
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(contentText)
            addView(correctionsList)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(body)
        }
        root.addView(scroll)
        return root
    }

    // ── Correct mode ─────────────────────────────────────────────────────────────

    private fun toggleCorrectMode() {
        correctMode = !correctMode
        if (correctMode && showingWhole) { showingWhole = false }
        render()
    }

    private fun renderCorrections() {
        val ink = ContextCompat.getColor(this, R.color.inkBlack)
        correctionsList.removeAllViews()
        correctionsList.addView(AppCompatTextView(this).apply {
            text = "Tap a line to fix its text. Fixes teach the Personal engine your handwriting."
            setTextColor(ink)
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })
        for ((index, line) in thisPageLines.withIndex()) {
            correctionsList.addView(AppCompatTextView(this).apply {
                text = line.text
                setTextColor(ink)
                textSize = 16f
                setBackgroundResource(R.drawable.shape_bordered)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                setOnClickListener { showLineCorrectionDialog(index) }
            })
        }
    }

    /** Preview-only rasterizer for the correction dialog (mean/std unused by renderLineBitmap). */
    private val linePreview by lazy {
        com.notesprout.android.recognition.trocr.LineRasterizer(
            384, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f),
        )
    }

    /** Load the line's source strokes from a short-lived read-only connection
     *  (plaintext only — Correct is hidden on encrypted notebooks). */
    private suspend fun loadLineStrokes(
        line: com.notesprout.android.data.PageText.RecognizedLine,
    ): List<com.notesprout.android.data.LiveStroke> = withContext(Dispatchers.IO) {
        val db = SoilDatabase.builder(
            this@PageTextViewerActivity,
            soilFile(this@PageTextViewerActivity, notebookId).absolutePath,
        ).build()
        try {
            val dao = db.notebookDao()
            val layer = dao.getLayerForPage(currentPageId) ?: return@withContext emptyList()
            val wanted = line.strokeIds.toSet()
            dao.getStrokesForLayer(layer.id)
                .filter { it.id in wanted }
                .mapNotNull { row -> runCatching { com.notesprout.android.data.LiveStroke.fromRow(row) }.getOrNull() }
        } finally {
            db.close()
        }
    }

    private fun showLineCorrectionDialog(index: Int) {
        val line = thisPageLines.getOrNull(index) ?: return
        lifecycleScope.launch {
            val strokes = loadLineStrokes(line)
            if (strokes.isEmpty()) {
                Toast.makeText(this@PageTextViewerActivity, "That ink is no longer on the page.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // The user corrects against their actual ink — render it above the input.
            val inkImage = withContext(Dispatchers.IO) { linePreview.renderLineBitmap(strokes) }

            val input = androidx.appcompat.widget.AppCompatEditText(this@PageTextViewerActivity).apply {
                setText(line.text)
                setSelection(text?.length ?: 0)
                setTextColor(ContextCompat.getColor(this@PageTextViewerActivity, R.color.inkBlack))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setBackgroundResource(R.drawable.shape_bordered)
                val p = dp(8)
                setPadding(p, p, p, p)
            }
            val container = LinearLayout(this@PageTextViewerActivity).apply {
                orientation = LinearLayout.VERTICAL
                val p = dp(12)
                setPadding(p, p, p, p)
                addView(android.widget.ImageView(this@PageTextViewerActivity).apply {
                    setImageBitmap(inkImage)
                    adjustViewBounds = true
                    scaleType = android.widget.ImageView.ScaleType.FIT_START
                    setBackgroundResource(R.drawable.shape_bordered)
                })
                addView(input, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) })
            }
            AlertDialog.Builder(this@PageTextViewerActivity)
                .setTitle("Correct line")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    val corrected = input.text?.toString()?.trim().orEmpty()
                    if (corrected.isNotEmpty() && corrected != line.text) {
                        applyLineCorrection(index, line, corrected, strokes)
                    } else {
                        inkImage.recycle()
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> inkImage.recycle() }
                .create()
                .also { d ->
                    d.show()
                    d.window?.setElevation(0f)
                    d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
                }
        }
    }

    private fun applyLineCorrection(
        index: Int,
        line: com.notesprout.android.data.PageText.RecognizedLine,
        corrected: String,
        strokes: List<com.notesprout.android.data.LiveStroke>,
    ) {
        lifecycleScope.launch {
            com.notesprout.android.recognition.personal.TrainingPairRepository.addPair(
                context = applicationContext,
                source = com.notesprout.android.recognition.personal.TrainingPairRepository.SOURCE_LINE_CORRECTION,
                strokes = strokes,
                label = corrected,
                confirmed = true,
                originalText = line.text,
                notebookId = notebookId,
                pageId = currentPageId,
            )
            // Patch the in-memory views so the fix shows immediately; the page_text row is
            // untouched (read-only viewer) — correction memory re-applies it on the next pass.
            thisPageLines[index] = line.copy(text = corrected)
            thisPageMarkdown = thisPageMarkdown.replaceFirst(line.text, corrected)
            wholeNotebookMarkdown = wholeNotebookMarkdown.replaceFirst(line.text, corrected)
            render()
            Toast.makeText(this@PageTextViewerActivity, "Saved — the Personal engine will learn from this.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateToggleState() {
        // Selected view gets a filled emphasis; both keep the 1dp border.
        btnThisPage.isSelected = !showingWhole
        btnWholeNotebook.isSelected = showingWhole
    }

    private fun render() {
        updateToggleState()
        if (!loaded) return
        if (showingWhole) correctMode = false
        btnCorrect.visibility =
            if (!encrypted && thisPageLines.isNotEmpty() &&
                com.notesprout.android.recognition.HwrSettings.personalizationEnabled(this)
            ) View.VISIBLE else View.GONE
        btnCorrect.isSelected = correctMode
        if (correctMode) {
            contentText.visibility = View.GONE
            correctionsList.visibility = View.VISIBLE
            renderCorrections()
            return
        }
        contentText.visibility = View.VISIBLE
        correctionsList.visibility = View.GONE
        val md = if (showingWhole) wholeNotebookMarkdown else thisPageMarkdown
        if (md.isBlank()) {
            contentText.text = if (showingWhole) "No recognized text yet." else "No recognized text on this page yet."
            return
        }
        val paint = TextPaint().apply { color = Color.BLACK; textSize = contentText.textSize }
        val widthPx = (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(dp(200))
        val blocks = MarkdownParser.parse(md)
        contentText.text = MarkdownRenderer.render(
            blocks, widthPx, paint, resources.displayMetrics.density, blockGapPx = dp(10),
        )
    }

    // ── Load ─────────────────────────────────────────────────────────────────────

    private fun loadText(notebookId: String, currentPageId: String) {
        lifecycleScope.launch {
            val hwr = HandwritingRecognizerProvider.instance?.takeIf { it.isReady() }
            val info = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(notebookId) }
            encrypted = info.encrypted
            val key = KeyResolver.resolveForOpen(this@PageTextViewerActivity, notebookId, info)
            if (info.encrypted && key == null) {
                statusText.text = "Notebook is locked."
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                val path = soilFile(this@PageTextViewerActivity, notebookId).absolutePath
                val builder = SoilDatabase.builder(this@PageTextViewerActivity, path)
                if (key != null) {
                    builder.openHelperFactory(SoilCrypto.roomFactory(key))
                    KeySession.set(notebookId, key)
                }
                val db = builder.build()
                try {
                    val dao = db.notebookDao()
                    val recognizer = hwr?.let { PageTextRecognizer(it) }
                    val pages = dao.getPagesSorted()
                    val perPage = LinkedHashMap<String, String>()
                    var pageLines: List<com.notesprout.android.data.PageText.RecognizedLine>? = null
                    for (page in pages) {
                        val pt = if (recognizer != null) {
                            PageTextRepository.freshOrRecognizeReadOnly(dao, page.id, recognizer)
                        } else {
                            PageTextRepository.getCached(dao, page.id)
                        }
                        perPage[page.id] = pt?.text?.trim().orEmpty()
                        if (page.id == currentPageId) pageLines = pt?.lines
                    }
                    val whole = perPage.values.filter { it.isNotBlank() }.joinToString("\n\n")
                    val thisPage = perPage[currentPageId].orEmpty()
                    Triple(thisPage, whole, pageLines)
                } finally {
                    db.close()
                }
            }

            thisPageMarkdown = result.first
            wholeNotebookMarkdown = result.second
            thisPageLines = result.third.orEmpty().toMutableList()
            loaded = true
            statusText.text = when {
                hwr == null -> "Handwriting model not ready — showing cached text only."
                else -> "Read-only. Recognized from your handwriting."
            }
            render()
        }
    }

    // ── Export ─────────────────────────────────────────────────────────────────────
    // Exports exactly what the user is looking at (This Page vs Whole Notebook) from the
    // already-recognized in-memory text — no re-recognition, so it's instant.

    private fun onExportClicked() {
        if (!loaded) {
            Toast.makeText(this, "Still recognizing…", Toast.LENGTH_SHORT).show()
            return
        }
        val md = (if (showingWhole) wholeNotebookMarkdown else thisPageMarkdown).trim()
        if (md.isBlank()) {
            Toast.makeText(this, "Nothing to export yet.", Toast.LENGTH_SHORT).show()
            return
        }
        if (encrypted) confirmEncryptedThen { chooseFormatThen(md) } else chooseFormatThen(md)
    }

    private fun confirmEncryptedThen(proceed: () -> Unit) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Export encrypted notebook")
            .setMessage("This notebook is encrypted. The exported file will be unencrypted — anyone with access to the exported file will be able to read its contents.")
            .setPositiveButton("Export anyway") { _, _ -> proceed() }
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        dlg.window?.setElevation(0f)
        dlg.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    private fun chooseFormatThen(md: String) {
        val scope = if (showingWhole) "notebook" else "page"
        val dlg = AlertDialog.Builder(this)
            .setTitle("Export this $scope as")
            .setItems(arrayOf("Markdown (.md)", "Text only (.txt)")) { _, which ->
                if (which == 0) buildAndOffer(md, NotebookTextExporter.Format.MARKDOWN)
                else buildAndOffer(MarkdownText.toPlainText(md), NotebookTextExporter.Format.PLAIN)
            }
            .create()
        dlg.show()
        dlg.window?.setElevation(0f)
        dlg.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    /** Write [body] to a cache file named after the notebook, then offer Save/Share. */
    private fun buildAndOffer(body: String, format: NotebookTextExporter.Format) {
        val safeTitle = notebookName.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim('_', ' ')
            .ifBlank { "notebook" }
        val scopeSuffix = if (showingWhole) "" else " - page"
        val file = try {
            val outDir = File(cacheDir, "exported_text").also { it.deleteRecursively(); it.mkdirs() }
            File(outDir, "$safeTitle$scopeSuffix.${format.extension}").apply {
                writeText(body.trim() + "\n")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        showTextExportChoice(file)
    }

    private fun showTextExportChoice(file: File) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Export Text")
            .setPositiveButton("Save to device") { _, _ ->
                pendingExportFile = file
                if (file.extension.equals("md", ignoreCase = true)) saveMarkdownLauncher.launch(file.name)
                else saveTextLauncher.launch(file.name)
            }
            .setNegativeButton("Share") { _, _ ->
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (file.extension == "md") "text/markdown" else "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Text"))
            }
            .create()
        dlg.show()
        dlg.window?.setElevation(0f)
        dlg.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    private fun writePendingExportTo(uri: Uri?) {
        val file = pendingExportFile ?: return
        if (uri == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PageTextViewerActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
