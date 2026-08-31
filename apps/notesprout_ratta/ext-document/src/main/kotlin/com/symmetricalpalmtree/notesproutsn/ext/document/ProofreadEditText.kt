package com.symmetricalpalmtree.notesproutsn.ext.document

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.text.Layout
import android.text.Spanned
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat

/**
 * The editing surface, with the proofread flags drawn on it (arc 19 / M10).
 *
 * It is a subclass rather than a decorator because both halves of the work are the view's own: the
 * underlines have to be painted inside `onDraw`, and the tap that opens the suggestion popup has to
 * be read from the same touch stream the caret is placed from.
 *
 * **The underlines are drawn here rather than by the spans themselves** because a `CharacterStyle`
 * cannot draw a *dashed* line — `UnderlineSpan` is solid, and the design gives spelling a dashed
 * inkBlack line and grammar a dotted one. [ProofreadFlagSpan] and [GrammarFlagSpan] therefore carry
 * no style at all; they are position-only markers that ride the `Editable` through every edit, and
 * this view paints under whichever of them the viewport can see.
 *
 * **No IME is ever refused here.** og's editing surface can return a null `InputConnection` to keep
 * a physical keyboard's chords away from an input method; on Ratta the rule is the opposite —
 * hardware keys are translated by the IME and delivered only while it is shown — so nothing in this
 * module hides or refuses the keyboard, and this class carries none of that machinery.
 *
 * **Nothing here logs.** The only thing it could report is the text under a flag.
 */
class ProofreadEditText(context: Context, attrs: AttributeSet?) : AppCompatEditText(context, attrs) {

    /** Called with the character offset of a confirmed single tap — the proofread popup's hook. */
    var onWordTap: ((Int) -> Unit)? = null

    /** Offset under the last tap-shaped finger-up, against the layout that was actually tapped. */
    private var tappedOffset = -1

    /**
     * Confirmed-single-tap detection, so the popup never rides a double tap: a double tap is the
     * framework's select-word gesture, and a sheet on top of a fresh selection would break
     * select-to-copy on every flagged word. `onSingleTapConfirmed` fires only after the double-tap
     * window has passed — and never for drags or long-presses — well after `super` has placed the
     * caret.
     *
     * The character offset is resolved in `onSingleTapUp`, **not** at confirmation: the confirmation
     * arrives ~300 ms after the finger lifted, and a tap that summons the soft keyboard has resized
     * and scrolled this view by then — the event's x/y against the *new* layout name a different
     * character.
     */
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Resolved here — only for tap-shaped lifts, never scroll or long-press ends — while
            // the pre-IME layout is still the one that was touched.
            tappedOffset = getOffsetForPosition(e.x, e.y)
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tappedOffset >= 0) onWordTap?.invoke(tappedOffset)
            return false
        }
    })

    private val density = resources.displayMetrics.density

    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.inkBlack)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        // Dashed = spelling. On-off lengths chosen to survive e-ink: long enough to render as
        // marks, short enough to read as a dash and not a rule.
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }

    private val grammarPaint = Paint(flagPaint).apply {
        // Dotted = grammar. Round caps turn the near-zero dash segments into dots the stroke's
        // width across — a different texture from the spelling dash at reading distance.
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(1f, 2.5f * density), 0f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // super first: the caret placement, the selection handles and the scroll are all its work,
        // and the detector only watches what it has already dealt with.
        val handled = super.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawProofreadFlags(canvas)
    }

    private fun drawProofreadFlags(canvas: Canvas) {
        val text = text ?: return
        if (text.isEmpty()) return
        val layout = layout ?: return
        // onDraw runs on every keystroke, caret blink and scroll frame, and each underline costs a
        // line measurement — so only the flags in the viewport are considered, never every flag in
        // the document.
        val topLine = layout.getLineForVertical(scrollY)
        val bottomLine = layout.getLineForVertical(scrollY + height)
        val visStart = layout.getLineStart(topLine)
        val visEnd = layout.getLineEnd(bottomLine)
        val spelling = text.getSpans(visStart, visEnd, ProofreadFlagSpan::class.java)
        val grammar = text.getSpans(visStart, visEnd, GrammarFlagSpan::class.java)
        if (spelling.isEmpty() && grammar.isEmpty()) return
        canvas.save()
        // onDraw's canvas is already scrolled; only the text origin's padding is left to add.
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        for (span in spelling) underlineSpan(canvas, layout, text, span, flagPaint)
        for (span in grammar) underlineSpan(canvas, layout, text, span, grammarPaint)
        canvas.restore()
    }

    private fun underlineSpan(canvas: Canvas, layout: Layout, text: Spanned, span: Any, paint: Paint) {
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) return
        val drop = 2f * density
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        // A long word can soft-wrap mid-word, so a flag may span lines even though a word never
        // contains a newline.
        for (line in firstLine..lastLine) {
            val x1 = if (line == firstLine) layout.getPrimaryHorizontal(start) else layout.getLineLeft(line)
            var x2 = if (line == lastLine) layout.getPrimaryHorizontal(end) else layout.getLineRight(line)
            // At a wrap boundary the end offset's position belongs to the next line's start.
            if (line == lastLine && x2 <= x1) x2 = layout.getLineRight(line)
            if (x2 <= x1) continue
            val y = layout.getLineBaseline(line) + drop
            canvas.drawLine(x1, y, x2, y, paint)
        }
    }
}
