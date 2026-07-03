package com.notesprout.android

import android.graphics.Color
import android.os.Bundle
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
import com.notesprout.android.recognition.PageTextRecognizer
import com.notesprout.android.recognition.PageTextRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val notebookId = intent.getStringExtra(EXTRA_NOTEBOOK_ID).orEmpty()
        val notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME).orEmpty().ifBlank { "Notebook" }
        val currentPageId = intent.getStringExtra(EXTRA_CURRENT_PAGE_ID).orEmpty()

        setContentView(buildUi(notebookName))
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
        val done = AppCompatButton(this).apply {
            text = "Done"
            setTextColor(ink)
            setBackgroundResource(R.drawable.shape_bordered)
            setOnClickListener { finish() }
        }
        header.addView(title)
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

        // Scrollable body
        contentText = AppCompatTextView(this).apply {
            setTextColor(ink)
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(contentText)
        }
        root.addView(scroll)
        return root
    }

    private fun updateToggleState() {
        // Selected view gets a filled emphasis; both keep the 1dp border.
        btnThisPage.isSelected = !showingWhole
        btnWholeNotebook.isSelected = showingWhole
    }

    private fun render() {
        updateToggleState()
        if (!loaded) return
        val md = if (showingWhole) wholeNotebookMarkdown else thisPageMarkdown
        if (md.isBlank()) {
            contentText.text = if (showingWhole) "No recognized text yet." else "No recognized text on this page yet."
            return
        }
        val paint = TextPaint().apply { color = Color.BLACK; textSize = contentText.textSize }
        val widthPx = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(200))
        val blocks = MarkdownParser.parse(md)
        contentText.text = MarkdownRenderer.render(blocks, widthPx, paint, resources.displayMetrics.density)
    }

    // ── Load ─────────────────────────────────────────────────────────────────────

    private fun loadText(notebookId: String, currentPageId: String) {
        lifecycleScope.launch {
            val hwr = HandwritingRecognizerProvider.instance?.takeIf { it.isReady() }
            val info = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(notebookId) }
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
                    for (page in pages) {
                        val pt = if (recognizer != null) {
                            PageTextRepository.freshOrRecognizeReadOnly(dao, page.id, recognizer)
                        } else {
                            PageTextRepository.getCached(dao, page.id)
                        }
                        perPage[page.id] = pt?.text?.trim().orEmpty()
                    }
                    val whole = perPage.values.filter { it.isNotBlank() }.joinToString("\n\n")
                    val thisPage = perPage[currentPageId].orEmpty()
                    Triple(thisPage, whole, pages.size)
                } finally {
                    db.close()
                }
            }

            thisPageMarkdown = result.first
            wholeNotebookMarkdown = result.second
            loaded = true
            statusText.text = when {
                hwr == null -> "Handwriting model not ready — showing cached text only."
                else -> "Read-only. Recognized from your handwriting."
            }
            render()
        }
    }
}
