package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The dial (arc 24 / Z5b) — twelve numbers on a ring, one of them inverted, and a tap that names
 * one. Every angle, every offset and every hit test is [ClockFaceModel]'s: this class owns paints,
 * pixels and one touch listener, and computes nothing it could ask for.
 *
 * **Why a face and not two steppers.** A time on a calendar page is a place, not a count, and a
 * person reaching for "half past two" with a pen knows where that is on a clock before they know how
 * many taps it is from nine. Two taps get you any time on the grain the picker offers.
 *
 * **Nothing here moves.** No hand, no sweep, no ripple, no animation — the picked number is drawn
 * inverted (paper on ink) and that is the whole feedback, because on e-ink an animation is a smear
 * and a highlight that is only a tint is not visible at all.
 */
class ClockFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Which face is showing. The dialog owns the swap — the view never decides to change faces. */
    var face: ClockFaceModel.Face = ClockFaceModel.Face.HOURS
        set(value) { field = value; invalidate() }

    /** The hour that is down, 1..12 — drawn inverted while [face] is `HOURS`. */
    var hour: Int = 12
        set(value) { field = value; invalidate() }

    /** The minute that is down, 0..55 — drawn inverted while [face] is `MINUTES`. */
    var minute: Int = 0
        set(value) { field = value; invalidate() }

    /** What a tap on a number reports. Never called for a tap the model refused (the dead centre,
     *  or outside the dial). */
    var onPicked: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val ink = ContextCompat.getColor(context, R.color.inkBlack)
    private val paper = ContextCompat.getColor(context, R.color.paperWhite)

    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ink
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ink
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, LABEL_SP, resources.displayMetrics,
        )
    }

    /** Square, and as big as the dialog will give it. `resolveSize` against both specs is what lets
     *  the desired 240dp shrink on a narrow window rather than run off the edge of it. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (DESIRED_DP * density).toInt()
        val side = minOf(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 2f * density
        if (radius <= 0f) return
        canvas.drawCircle(cx, cy, radius, rim)

        val ring = radius * RING_FRACTION
        val picked = if (face == ClockFaceModel.Face.HOURS) hour else minute
        val values = ClockFaceModel.values(face)
        val metrics = text.fontMetrics
        // One baseline rule for all twelve: the label is centred on its point vertically as well as
        // horizontally, so the inverted disc sits concentric with the number rather than under it.
        val baselineShift = -(metrics.ascent + metrics.descent) / 2f
        for (index in values.indices) {
            val value = values[index]
            val (dx, dy) = ClockFaceModel.labelOffset(index, ring)
            val x = cx + dx
            val y = cy + dy
            if (value == picked) canvas.drawCircle(x, y, PICKED_RADIUS_DP * density, disc)
            text.color = if (value == picked) paper else ink
            canvas.drawText(ClockFaceModel.label(face, value), x, y + baselineShift, text)
        }
    }

    /**
     * A tap names a number. `ACTION_UP` is the moment, not `ACTION_DOWN`: a pen that lands and slides
     * off a number has not chosen it, and answering on the down would make every rested nib a pick.
     *
     * Pen and finger are the same here on purpose — this is a dialog, not a drawing surface, and the
     * pen-activity gate that keeps fingers off paper has nothing to protect.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val cx = width / 2f
                val cy = height / 2f
                val radius = minOf(cx, cy) - 2f * density
                val hit = ClockFaceModel.hit(face, event.x - cx, event.y - cy, radius)
                if (hit != null) {
                    performClick()
                    onPicked?.invoke(hit)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        /** How big the dial wants to be; `onMeasure` shrinks it to whatever the dialog allows. */
        const val DESIRED_DP = 240f

        /** Where the numbers sit, as a fraction of the dial's radius — inside the rim, with room
         *  for the inverted disc to clear it. */
        const val RING_FRACTION = 0.78f

        /** The inverted disc under the picked number. */
        const val PICKED_RADIUS_DP = 20f

        const val LABEL_SP = 16f
    }
}
