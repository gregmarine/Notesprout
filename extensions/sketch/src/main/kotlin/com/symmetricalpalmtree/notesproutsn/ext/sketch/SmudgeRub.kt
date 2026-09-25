package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.hypot

/**
 * The finger rub that smudges graphite (arc 48) — the recogniser, in pure Kotlin.
 *
 * A smudge is a **one-finger back-and-forth**: the hand rubs across a hatch in short, quick
 * strokes, the way a finger blends pencil on paper. What tells it from every other one-finger
 * gesture on the page is the **reversal** — a swipe travels one way and lifts; a tap and a
 * long-press do not travel at all — and what tells it from a swipe that happened to bounce is
 * **staying put**: at the moment of the reversal the finger is still within [armWithinPx] of
 * where it landed. So the rub arms on the first reversal of travel inside that reach, and
 * never on anything else.
 *
 * Travel is read in **hops**: each time the finger has moved [hopPx] from the last hop's end,
 * that hop's direction is compared with the previous hop's; a turn of more than 120°
 * ([REVERSAL_COS], the rubber's own number) is a reversal. Hops are short so a rapid rub of a
 * centimetre or two reverses within a sample or two of the hand actually turning.
 *
 * **Before it arms, the samples are kept**; on arming they are delivered as the first batch,
 * so the first stroke of the rub smudges too rather than being the price of recognition.
 * After it arms every move is a batch, and the lift ends it. A second finger ends an armed rub
 * (what was smudged stays) and kills an unarmed one; a sequence that was refused at the down
 * (a stylus, chrome, the pen gate) is ignored whole; [gate] is asked again at the reversal
 * and on every batch, so a pen that arrives mid-rub ends it — the pen-activity rule every
 * finger gesture on these screens keeps.
 *
 * Coordinates are the paper's; the caller converts. Nothing here touches Android.
 */
class SmudgeRub(
    private val hopPx: Float,
    private val armWithinPx: Float,
    private val gate: () -> Boolean,
    private val listener: Listener,
) {
    interface Listener {
        /** The rub is recognised: [points] are every sample since the finger landed. */
        fun onArmed(points: List<StrokePoint>)
        /** One more batch of an armed rub. */
        fun onRub(points: List<StrokePoint>)
        /** The armed rub is over — the finger lifted, a second finger landed, or the gate closed. */
        fun onEnded()
    }

    /** Whether an armed rub is in progress — the page gestures stand down while it is. */
    var active: Boolean = false
        private set

    /** Whether a candidate sequence is being watched (armed or not yet). */
    val tracking: Boolean get() = watching || active

    private var watching = false
    private val pending = ArrayList<StrokePoint>()
    private var downX = 0f
    private var downY = 0f
    private var hopX = 0f
    private var hopY = 0f
    private var prevDx = 0f
    private var prevDy = 0f
    private var haveDir = false

    /** The first finger landed at ([x], [y]); [allowed] is the caller's down-time gate. */
    fun down(x: Float, y: Float, timeMillis: Long, allowed: Boolean) {
        reset()
        if (!allowed) return
        watching = true
        downX = x; downY = y
        hopX = x; hopY = y
        pending.add(StrokePoint(x, y, pressure = PRESSURE, timeMillis = timeMillis))
    }

    /** This event's samples, oldest first. */
    fun move(points: List<StrokePoint>) {
        if (points.isEmpty()) return
        if (active) {
            if (!gate()) { end(); return }
            listener.onRub(points)
            return
        }
        if (!watching) return
        pending.addAll(points)
        for (p in points) {
            val dx = p.x - hopX
            val dy = p.y - hopY
            val len = hypot(dx, dy)
            if (len < hopPx) continue
            val ux = dx / len
            val uy = dy / len
            val reversed = haveDir && (prevDx * ux + prevDy * uy) < REVERSAL_COS
            prevDx = ux; prevDy = uy; haveDir = true
            hopX = p.x; hopY = p.y
            if (!reversed) continue
            // The first turn-back decides, one way or the other.
            watching = false
            if (hypot(p.x - downX, p.y - downY) > armWithinPx || !gate()) {
                pending.clear()
                return
            }
            active = true
            val first = ArrayList(pending)
            pending.clear()
            listener.onArmed(first)
            return
        }
    }

    /** A second finger landed: an armed rub ends, an unarmed one is forgotten. */
    fun secondFinger() {
        if (active) end() else reset()
    }

    /** The finger lifted. */
    fun up() {
        if (active) end() else reset()
    }

    /** The sequence was cancelled by the system. */
    fun cancel() = up()

    private fun end() {
        active = false
        reset()
        listener.onEnded()
    }

    private fun reset() {
        active = false
        watching = false
        pending.clear()
        haveDir = false
    }

    companion object {
        /** Cosine of the turn that counts as rubbing back: 120° — `RasterRub.REVERSAL_COS`. */
        const val REVERSAL_COS = -0.5f

        /** A finger reports no pressure worth reading; the engine's smudge does not read it. */
        const val PRESSURE = 0.5f
    }
}
