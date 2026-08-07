package com.notesprout.android.core

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Two-finger deliberate downward swipe — the shortcut to the Today dashboard.
 *
 * The dashboard is the jump point for the rest of the app, so it has to be reachable from wherever
 * the user happens to be rather than only from the library. Two fingers pulled down is the gesture
 * for it, chosen to sit beside the notebook's existing vocabulary rather than inside it: one finger
 * down already opens the table of contents, and two fingers sideways already inserts a page. This
 * one is vertical **and** two-fingered, so it collides with neither.
 *
 * Every screen hand-rolls its own finger detectors, but this gesture is deliberately shared: it
 * means the same thing everywhere, and one definition is what keeps its feel — and its thresholds —
 * from drifting screen by screen as it is added to more of them.
 *
 * **The gates**, mirroring `NotebookActivity.evaluateSwipeDownToc` so the two down-swipes feel like
 * siblings: the centroid of the two fingers must travel *downward*, be vertical-dominant, cover at
 * least [MIN_DISTANCE_FRAC] of the screen height, and either carry fling velocity or reach
 * [LONG_DISTANCE_FRAC] of the height. Direction comes from displacement, never from velocity, which
 * can flip sign as a finger decelerates at lift-off.
 *
 * Drawing hosts must feed only **finger** events, and only when their pen-activity gate is open — a
 * palm rolling across the glass mid-word is not a gesture (see `NotebookView.isPenActive`). They
 * must also call [cancel] from their own `cancelFingerGestures()`, so a gesture in flight when the
 * gate closes is abandoned rather than completed by the palm that closed it. Hosts with no canvas
 * (the task screen) have neither concern and simply feed it everything.
 *
 * **No host consumes.** The detector reports whether it fired, but every host returns the event to
 * normal dispatch. The day window briefly did consume, to stop its Events `ScrollView` following the
 * swipe. That was removed: Android splits pointers across children by default, so a second finger
 * landing on a different row is a real tap on a real view, and an Activity-level swallow throws that
 * away for every touch in the sequence. A list sliding a little on the way out is the far cheaper
 * problem. (The G102 does not deliver those second-finger taps to begin with — see
 * `docs/today-dashboard.md` — but that is one device, and not a licence to break the ones that do.)
 */
class TwoFingerSwipeDown(
    private val context: Context,
    private val onSwipeDown: () -> Unit,
) {

    private companion object {
        const val TAG = "TwoFingerSwipeDown"
        const val MIN_DISTANCE_FRAC  = 0.30f  // min |dy| to qualify at all
        const val LONG_DISTANCE_FRAC = 0.50f  // |dy| that qualifies regardless of velocity
        const val MIN_VELOCITY_MULT  = 1.0f   // x scaledMinimumFlingVelocity
    }

    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var tracker: VelocityTracker? = null

    /**
     * Feed one finger [event]. Returns true when this event completed a qualifying swipe and
     * [onSwipeDown] has already run — hosts that consume touches may use it to swallow the event.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // First finger down: nothing to arm yet, but clear anything left over.
            MotionEvent.ACTION_DOWN -> cancel()

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    arm(event)
                } else {
                    // Three or more fingers. A palm landing on a swipe that already covers the
                    // distance must not kill it, so commit first and disarm after — the same
                    // early-commit the notebook's page-insert swipe makes.
                    val fired = active && evaluate(event, centroidX(event), centroidY(event))
                    cancel()
                    return fired
                }
            }

            MotionEvent.ACTION_MOVE -> if (active) tracker?.addMovement(event)

            MotionEvent.ACTION_POINTER_UP -> {
                // Both pointers are still reported at POINTER_UP, so the centroid here is still the
                // gesture's — measured the same way as the one recorded when it armed.
                if (active && event.pointerCount == 2) {
                    val fired = evaluate(event, centroidX(event), centroidY(event))
                    cancel()
                    return fired
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancel()
        }
        return false
    }

    /** Abandon any gesture in flight. Safe to call when nothing is armed. */
    fun cancel() {
        active = false
        tracker?.recycle()
        tracker = null
    }

    private fun arm(event: MotionEvent) {
        active = true
        startX = centroidX(event)
        startY = centroidY(event)
        tracker?.recycle()
        tracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun centroidX(event: MotionEvent) = (event.getX(0) + event.getX(1)) / 2f
    private fun centroidY(event: MotionEvent) = (event.getY(0) + event.getY(1)) / 2f

    /** Run the gates against the gesture ending at ([endX], [endY]); fire and report if it passes. */
    private fun evaluate(event: MotionEvent, endX: Float, endY: Float): Boolean {
        val t = tracker ?: return false
        t.addMovement(event)
        t.computeCurrentVelocity(1000)
        val velocityY = t.getYVelocity(event.getPointerId(0))

        val dx = endX - startX
        val dy = endY - startY
        val absDx = abs(dx)
        val absDy = abs(dy)
        val height = context.resources.displayMetrics.heightPixels.toFloat()
        val minVel = ViewConfiguration.get(context).scaledMinimumFlingVelocity * MIN_VELOCITY_MULT

        if (dy <= 0) return false                                  // must be downward
        if (absDy <= absDx) return false                           // must be vertical-dominant
        if (absDy < MIN_DISTANCE_FRAC * height) return false        // must cover the minimum distance
        if (abs(velocityY) < minVel && absDy < LONG_DISTANCE_FRAC * height) return false

        Slog.d(TAG) { "accepted: dy=$dy dx=$dx vy=$velocityY height=$height" }
        onSwipeDown()
        return true
    }
}
