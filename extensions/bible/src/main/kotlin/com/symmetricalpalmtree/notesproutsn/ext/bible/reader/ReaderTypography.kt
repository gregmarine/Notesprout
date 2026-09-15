package com.symmetricalpalmtree.notesproutsn.ext.bible.reader

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.symmetricalpalmtree.notesproutsn.ext.bible.R
import kotlin.math.roundToInt

/**
 * The reader's fonts, sizes, and text-layout building. The key correctness trick
 * for e-ink pagination: the paginator measures a page and the view draws it with
 * the *same* [StaticLayout] configuration, so measured height and rendered height
 * are identical by construction — a page that measures as fitting always renders
 * without overflow. That is why this class **is** the [BodyMeasurer].
 *
 * `LineHeightSpan.Standard` forces every line to one body line-height (the strut
 * equivalent), so a superscript verse number never makes its line taller. Sizes
 * are in sp, so a device font scale is honoured automatically and measurement
 * matches rendering.
 *
 * Building a layout is not cheap: construct this and call it on `Dispatchers.IO`,
 * never on Main.
 *
 * Ported from Biblesprout (`reader/ReaderTypography.kt`). The word popup's and the
 * highlights' char-mark bookkeeping came out with those features; the heading's
 * boolean became [HeadingKind]. Arc 41 brought the tappable marks back in the
 * reader's own shape — [PageMark]s, collected only for a layout that will be drawn
 * ([bodyPage]), never on the measuring path ([measure] runs on every step of the
 * paginator's binary search).
 */
class ReaderTypography(context: Context) : BodyMeasurer {

