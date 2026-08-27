package com.symmetricalpalmtree.notesproutsn.core

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration

/**
 * The one-finger flip for every **paginated list** in the app — the library and folder-picker card
 * grids, the link picker, the template browser, and the Contents / Recents panels. The pager
 * buttons stay exactly as they are; this is the same page turn, made with the hand.
 *
 * It is [SwipeMath]'s rule applied to a region instead of the whole screen, so the gesture the
 * notebook already teaches — drag left for the next page, right for the one before, about a third
 * of the way across — means the same thing on a list. A swipe at either end is a **no-op**, never a
 * bounce and never a wrap: the host's own `goToPage` clamps, exactly as a tap on a disabled-looking
 * pager button already does.
 *
 * **Observer only**, like `PageGestures`: it is fed from the host's `dispatchTouchEvent` and
 * consumes nothing, so cards keep their taps and long-presses and the chrome keeps its buttons. A
 * card cannot mistake a swipe for a tap anyway — the finger is long past the touch slop before this
 * fires, which cancels the click on the way.
 *
 * **Armed by region.** The sequence counts only if its DOWN landed inside [region] — the grid
 * container or the list body, never the bars. That is what lets the template browser live inside
 * New Notebook without a drag across the name field turning the page, and it is why the width in
 * the distance rule is the region's, not the screen's: in the sidebar form of the Contents panel
 * the list is 60 % of the glass, and a swipe should be measured against the paper it is on.
 *
 * **Finger only.** A stylus sequence is dropped whole (the user's call): the pen writes, the hand
 * navigates, and that is the same division `PageGestures` draws on the page itself.
 */
class ListSwipe(
    private val region: () -> View?,
    private val onFlipNext: () -> Unit,
    private val onFlipPrevious: () -> Unit,
    /** While true the detector refuses to arm and drops a sequence in flight — an overlay, a
     *  half-built screen, anything that owns the contact instead. Default: nothing stands it down. */
    private val standDown: () -> Boolean = { false },
) {

    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var regionWidth = 0f
    private var tracker: VelocityTracker? = null
    private var minFlingVelocity = 0f

    private val bounds = IntArray(2)

    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clear()
                val view = region() ?: return
                if (standDown() || isStylus(ev) || !inRegion(view, ev)) return
                if (minFlingVelocity == 0f) {
                    minFlingVelocity = ViewConfiguration.get(view.context)
                        .scaledMinimumFlingVelocity * SwipeMath.MIN_VELOCITY_MULT
                }
                active = true
                startX = ev.rawX; startY = ev.rawY
                regionWidth = view.width.toFloat()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> if (active) tracker?.addMovement(ev)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landing on an already-qualifying swipe is a late arrival, not a
                // gesture of its own (a list has no two-finger vocabulary): commit the flip before
                // it is lost, then stand the rest of the sequence down.
                commit(ev)
                clear()
            }
            MotionEvent.ACTION_UP -> {
                commit(ev)
                clear()
            }
            MotionEvent.ACTION_CANCEL -> clear()
        }
    }

    private fun commit(ev: MotionEvent) {
        if (!active || standDown()) return
        val t = tracker ?: return
        t.addMovement(ev)
        t.computeCurrentVelocity(1000)
        val dx = ev.rawX - startX
        val dy = ev.rawY - startY
        when (SwipeMath.flip(dx, dy, t.getXVelocity(0), regionWidth, minFlingVelocity)) {
            SwipeMath.FORWARD -> onFlipNext()
            SwipeMath.BACK -> onFlipPrevious()
        }
    }

    private fun isStylus(ev: MotionEvent): Boolean {
        val t = ev.getToolType(0)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    /** The DOWN in screen coordinates against the region's own place on the glass — a Dialog's
     *  window and an Activity's both report `rawX`/`rawY` there, so one test covers both. */
    private fun inRegion(view: View, ev: MotionEvent): Boolean {
        if (view.width == 0 || view.height == 0 || view.visibility != View.VISIBLE) return false
        view.getLocationOnScreen(bounds)
        val x = ev.rawX; val y = ev.rawY
        return x >= bounds[0] && x < bounds[0] + view.width &&
            y >= bounds[1] && y < bounds[1] + view.height
    }

    private fun clear() {
        active = false
        tracker?.recycle(); tracker = null
    }
}
