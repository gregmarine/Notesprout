package com.symmetricalpalmtree.notesproutsn.ext.bible.reader

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.roundToInt

/** One rendered reader page: body text plus, on a chapter's first page, its heading. */
class ReaderPage(
    val body: StaticLayout,
    val title: StaticLayout? = null,
    val number: StaticLayout? = null,
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
 * underlines — this reader stores no highlights.
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
}