    private val metrics = context.resources.displayMetrics
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)
    fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics).roundToInt()

    private val serif = ResourcesCompat.getFont(context, R.font.noto_serif) ?: Typeface.SERIF
    private val serifBold = Typeface.create(serif, Typeface.BOLD)

    // Reading typography: 30sp Noto Serif at 1.5 line height, fixed this arc
    // (decision 12), and the reader's derived heading/number sizes.
    private val bodySizePx = sp(BODY_SP)
    val lineHeightPx = (bodySizePx * 1.5f).roundToInt()
    val gap1 = dp(GAP1_DP)
    val gap2 = dp(GAP2_DP)

    val body = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = serif
        textSize = bodySizePx
        color = BLACK
    }
    private val bookTitle = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = serifBold
        textSize = sp(BODY_SP * 0.85f)
        color = BLACK
        letterSpacing = 0.08f
    }
    private val chapterNumber = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = serifBold
        textSize = sp(BODY_SP * 2.1f)
        color = BLACK
    }

    // Poetry / list indent unit, and the prose paragraph first-line indent.
    private val indentUnit = dp(22f)
    private val paraIndent = dp(18f)

    /** A built page body: the text, and — when asked for — the tappable spans in it. */
    private class Built(val text: SpannableStringBuilder, val marks: List<PageMark>)

    /**
     * The single source of truth for how an atom stream lays out: builds the
     * rendered [SpannableStringBuilder]. [BreakAtom]s start new lines (poetry
     * indent / paragraph / stanza); [HeadingAtom]s render as centered lines.
     * With [collectMarks] the char spans a finger can land on — a heading's
     * cross-reference links, a footnote caller — are recorded as [PageMark]s.
     */
    private fun build(atoms: List<Atom>, collectMarks: Boolean): Built {
        val sb = SpannableStringBuilder()
        val marks = ArrayList<PageMark>()
        var atLineStart = true
        var lineStart = 0
        var lineFlow = Flow.PARAGRAPH

        fun closeLine() {
            if (sb.length > lineStart) applyFlow(sb, lineStart, sb.length, lineFlow)
        }
        fun newline() {
            if (!atLineStart) {
                closeLine()
                sb.append('\n')
                atLineStart = true
                lineStart = sb.length
            }
        }

        for (atom in atoms) {
            when (atom) {
                is BreakAtom -> {
                    newline()
                    if (atom.flow == Flow.STANZA && sb.isNotEmpty()) {
                        sb.append('\n') // a blank separator line between stanzas
                        lineStart = sb.length
                    }
                    lineFlow = atom.flow
                }
                is HeadingAtom -> {
                    newline()
                    if (sb.isNotEmpty()) { sb.append('\n'); lineStart = sb.length } // gap above
                    val start = sb.length
                    sb.append(atom.text)
                    // Paragraph spans must be EXCLUSIVE at the end: an INCLUSIVE end
                    // sits at the buffer tail and would grow into every later append,
                    // bleeding the center alignment onto the whole page.
                    sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, sb.length, EXCL)
                    val style = if (atom.kind == HeadingKind.MAJOR) Typeface.BOLD else Typeface.ITALIC
                    sb.setSpan(StyleSpan(style), start, sb.length, EXCL)
                    // A `\r` parallel-passage line is reference apparatus, not text:
                    // it reads as a smaller aside above the section it belongs to.
                    if (atom.kind == HeadingKind.REFERENCE) {
                        sb.setSpan(RelativeSizeSpan(0.8f), start, sb.length, EXCL)
                    }
                    // A cross-reference reads as a link — underlined, the notebook's own cue —
                    // and its chars are what a tap is tested against (arc 41). Clamped to the
                    // heading's own text: a builder span can never reach past it.
                    for (link in atom.links) {
                        val ls = (start + link.start).coerceIn(start, sb.length)
                        val le = (start + link.end).coerceIn(ls, sb.length)
                        if (le <= ls) continue
                        sb.setSpan(UnderlineSpan(), ls, le, EXCL)
                        if (collectMarks) {
                            marks.add(PageMark.Reference(ls, le, link.targetStartKey, link.targetEndKey))
                        }
                    }
                    sb.append('\n') // end the heading line; next line starts a gap-free body line
                    lineStart = sb.length
                    lineFlow = Flow.PARAGRAPH
                    atLineStart = true
                }
                is NumberAtom -> {
                    if (!atLineStart) sb.append(' ')
                    val start = sb.length
                    sb.append(atom.number.toString())
                    sb.setSpan(RelativeSizeSpan(0.62f), start, sb.length, EXCL)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, EXCL)
                    sb.setSpan(SuperscriptSpan(), start, sb.length, EXCL)
                    atLineStart = false
                }
                is WordAtom -> {
                    if (!atLineStart) sb.append(' ')
                    sb.append(atom.word)
                    atLineStart = false
                }
                is FootnoteAtom -> {
                    // The caller attaches to the preceding word — no leading space.
                    val start = sb.length
                    sb.append(CALLER)
                    sb.setSpan(RelativeSizeSpan(0.7f), start, sb.length, EXCL)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, EXCL)
                    sb.setSpan(SuperscriptSpan(), start, sb.length, EXCL)
                    if (collectMarks) marks.add(PageMark.Caller(start, sb.length, atom.id))
                    atLineStart = false
                }
            }
        }
        closeLine()
        if (sb.isNotEmpty()) sb.setSpan(LineHeightSpan.Standard(lineHeightPx), 0, sb.length, INCL)
        return Built(sb, marks)
    }

    /** Applies a line's leading margin (poetry indent / paragraph first-line indent). */
    private fun applyFlow(sb: SpannableStringBuilder, start: Int, end: Int, flow: Flow) {
        val span = when (flow) {
            Flow.PARAGRAPH -> LeadingMarginSpan.Standard(paraIndent, 0)
            Flow.POETRY1 -> LeadingMarginSpan.Standard(indentUnit, indentUnit * 2)
            Flow.POETRY2 -> LeadingMarginSpan.Standard(indentUnit * 2, indentUnit * 3)
            Flow.POETRY_REFRAIN -> LeadingMarginSpan.Standard(indentUnit * 3, indentUnit * 3)
            Flow.LIST1 -> LeadingMarginSpan.Standard(indentUnit, indentUnit)
            Flow.LIST2 -> LeadingMarginSpan.Standard(indentUnit * 2, indentUnit * 2)
            Flow.STANZA -> return
        }
        // EXCLUSIVE end: an INCLUSIVE end would grow into later appends and apply
        // this line's indent to the rest of the page.
        sb.setSpan(span, start, end, EXCL)
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, align: Layout.Alignment) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(align)
            .setLineSpacing(0f, 1f)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    fun bodyLayout(atoms: List<Atom>, width: Int): StaticLayout =
        layout(build(atoms, collectMarks = false).text, body, width, Layout.Alignment.ALIGN_NORMAL)

    /**
     * A page body to be **drawn** (arc 41): the layout and the [PageMark]s a tap on it is tested
     * against — char offsets into this very layout's text.
     */
    fun bodyPage(atoms: List<Atom>, width: Int): Pair<StaticLayout, List<PageMark>> {
        val built = build(atoms, collectMarks = true)
        return layout(built.text, body, width, Layout.Alignment.ALIGN_NORMAL) to built.marks
    }

    /** Rendered height of atoms [start, start+count), measured exactly as drawn. */
    override fun measure(atoms: List<Atom>, start: Int, count: Int, width: Int): Int =
        bodyLayout(atoms.subList(start, start + count), width).height

    fun headingLayouts(bookName: String, chapter: Int, width: Int): Pair<StaticLayout, StaticLayout> =
        layout(bookName.uppercase(), bookTitle, width, Layout.Alignment.ALIGN_CENTER) to
            layout(chapter.toString(), chapterNumber, width, Layout.Alignment.ALIGN_CENTER)

    /** Total height a first-page heading (book title + big chapter number) needs. */
    fun headingHeight(bookName: String, chapter: Int, width: Int): Int =
        headingHeight(headingLayouts(bookName, chapter, width))

    /** [headingHeight] from layouts already built — a chapter build lays the heading out once. */
    fun headingHeight(heading: Pair<StaticLayout, StaticLayout>): Int =
        heading.first.height + gap1 + heading.second.height + gap2

    companion object {
        const val BLACK = 0xFF000000.toInt()

        /** Reading size, fixed this arc (decision 12): 30sp at 1.5 line height. */
        private const val BODY_SP = 30f

        /** The gaps under the book title and under the chapter number, in dp —
         *  read by [ReaderView] too, so the drawn stack matches [headingHeight]. */
        const val GAP1_DP = 4f
        const val GAP2_DP = 22f

        /** The footnote caller glyph rendered inline (superscript). */
        private const val CALLER = "*"
        private const val EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        private const val INCL = Spanned.SPAN_INCLUSIVE_INCLUSIVE
    }
}
