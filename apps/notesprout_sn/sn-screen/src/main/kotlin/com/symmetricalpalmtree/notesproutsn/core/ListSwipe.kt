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
 *
 * **Optionally vertical too.** A host that gives [onSwipeDown] / [onSwipeUp] gets the flip's rule
 * rotated 90° ([SwipeMath.vertical], judged against the region's *height*) — the Bible reader's
 * swipe-down for its index, the same travel the notebook teaches for its Contents. The two
 * evaluations are exclusive by dominance, so one drag is a flip or a vertical swipe, never both;
 * a host that leaves them null keeps the horizontal-only detector every list has always had.
 *
 * **Optionally two-fingered too.** A host that gives [onTwoFingerSwipeDown] gets the notebook's
 * Recents gesture (`PageGestures`' two-finger vertical rule): a second finger landing on a
 * one-finger drag that has **not** yet qualified starts a two-finger sequence measured at the
 * two-finger centroid, judged by [SwipeMath.vertical] when the sequence drops back to one finger
 * (or a third finger lands on a qualifying one), and only the **down** direction is claimed. A
 * second finger landing on an already-qualifying one-finger swipe is still the late arrival it
 * always was — the flip commits and the rest of the sequence is stood down. A host that leaves it
 * null keeps the one-finger detector exactly as it was.
 */
class ListSwipe(
    private val region: () -> View?,
    private val onFlipNext: () -> Unit,
    private val onFlipPrevious: () -> Unit,
    private val onSwipeDown: (() -> Unit)? = null,
    private val onSwipeUp: (() -> Unit)? = null,
    private val onTwoFingerSwipeDown: (() -> Unit)? = null,
    /** While true the detector refuses to arm and drops a sequence in flight — an overlay, a
     *  half-built screen, anything that owns the contact instead. Default: nothing stands it down. */
    private val standDown: () -> Boolean = { false },
) {

    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var regionWidth = 0f
    private var regionHeight = 0f
    private var tracker: VelocityTracker? = null
    private var minFlingVelocity = 0f

    private val bounds = IntArray(2)

    /** The two-finger sequence, when a host asked for one: armed at the POINTER_DOWN to two. */
    private var twoFingerActive = false
    private var twoFingerStartX = 0f
    private var twoFingerStartY = 0f
    private var twoFingerTracker: VelocityTracker? = null

    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clear()
                clearTwoFinger()
                val view = region() ?: return
                if (standDown() || isStylus(ev) || !inRegion(view, ev)) return
                if (minFlingVelocity == 0f) {
                    minFlingVelocity = ViewConfiguration.get(view.context)
                        .scaledMinimumFlingVelocity * SwipeMath.MIN_VELOCITY_MULT
                }
                active = true
                startX = ev.rawX; startY = ev.rawY
                regionWidth = view.width.toFloat()
                regionHeight = view.height.toFloat()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (active) tracker?.addMovement(ev)
                if (twoFingerActive && ev.pointerCount >= 2) twoFingerTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landing on an already-qualifying swipe is a late arrival, not a
                // gesture of its own: commit the flip before it is lost, then stand the rest of
                // the sequence down. Only a one-finger drag that did NOT qualify can become a
                // two-finger sequence — and only for a host that asked for one.
                val wasArmed = active
                val committed = commit(ev)
                clear()
                if (onTwoFingerSwipeDown == null) return
                if (ev.pointerCount == 2) {
                    if (wasArmed && !committed) startTwoFinger(ev)
                } else {
                    // 3+ fingers: commit a qualifying two-finger swipe before the extra finger
                    // kills it, then stand down.
                    commitTwoFinger(ev)
                    clearTwoFinger()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Back down to one finger: the two-finger swipe is over, judge it now.
                if (twoFingerActive && ev.pointerCount == 2) {
                    commitTwoFinger(ev)
                    clearTwoFinger()
                }
            }
            MotionEvent.ACTION_UP -> {
                commit(ev)
                clear()
                clearTwoFinger()
            }
            MotionEvent.ACTION_CANCEL -> { clear(); clearTwoFinger() }
        }
    }

    /** Judges the one-finger sequence; true when a callback fired. */
    private fun commit(ev: MotionEvent): Boolean {
        if (!active || standDown()) return false
        val t = tracker ?: return false
        t.addMovement(ev)
        t.computeCurrentVelocity(1000)
        val dx = ev.rawX - startX
        val dy = ev.rawY - startY
        when (SwipeMath.flip(dx, dy, t.getXVelocity(0), regionWidth, minFlingVelocity)) {
            SwipeMath.FORWARD -> { onFlipNext(); return true }
            SwipeMath.BACK -> { onFlipPrevious(); return true }
        }
        if (onSwipeDown == null && onSwipeUp == null) return false
        when (SwipeMath.vertical(dx, dy, t.getYVelocity(0), regionHeight, minFlingVelocity)) {
            SwipeMath.DOWN -> { onSwipeDown?.invoke(); return true }
            SwipeMath.UP -> { onSwipeUp?.invoke(); return true }
        }
        return false
    }

    /** Arms the two-finger sequence at the centroid of the two contacts — window coordinates
     *  (`getX`/`getY`), as `PageGestures` measures its own: only the displacement is judged. */
    private fun startTwoFinger(ev: MotionEvent) {
        twoFingerActive = true
        twoFingerStartX = centroidX(ev); twoFingerStartY = centroidY(ev)
        twoFingerTracker?.recycle()
        twoFingerTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    /** The two-finger centroid's vertical rule: the flip's rule rotated 90°, against the
     *  region's height, and only **down** is claimed — an upward two-finger swipe is nothing. */
    private fun commitTwoFinger(ev: MotionEvent) {
        if (!twoFingerActive || ev.pointerCount < 2 || standDown()) return
        val t = twoFingerTracker ?: return
        t.addMovement(ev)
        t.computeCurrentVelocity(1000)
        val dx = centroidX(ev) - twoFingerStartX
        val dy = centroidY(ev) - twoFingerStartY
        if (SwipeMath.vertical(dx, dy, t.getYVelocity(0), regionHeight, minFlingVelocity) == SwipeMath.DOWN) {
            onTwoFingerSwipeDown?.invoke()
        }
    }

    private fun centroidX(ev: MotionEvent) = (ev.getX(0) + ev.getX(1)) / 2f
    private fun centroidY(ev: MotionEvent) = (ev.getY(0) + ev.getY(1)) / 2f

    private fun clearTwoFinger() {
        twoFingerActive = false
        twoFingerTracker?.recycle(); twoFingerTracker = null
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
