package com.symmetricalpalmtree.notesproutsn.ext.bible.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.text.StaticLayout
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.roundToInt

/**
 * One rendered reader page: body text plus, on a chapter's first page, its heading — and, since
 * arc 41, the [marks] a finger can land on, as char offsets into [body]'s text. A passage page
 * carries none (the plain verse layer has no `\r` lines and no callers).
 */
class ReaderPage(
    val body: StaticLayout,
    val title: StaticLayout? = null,
    val number: StaticLayout? = null,
    val marks: List<PageMark> = emptyList(),
)

/**
 * Draws one [ReaderPage] on white, with the reader's padding. Drawing the same
 * [StaticLayout] the paginator measured guarantees the page fits exactly.
 * The screen reads [readingWidth]/[readingHeight] to paginate against.
 *
 * **It repaints only when the page actually changes** ([show] is the one door,
 * and an identical page is a no-op): a surface that invalidates itself on e-ink
 * ghosts, and every needless frame is a visible flash.
 *
 * Ported from Biblesprout (`reader/ReaderView.kt`), minus the highlight
 * underlines — this reader stores no highlights. Arc 41 added the hit test
 * ([markAt]): the offset under a finger is asked of the very layout that was
 * drawn, so what looks tappable is tappable.
 */
class ReaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private var page: ReaderPage? = null

    /** Padding around the text block; the gaps match [ReaderTypography.headingHeight]. */
    val horizontalPad = dp(44f)
    val verticalPad = dp(10f)
    private val gap1 = dp(ReaderTypography.GAP1_DP)
    private val gap2 = dp(ReaderTypography.GAP2_DP)

    /** Shows [page], repainting only if it is not the one already drawn. */
    fun show(page: ReaderPage?) {
        if (this.page === page) return
        this.page = page
        invalidate()
    }

    fun readingWidth(): Int = width - horizontalPad * 2
    fun readingHeight(): Int = height - verticalPad * 2

    /** Where the body starts below the top pad: under the heading stack on a first page. */
    private fun bodyTop(p: ReaderPage): Int =
        if (p.title != null && p.number != null) p.title.height + gap1 + p.number.height + gap2 else 0

    /**
     * The tappable mark under ([x], [y]) in this view's coordinates, or null — a cross-reference
     * in a parallel-passage line, or a footnote caller ([PageMarks.at]'s rules). The offset comes
     * from the drawn body layout: its line under the finger, then the char nearest the finger's
     * x on that line.
     */
    fun markAt(x: Float, y: Float): PageMark? {
        val p = page ?: return null
        if (p.marks.isEmpty()) return null
        val localX = x - horizontalPad
        val localY = (y - verticalPad - bodyTop(p)).toInt()
        if (localY < 0 || localY > p.body.height) return null
        val line = p.body.getLineForVertical(localY)
        // Past the line's drawn end there is no glyph to have been tapped.
        if (localX < p.body.getLineLeft(line) - SLOP_PX || localX > p.body.getLineRight(line) + SLOP_PX) return null
        val offset = p.body.getOffsetForHorizontal(line, localX)
        return PageMarks.at(p.marks, offset)
    }

    /**
     * The drawn line holding [mark]'s first char, as a rect in this view's coordinates — where a
     * popup anchors to sit just under (or over) what was tapped.
     */
    fun lineBounds(mark: PageMark): Rect? {
        val p = page ?: return null
        val line = p.body.getLineForOffset(mark.start)
        val top = verticalPad + bodyTop(p)
        return Rect(
            horizontalPad + p.body.getLineLeft(line).toInt(),
            top + p.body.getLineTop(line),
            horizontalPad + p.body.getLineRight(line).toInt(),
            top + p.body.getLineBottom(line),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
        canvas.save()
        canvas.translate(horizontalPad.toFloat(), verticalPad.toFloat())
        var y = 0
        if (p.title != null && p.number != null) {
            drawAt(canvas, p.title, y)
            y += p.title.height + gap1
            drawAt(canvas, p.number, y)
            y += p.number.height + gap2
        }
        drawAt(canvas, p.body, y)
        canvas.restore()
    }

    private fun drawAt(canvas: Canvas, layout: StaticLayout, y: Int) {
        canvas.save()
        canvas.translate(0f, y.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
            .roundToInt()

    /** A finger is wider than a glyph: a tap a little past a line's edge still counts. */
    private val SLOP_PX get() = dp(8f).toFloat()
}
