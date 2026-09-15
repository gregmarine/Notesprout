package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import com.symmetricalpalmtree.notesproutsn.ext.bible.reader.XrefLink
import kotlin.math.roundToInt

/**
 * A footnote's body, shown on a finger tap on its `*` caller (arc 41): a bordered paper box
 * sitting just **under the caller's line** — or over it when the page has no room below —
 * with the note's address as its heading ("1 Peter 3:8", the user's call: label + text) and any
 * cross-reference inside the body underlined and tappable. A tap anywhere outside dismisses it;
 * a tap on a reference dismisses it and hands the target up.
 *
 * The shape is the panels' (`RecentsPanel`): a full-window `Dialog` on `Theme_Notesprout`,
 * transparent, no dim, no elevation, no animation — the whole window is the dismissing scrim,
 * so nothing leaks to the reader behind and the page never turns under an open note. The box is
 * placed only after the root has laid out, from the anchor's **screen** coordinates against the
 * root's own, so the activity's inset padding cannot skew it; it stays `INVISIBLE` until then —
 * a box drawn once in the wrong place is a ghost on e-ink.
 */
class FootnotePopup(
    private val activity: Activity,
    /** The caller's line, in screen coordinates. */
    private val anchor: Rect,
    private val heading: String,
    private val text: String,
    private val links: List<XrefLink>,
    private val onDismissed: () -> Unit,
    /** A reference inside the note was tapped; the popup has dismissed itself first. */
    private val onNavigate: (startKey: Int, endKey: Int) -> Unit,
) {
    private val dialog = Dialog(activity, R.style.Theme_Notesprout)

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) { onDismissed(); return }
        val black = ContextCompat.getColor(activity, R.color.inkBlack)
        val serif = ResourcesCompat.getFont(activity, R.font.noto_serif) ?: Typeface.SERIF

        val body = SpannableString(text)
        for (link in links) {
            val s = link.start.coerceIn(0, body.length)
            val e = link.end.coerceIn(s, body.length)
            if (e <= s) continue
            body.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        dialog.dismiss()
                        onNavigate(link.targetStartKey, link.targetEndKey)
                    }
                    // Ink, underlined — the page's own link look; no accent colour anywhere.
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = black
                        ds.isUnderlineText = true
                    }
                },
                s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.shape_dialog_bordered)
            setPadding(dp(20), dp(14), dp(20), dp(16))
            visibility = View.INVISIBLE
            addView(
                TextView(activity).apply {
                    this.text = heading
                    typeface = Typeface.create(serif, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
                    setTextColor(black)
                    setPadding(0, 0, 0, dp(8))
                },
            )
            addView(
                TextView(activity).apply {
                    this.text = body
                    typeface = serif
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
                    setTextColor(black)
                    setLineSpacing(0f, 1.25f)
                    highlightColor = Color.TRANSPARENT
                    if (links.isNotEmpty()) movementMethod = LinkMovementMethod.getInstance()
                },
            )
        }
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dialog.dismiss() }
            addView(panel, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setOnDismissListener { onDismissed() }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        root.doOnLayout { place(root, panel) }
        dialog.show()
    }

    /**
     * Under the anchor line when it fits, else over it; horizontally centred on the line and
     * clamped inside the window with [MARGIN_DP] of air. The width is the lesser of [WIDTH_DP]
     * and what the window leaves.
     */
    private fun place(root: FrameLayout, panel: View) {
        val margin = dp(MARGIN_DP)
        val width = minOf(dp(WIDTH_DP), root.width - 2 * margin).coerceAtLeast(1)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST),
        )
        val height = panel.measuredHeight
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        val lineTop = anchor.top - at[1]
        val lineBottom = anchor.bottom - at[1]
        val gap = dp(GAP_DP)
        var top = lineBottom + gap
        if (top + height > root.height - margin) top = lineTop - gap - height
        top = top.coerceIn(margin, (root.height - margin - height).coerceAtLeast(margin))
        val centre = (anchor.left + anchor.right) / 2 - at[0]
        val left = (centre - width / 2).coerceIn(margin, (root.width - margin - width).coerceAtLeast(margin))
        panel.layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = left
            topMargin = top
        }
        panel.visibility = View.VISIBLE
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics).roundToInt()
    private fun dp(value: Int): Int = dp(value.toFloat())

    companion object {
        /** A note reads at two thirds of the page's 30 sp — an aside, still print. */
        private const val TEXT_SP = 20f
        private const val WIDTH_DP = 340f
        private const val MARGIN_DP = 16f
        private const val GAP_DP = 6f
    }
}
