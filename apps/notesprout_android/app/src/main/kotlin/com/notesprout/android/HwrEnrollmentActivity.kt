package com.notesprout.android

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.TopGuard
import com.notesprout.android.notebook.createNotebookView
import com.notesprout.android.notebook.NotebookView
import com.notesprout.android.recognition.HandwritingRecognizerProvider
import com.notesprout.android.recognition.StrokeSegmenter
import com.notesprout.android.recognition.personal.EnrollmentAligner
import com.notesprout.android.recognition.personal.EnrollmentScript
import com.notesprout.android.recognition.personal.TrainingPairRepository
import kotlinx.coroutines.launch

/**
 * "Teach Notesprout your handwriting" — the user copies ~16 prescribed sentences in
 * their normal hand; each becomes a confirmed training pair (full letter/digit/
 * punctuation coverage on day one, before any organic corrections exist).
 *
 * The writing surface is the app's real drawing engine, chosen per device by
 * [createNotebookView] (EPD raw-drawing acceleration on BOOX, so it writes exactly
 * like a page) — with pen + eraser, same as every other
 * host. Ink is captured in memory only (no `.soil`, nothing rendered to disk); the
 * strokes go straight into the training-pair store. Explicitly opt-in by nature, so
 * capture is allowed regardless of notebook encryption state (there is no notebook here).
 */
class HwrEnrollmentActivity : AppCompatActivity() {

    private lateinit var progress: AppCompatTextView
    private lateinit var prompt: AppCompatTextView
    private lateinit var drawingView: NotebookView
    private lateinit var btnPen: AppCompatButton
    private lateinit var btnEraser: AppCompatButton

    private var index = 0
    private var saved = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildUi()
        setContentView(root)
        TopGuard.applyInsetPadding(root)
        showSentence()
    }

    override fun onResume() {
        super.onResume()
        drawingView.resumeDrawing()
    }

    override fun onDestroy() {
        super.onDestroy()
        drawingView.releaseResources()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val inkColor = ContextCompat.getColor(this, R.color.inkBlack)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }

        // Top border — content starts below the system bar inset, so it needs its own 1dp rule.
        root.addView(View(this).apply {
            setBackgroundColor(inkColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        header.addView(AppCompatTextView(this).apply {
            text = "Teach it your handwriting"
            setTextColor(inkColor)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        progress = AppCompatTextView(this).apply {
            setTextColor(inkColor)
            textSize = 15f
        }
        header.addView(progress)
        header.addView(AppCompatButton(this).apply {
            text = "Done"
            setTextColor(inkColor)
            setBackgroundResource(R.drawable.shape_bordered)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
            setOnClickListener { finishEnrollment() }
        })
        root.addView(header)
        root.addView(View(this).apply {
            setBackgroundColor(inkColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        root.addView(AppCompatTextView(this).apply {
            text = "Copy the sentence below in your normal handwriting. Use more lines if you need the space."
            setTextColor(inkColor)
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), 0)
        })
        prompt = AppCompatTextView(this).apply {
            setTextColor(inkColor)
            textSize = 20f
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        root.addView(prompt)

        // Tool row: pen / eraser — same two-state pattern as the sticky-note editor.
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        btnPen = AppCompatButton(this).apply {
            text = "Pen"
            setTextColor(inkColor)
            setBackgroundResource(R.drawable.shape_bordered)
            isSelected = true
            setOnClickListener { setEraser(false) }
        }
        btnEraser = AppCompatButton(this).apply {
            text = "Eraser"
            setTextColor(inkColor)
            setBackgroundResource(R.drawable.shape_bordered)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener { setEraser(true) }
        }
        tools.addView(btnPen)
        tools.addView(btnEraser)
        root.addView(tools)

        // The real drawing engine: EPD raw drawing on BOOX, generic canvas elsewhere.
        drawingView = createNotebookView(this)
        val canvasFrame = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.shape_bordered)
            val pad = dp(2)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { leftMargin = dp(16); rightMargin = dp(16) }
            addView(
                drawingView.asView(),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        root.addView(canvasFrame)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        fun button(label: String, onClick: () -> Unit) = AppCompatButton(this).apply {
            text = label
            setTextColor(inkColor)
            setBackgroundResource(R.drawable.shape_bordered)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }
        buttons.addView(button("Clear") { drawingView.eraseAll() })
        buttons.addView(button("Skip") { advance() })
        buttons.addView(button("Save & Next") { saveAndAdvance() })
        root.addView(buttons)

        return root
    }

    private fun setEraser(active: Boolean) {
        drawingView.setEraserMode(active)
        btnPen.isSelected = !active
        btnEraser.isSelected = active
    }

    private fun showSentence() {
        val sentences = EnrollmentScript.SENTENCES
        if (index >= sentences.size) {
            finishEnrollment()
            return
        }
        progress.text = "${index + 1} / ${sentences.size}"
        prompt.text = sentences[index]
        drawingView.eraseAll()
        setEraser(false) // each sentence starts with the pen
    }

    private fun saveAndAdvance() {
        val strokes = drawingView.getStrokes()
        if (strokes.isEmpty()) {
            Toast.makeText(this, "Write the sentence first (or Skip).", Toast.LENGTH_SHORT).show()
            return
        }
        val sentence = EnrollmentScript.SENTENCES[index]
        val layout = StrokeSegmenter.segment(strokes)
        val lines = layout.paragraphs.flatMap { it.lines }
        if (lines.isEmpty()) {
            Toast.makeText(this, "Write the sentence first (or Skip).", Toast.LENGTH_SHORT).show()
            return
        }

        // Single line: the label is simply the whole sentence. Multiple lines: each line is
        // its own training pair, so the sentence's words must be split correctly across them —
        // ML Kit roughly transcribes each line and EnrollmentAligner finds the word boundaries
        // (a misread doesn't matter, only the split points do).
        if (lines.size == 1) {
            storePairs(listOf(lines.single().strokes to sentence))
            return
        }
        val mlKit = HandwritingRecognizerProvider.mlKitFallback?.takeIf { it.isReady() }
        if (mlKit == null) {
            Toast.makeText(
                this,
                "Multi-line needs the Standard model (still downloading?) — write it on one line for now.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        lifecycleScope.launch {
            val roughTexts = lines.map { line ->
                mlKit.recognizeSegment(line.strokes, line.bounds, "", layout.medianLineHeight)
            }
            val labels = EnrollmentAligner.align(sentence, roughTexts)
            if (labels == null) {
                Toast.makeText(
                    this@HwrEnrollmentActivity,
                    "Couldn't match your lines to the sentence — Clear and try again.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            storePairs(lines.map { it.strokes }.zip(labels))
        }
    }

    /** Persist one pair per written line and advance. */
    private fun storePairs(pairs: List<Pair<List<com.notesprout.android.data.LiveStroke>, String>>) {
        lifecycleScope.launch {
            for ((strokes, label) in pairs) {
                TrainingPairRepository.addPair(
                    context = applicationContext,
                    source = TrainingPairRepository.SOURCE_ENROLLMENT,
                    strokes = strokes,
                    label = label,
                    confirmed = true,
                )
            }
        }
        saved++
        advance()
    }

    private fun advance() {
        index++
        showSentence()
    }

    private fun finishEnrollment() {
        if (saved > 0) {
            Toast.makeText(this, "Saved $saved handwriting samples.", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
