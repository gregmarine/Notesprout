package com.notesprout.android

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.recognition.StrokeSegmenter
import com.notesprout.android.recognition.personal.EnrollmentInkView
import com.notesprout.android.recognition.personal.EnrollmentScript
import com.notesprout.android.recognition.personal.TrainingPairRepository
import kotlinx.coroutines.launch

/**
 * "Teach Notesprout your handwriting" — the user copies ~16 prescribed sentences in
 * their normal hand; each becomes a confirmed training pair (full letter/digit/
 * punctuation coverage on day one, before any organic corrections exist).
 *
 * Ink is captured in memory only (no `.soil`, nothing rendered to disk); the strokes go
 * straight into the training-pair store. Explicitly opt-in by nature, so capture is
 * allowed regardless of notebook encryption state (there is no notebook here).
 */
class HwrEnrollmentActivity : AppCompatActivity() {

    private lateinit var progress: AppCompatTextView
    private lateinit var prompt: AppCompatTextView
    private lateinit var ink: EnrollmentInkView

    private var index = 0
    private var saved = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        showSentence()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val inkColor = ContextCompat.getColor(this, R.color.inkBlack)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }

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
            text = "Copy the sentence below in your normal handwriting, on one line."
            setTextColor(inkColor)
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), 0)
        })
        prompt = AppCompatTextView(this).apply {
            setTextColor(inkColor)
            textSize = 20f
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        root.addView(prompt)

        ink = EnrollmentInkView(this)
        root.addView(LinearLayout(this).apply {
            setBackgroundResource(R.drawable.shape_bordered)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { leftMargin = dp(16); rightMargin = dp(16) }
            addView(ink, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        })

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
        buttons.addView(button("Clear") { ink.clear() })
        buttons.addView(button("Skip") { advance() })
        buttons.addView(button("Save & Next") { saveAndAdvance() })
        root.addView(buttons)

        return root
    }

    private fun showSentence() {
        val sentences = EnrollmentScript.SENTENCES
        if (index >= sentences.size) {
            finishEnrollment()
            return
        }
        progress.text = "${index + 1} / ${sentences.size}"
        prompt.text = sentences[index]
        ink.clear()
    }

    private fun saveAndAdvance() {
        val strokes = ink.getStrokes()
        if (strokes.isEmpty()) {
            Toast.makeText(this, "Write the sentence first (or Skip).", Toast.LENGTH_SHORT).show()
            return
        }
        // Labels can't be split across lines — require a single writing band.
        val bands = StrokeSegmenter.segment(strokes).paragraphs.sumOf { it.lines.size }
        if (bands > 1) {
            Toast.makeText(this, "Please write it on a single line — Clear and try again.", Toast.LENGTH_LONG).show()
            return
        }
        val sentence = EnrollmentScript.SENTENCES[index]
        lifecycleScope.launch {
            TrainingPairRepository.addPair(
                context = applicationContext,
                source = TrainingPairRepository.SOURCE_ENROLLMENT,
                strokes = strokes,
                label = sentence,
                confirmed = true,
            )
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
