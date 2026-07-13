package com.notesprout.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Debug
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.soilFile
import com.notesprout.android.recognition.HandwritingRecognizer
import com.notesprout.android.recognition.HandwritingRecognizerProvider
import com.notesprout.android.recognition.PageTextRepository
import com.notesprout.android.recognition.StrokeSegmenter
import com.notesprout.android.recognition.personal.TrainingPairRepository
import com.notesprout.android.recognition.trocr.CerMetric
import com.notesprout.android.recognition.trocr.LineRasterizer
import com.notesprout.android.recognition.trocr.TrOcrHandwritingRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only eval harness for the TrOCR engine (Phase 0 go/no-go on real ink).
 *
 * Runs every segmented line of a chosen plaintext notebook page through BOTH engines.
 * Each result row shows the line's actual ink image (so the handwriting can be
 * transcribed right here) plus both transcriptions; tapping a row opens a correction
 * dialog prefilled with ML Kit's guess — fix it to set that line's reference text.
 * CER then scores both engines against the collected references.
 *
 * Recognized text is displayed only — never logged. Lives in the debug source set:
 * release builds contain none of this.
 */
class HwrLabActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var rows: LinearLayout

    private val indexRepo by lazy { IndexRepository(NotesproutIndex.dao()) }
    private val trOcr by lazy { TrOcrHandwritingRecognizer(applicationContext, lifecycleScope) }

    /** Preview-only rasterizer (mean/std unused by renderLineBitmap). */
    private val preview by lazy {
        LineRasterizer(384, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
    }

    private var notebookId: String? = null
    private var notebookName: String = ""
    private var pageId: String? = null
    private var pageLabel: String = ""

    private class LineResult(
        val image: Bitmap,
        val strokes: List<com.notesprout.android.data.LiveStroke>,
        val mlText: String,
        val mlMs: Long,
        val trText: String,
        val trMs: Long,
        var reference: String? = null,
        var label: TextView? = null,
    )

    private var lineResults: MutableList<LineResult> = mutableListOf()

    private val importModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            status.text = "Installing model bundle…"
            lifecycleScope.launch {
                val result = trOcr.modelStore.installFromUri(uri)
                status.text = result.fold(
                    onSuccess = { "Model installed: ${it.versionId} (${it.name})" },
                    onFailure = { "Install failed: ${it.message}" },
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        status.text = trOcr.modelStore.activeManifest()
            ?.let { "Model ready: ${it.versionId}" }
            ?: "No TrOCR model installed — import a bundle zip."
    }

    // ── Notebook / page selection ────────────────────────────────────────────────

    private fun pickNotebook() {
        lifecycleScope.launch {
            val notebooks = withContext(Dispatchers.IO) { indexRepo.getAllNotebooks() }
            if (notebooks.isEmpty()) {
                Toast.makeText(this@HwrLabActivity, "No notebooks", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = notebooks.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@HwrLabActivity)
                .setTitle("Notebook")
                .setItems(names) { _, which ->
                    val nb = notebooks[which]
                    lifecycleScope.launch {
                        val info = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(nb.id) }
                        if (info.encrypted) {
                            Toast.makeText(
                                this@HwrLabActivity,
                                "Pick a plaintext notebook (lab skips encrypted ones)",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            notebookId = nb.id
                            notebookName = nb.name
                            pickPage()
                        }
                    }
                }
                .show()
        }
    }

    private fun pickPage() {
        val nbId = notebookId ?: return
        lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) {
                val db = SoilDatabase.builder(this@HwrLabActivity, soilFile(this@HwrLabActivity, nbId).absolutePath).build()
                try { db.notebookDao().getPagesSorted() } finally { db.close() }
            }
            if (pages.isEmpty()) {
                Toast.makeText(this@HwrLabActivity, "Notebook has no pages", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = Array(pages.size) { "Page ${it + 1}" }
            AlertDialog.Builder(this@HwrLabActivity)
                .setTitle("Page")
                .setItems(labels) { _, which ->
                    pageId = pages[which].id
                    pageLabel = labels[which]
                    status.text = "Selected: $notebookName · $pageLabel"
                }
                .show()
        }
    }

    // ── Eval run ─────────────────────────────────────────────────────────────────

    private fun runEval() {
        val nbId = notebookId; val pgId = pageId
        if (nbId == null || pgId == null) {
            Toast.makeText(this, "Pick a notebook page first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!trOcr.isReady()) {
            Toast.makeText(this, "Import a model bundle first", Toast.LENGTH_SHORT).show()
            return
        }
        val mlKit = HandwritingRecognizerProvider.instance
        status.text = "Running… (first TrOCR line includes model load)"
        clearRows()
        summary.text = ""

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = SoilDatabase.builder(this@HwrLabActivity, soilFile(this@HwrLabActivity, nbId).absolutePath).build()
                val content = try {
                    PageTextRepository.loadPageContent(db.notebookDao(), pgId)
                } finally { db.close() }

                val layout = StrokeSegmenter.segment(content.strokes)
                val lines = layout.paragraphs.flatMap { it.lines }
                if (lines.isEmpty()) {
                    withContext(Dispatchers.Main) { status.text = "No handwriting lines on that page." }
                    return@launch
                }

                val heapBefore = Debug.getNativeHeapAllocatedSize()
                val mlTimes = ArrayList<Long>(); val trTimes = ArrayList<Long>()
                var mlPre = ""

                for ((i, line) in lines.withIndex()) {
                    val image = preview.renderLineBitmap(line.strokes)

                    var mlText = "(engine not ready)"
                    var mlMs = -1L
                    if (mlKit != null && mlKit.isReady()) {
                        val t0 = System.currentTimeMillis()
                        mlText = mlKit.recognizeSegment(
                            line.strokes, line.bounds, mlPre, layout.medianLineHeight,
                        )
                        mlMs = System.currentTimeMillis() - t0
                        mlPre = mlText
                    }
                    if (mlMs >= 0) mlTimes.add(mlMs)

                    val t1 = System.currentTimeMillis()
                    val trText = trOcr.recognizeSegment(line.strokes, line.bounds, "")
                    val trMs = System.currentTimeMillis() - t1
                    trTimes.add(trMs)

                    val result = LineResult(image, line.strokes, mlText, mlMs, trText, trMs)
                    withContext(Dispatchers.Main) {
                        lineResults.add(result)
                        addRow(i, result)
                        status.text = "Line ${i + 1}/${lines.size}…"
                    }
                }

                val heapAfter = Debug.getNativeHeapAllocatedSize()
                val sb = StringBuilder()
                sb.append("══ summary ══\n")
                sb.append("lines: ${lines.size}\n")
                sb.append("model load: ${trOcr.lastLoadMillis()} ms\n")
                sb.append("mlkit  median ${median(mlTimes)} ms · p95 ${p95(mlTimes)} ms\n")
                sb.append("trocr  median ${median(trTimes)} ms · p95 ${p95(trTimes)} ms")
                if (trTimes.isNotEmpty()) sb.append(" (first ${trTimes.first()} ms incl. load)")
                sb.append("\nnative heap delta: ${(heapAfter - heapBefore) / (1 shl 20)} MB\n")
                sb.append("\nTap a line to set its true text, then tap CER.")

                withContext(Dispatchers.Main) {
                    status.text = "Done: $notebookName · $pageLabel"
                    summary.text = sb.toString()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { status.text = "Eval failed: ${e.message}" }
            }
        }
    }

    // ── Reference collection + CER ───────────────────────────────────────────────

    private fun editReference(index: Int, result: LineResult) {
        val input = EditText(this).apply {
            setText(result.reference ?: result.mlText.takeIf { it != HandwritingRecognizer.FALLBACK_TEXT }.orEmpty())
            setTextColor(Color.BLACK)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = borderedBg()
            val p = dp(8)
            setPadding(p, p, p, p)
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(12)
            setPadding(p, p, p, p)
            // the ink being transcribed, right above the input
            addView(ImageView(this@HwrLabActivity).apply {
                setImageBitmap(result.image)
                adjustViewBounds = true
                background = borderedBg()
            })
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this)
            .setTitle("Line ${index + 1} — true text")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                result.reference = input.text.toString().trim().ifEmpty { null }
                result.label?.let { bindRowText(it, index, result) }
                // Every lab reference is a perfect confirmed training pair (plaintext
                // notebooks only by construction — see pickNotebook).
                val ref = result.reference
                if (ref != null && TrainingPairRepository.captureAllowed(this, encryptedSource = false)) {
                    lifecycleScope.launch {
                        TrainingPairRepository.addPair(
                            context = applicationContext,
                            source = TrainingPairRepository.SOURCE_LAB,
                            strokes = result.strokes,
                            label = ref,
                            confirmed = true,
                            originalText = result.trText.takeIf { it != HandwritingRecognizer.FALLBACK_TEXT },
                            notebookId = notebookId,
                            pageId = pageId,
                        )
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun computeCer() {
        val done = lineResults.filter { it.reference != null }
        if (done.isEmpty()) {
            Toast.makeText(this, "Tap lines to set their true text first", Toast.LENGTH_LONG).show()
            return
        }
        val refs = done.map { it.reference!! }
        val mlCer = CerMetric.corpusCer(refs, done.map { it.mlText })
        val trCer = CerMetric.corpusCer(refs, done.map { it.trText })
        summary.append(
            "\n══ CER (${done.size}/${lineResults.size} lines referenced) ══\n" +
                "mlkit: %.4f\ntrocr: %.4f\n".format(mlCer, trCer)
        )
        Toast.makeText(this, "CER appended to summary (scroll down)", Toast.LENGTH_SHORT).show()
    }

    // ── UI construction ──────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 1dp black border on white — visible on e-ink (never gray). */
    private fun borderedBg() = GradientDrawable().apply {
        setColor(Color.WHITE)
        setStroke(dp(1).coerceAtLeast(1), Color.BLACK)
        cornerRadius = dp(4).toFloat()
    }

    private fun bindRowText(label: TextView, index: Int, r: LineResult) {
        val refLine = r.reference?.let { "\nref   ✓ $it" } ?: ""
        label.text = "line ${index + 1} · tap to set true text\n" +
            "mlkit ${fmtMs(r.mlMs)}: ${r.mlText}\n" +
            "trocr ${fmtMs(r.trMs)}: ${r.trText}$refLine"
    }

    private fun addRow(index: Int, r: LineResult) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = borderedBg()
            val p = dp(6)
            setPadding(p, p, p, p)
            setOnClickListener { editReference(index, r) }
        }
        row.addView(ImageView(this).apply {
            setImageBitmap(r.image)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        })
        val label = TextView(this).apply {
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        bindRowText(label, index, r)
        r.label = label
        row.addView(label)
        rows.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }

    private fun clearRows() {
        rows.removeAllViews()
        val old = lineResults
        lineResults = mutableListOf()
        old.forEach { it.image.recycle() }
    }

    private fun buildUi() {
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "HWR Lab (debug)"
            setTextColor(Color.BLACK)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        status = TextView(this).apply { setTextColor(Color.BLACK); setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.BLACK)
            setOnClickListener { onClick() }
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Import model…") { importModel.launch(arrayOf("application/zip")) })
        row1.addView(button("Pick page…") { pickNotebook() })
        row1.addView(button("Run eval") { runEval() })
        row1.addView(button("CER") { computeCer() })
        root.addView(row1)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        summary = TextView(this).apply {
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
        }
        content.addView(summary)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rows)

        root.addView(ScrollView(this).apply {
            addView(content)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 1f })

        setContentView(root, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun fmtMs(ms: Long) = if (ms < 0) "  n/a " else "%5d ms".format(ms)

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return -1
        val s = values.sorted()
        return s[s.size / 2]
    }

    private fun p95(values: List<Long>): Long {
        if (values.isEmpty()) return -1
        val s = values.sorted()
        return s[((s.size - 1) * 95) / 100]
    }

    override fun onDestroy() {
        super.onDestroy()
        clearRows()
        trOcr.close()
    }
}
