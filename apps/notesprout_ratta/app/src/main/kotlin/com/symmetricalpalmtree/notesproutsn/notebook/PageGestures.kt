package com.symmetricalpalmtree.notesproutsn.notebook

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The finger vocabulary of the notebook page — the only navigation there is, because the paper is
 * full-bleed and the chrome is two thin bars:
 *
 * | Gesture | Action |
 * |---|---|
 * | 1-finger bare tap | follow a link under it (arc 6) |
 * | 1-finger horizontal swipe | flip previous / next (past the last page: insert one) |
 * | 1-finger vertical swipe down | open the Contents (arc 4) |
 * | 1-finger vertical swipe up | walk the link trail back (arc 6) |
 * | 2-finger horizontal swipe | insert a page before / after this one |
 * | 2-finger stationary double-tap | undo |
 * | 3-finger stationary double-tap | redo |
 * | 1-finger long-press | ask to delete the page |
 *
 * **Observer only.** It is fed from the Activity's `dispatchTouchEvent` and consumes nothing, so
 * firmware ink and the toolbar buttons still see every event; the actions are side effects.
 *
 * **Pen-gated throughout.** A resting palm produces MotionEvents that a writing stylus does not, so
 * every recogniser refuses to arm while [isPenActive], re-checks the gate before it fires, and puts
 * tap-actions in escrow for [PaperView.PEN_ACTIVE_TAIL_MS] before committing them. A sequence that
 * starts on chrome or from a stylus is ignored whole.
 *
 * **Stand-down.** [standDown] is SN's wider version of Paper v0's `selectionActive`: the detector
 * refuses to arm and cancels mid-sequence while a lasso selection is up (g-paper claims finger
 * input then) **or** a tool panel is open (the panel's own dismiss owns that contact — see
 * `docs/notebook.md`). Because the Activity feeds this before its panel-dismiss block, a finger
 * DOWN that is about to close a panel is still seen with the panel open, and the whole sequence
 * is thrown away.
 *
 * **Deliberate delta from the Paper v0 reference:** no BOOX `ACTION_CANCEL` special case. On BOOX
 * the Onyx SDK intercepts 3-finger touches and cancels the sequence, so the reference counted an
 * armed, stationary 3-finger cancel as a tap. Ratta hardware delivers the real `ACTION_UP`, so here
 * a cancel is only ever a cancel: reset the recognisers and forget the double-tap history.
 */
class PageGestures(
    private val host: View,
    private val isPenActive: () -> Boolean,
    private val standDown: () -> Boolean,
    private val overChrome: (MotionEvent) -> Boolean,
    private val listener: Listener,
) {
    /** No-op defaults so a host overrides only what it uses (no nullable lambdas). */
    interface Listener {
        fun onFlipNext() {}
        fun onFlipPrevious() {}
        fun onInsertAfter() {}
        fun onInsertBefore() {}
        fun onUndo() {}
        fun onRedo() {}
        /** A one-finger long-press on the page: the host opens its page sheet (copy / cut /
         *  paste / delete — arc 7 grew it from the bare delete row). */
        fun onPageSheetRequested() {}
        /** A qualifying one-finger swipe DOWN (arc 4 — the Contents). */
        fun onSwipeDown() {}
        /** A qualifying one-finger swipe UP (arc 6 — the trail walk-back). One vertical
         *  evaluation routes on the `dy` sign, so this and [onSwipeDown] are exclusive. */
        fun onSwipeUp() {}
        /**
         * A bare one-finger tap (arc 6 — link follow): sub-slop, under the long-press timeout,
         * single finger throughout, committed through the pen-tail escrow. Reports the **down**
         * point in the host view's coordinates — the finger may creep before the lift.
         */
        fun onFingerTap(x: Float, y: Float) {}
    }

    private val vc = ViewConfiguration.get(host.context)
    private val touchSlop = vc.scaledTouchSlop
    private val doubleTapSlop = vc.scaledDoubleTapSlop
    private val minFlingVel = vc.scaledMinimumFlingVelocity * PAGE_SWIPE_MIN_VELOCITY_MULT
    private val width get() = host.resources.displayMetrics.widthPixels.toFloat()
    private val height get() = host.resources.displayMetrics.heightPixels.toFloat()

    /** Set at the first down; while true the rest of the sequence is dropped on the floor. */
    private var ignoreSequence = false

    // ── 1- and 2-finger swipes ──────────────────────────────────────────────────
    private var swipeActive = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeTracker: VelocityTracker? = null
    private var twoFingerActive = false
    private var twoFingerStartX = 0f
    private var twoFingerStartY = 0f
    private var twoFingerTracker: VelocityTracker? = null

    // ── Multi-finger stationary double-tap ──────────────────────────────────────
    private var mfPeak = 1
    private var mfArmed = false
    private var mfMoved = false
    private var mfDownTime = 0L
    private var mfCx = 0f
    private var mfCy = 0f
    private var twoTapTime = 0L; private var twoTapX = 0f; private var twoTapY = 0f
    private var threeTapTime = 0L; private var threeTapX = 0f; private var threeTapY = 0f

    // ── 1-finger bare tap → follow ──────────────────────────────────────────────
    private var tapArmed = false
    private var tapX = 0f
    private var tapY = 0f
    private var tapDownTime = 0L

    // ── 1-finger long-press → the page sheet ────────────────────────────────────
    private var longPressArmed = false
    private var longPressX = 0f
    private var longPressY = 0f
    private val longPressRunnable = Runnable {
        if (longPressArmed && gateOpen()) {
            longPressArmed = false
            // The page sheet is about to own the screen — stand the rest of this touch sequence
            // down. The finger is still on the glass, and its continued drag would otherwise be
            // judged at ACTION_UP as a flip or swipe-down *under the sheet*: the page changes, and
            // the pending action then lands on the wrong page.
            cancelAll()
            ignoreSequence = true
            listener.onPageSheetRequested()
        }
    }

    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignoreSequence = standDown() || overChrome(ev) || isStylus(ev)
                if (ignoreSequence) { cancelAll(); return }
            }
            else -> if (ignoreSequence) return
        }
        // A selection or a panel that appears mid-sequence takes the contact away from us.
        if (standDown()) { cancelAll(); ignoreSequence = true; return }
        handleSwipe(ev)
        handleMultiTap(ev)
        handleLongPress(ev)
        handleFingerTap(ev)
    }

    private fun gateOpen(): Boolean = !isPenActive() && !standDown()

    private fun isStylus(ev: MotionEvent): Boolean {
        val t = ev.getToolType(0)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    /** Hold a tap-action for the pen tail, then drop it if the gate closed while it waited. */
    private fun escrow(action: () -> Unit) {
        host.postDelayed({ if (gateOpen()) action() }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    // ── Swipe ───────────────────────────────────────────────────────────────────

    private fun handleSwipe(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeActive = true
                swipeStartX = ev.x; swipeStartY = ev.y
                swipeTracker?.recycle()
                swipeTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landing on an already-qualifying one-finger swipe is a late
                // arrival, not a two-finger gesture: commit the flip — or its vertical twin, the
                // swipe-down — before it is lost (a trailing palm is likeliest on exactly the
                // downward drag). Dominance makes the two checks mutually exclusive.
                if (swipeActive) {
                    val dx = ev.getX(0) - swipeStartX
                    val dy = ev.getY(0) - swipeStartY
                    val tracker = swipeTracker
                    if (tracker != null && horizontalQualifies(dx, dy)) {
                        tracker.addMovement(ev); tracker.computeCurrentVelocity(1000)
                        evaluateFlip(tracker.getXVelocity(0), dx, dy)
                        clearSwipe(); return
                    }
                    if (tracker != null && verticalQualifies(dx, dy)) {
                        tracker.addMovement(ev); tracker.computeCurrentVelocity(1000)
                        evaluateVerticalSwipe(tracker.getYVelocity(0), dx, dy)
                        clearSwipe(); return
                    }
                }
                clearSwipe()
                if (ev.pointerCount == 2) {
                    twoFingerActive = true
                    twoFingerStartX = centroidX(ev); twoFingerStartY = centroidY(ev)
                    twoFingerTracker?.recycle()
                    twoFingerTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                } else {
                    // 3+ fingers: commit a qualifying two-finger insert before the extra finger kills it.
                    if (twoFingerActive) {
                        val dx = centroidX(ev) - twoFingerStartX
                        val dy = centroidY(ev) - twoFingerStartY
                        val tracker = twoFingerTracker
                        if (tracker != null && horizontalQualifies(dx, dy)) {
                            tracker.addMovement(ev); tracker.computeCurrentVelocity(1000)
                            evaluateInsert(tracker.getXVelocity(0), dx, dy)
                        }
                    }
                    clearTwoFinger()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Back down to one finger: the two-finger swipe is over, judge it now.
                if (twoFingerActive && ev.pointerCount == 2) {
                    val tracker = twoFingerTracker
                    if (tracker != null) {
                        tracker.addMovement(ev); tracker.computeCurrentVelocity(1000)
                        evaluateInsert(
                            tracker.getXVelocity(0),
                            centroidX(ev) - twoFingerStartX,
                            centroidY(ev) - twoFingerStartY,
                        )
                    }
                    clearTwoFinger()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeActive) swipeTracker?.addMovement(ev)
                if (twoFingerActive && ev.pointerCount >= 2) twoFingerTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_UP -> {
                if (swipeActive) {
                    val tracker = swipeTracker
                    if (tracker != null) {
                        tracker.addMovement(ev); tracker.computeCurrentVelocity(1000)
                        val dx = ev.x - swipeStartX
                        val dy = ev.y - swipeStartY
                        // Axis-exclusive by dominance: qualifiesFling wants |dx| > |dy|, the
                        // vertical rule |dy| > |dx| — at most one of these fires.
                        evaluateFlip(tracker.getXVelocity(0), dx, dy)
                        evaluateVerticalSwipe(tracker.getYVelocity(0), dx, dy)
                    }
                }
                clearSwipe(); clearTwoFinger()
            }
            MotionEvent.ACTION_CANCEL -> { clearSwipe(); clearTwoFinger() }
        }
    }

    private fun centroidX(ev: MotionEvent) = (ev.getX(0) + ev.getX(1)) / 2f
    private fun centroidY(ev: MotionEvent) = (ev.getY(0) + ev.getY(1)) / 2f

    private fun horizontalQualifies(dx: Float, dy: Float): Boolean =
        abs(dx) > abs(dy) && abs(dx) >= PAGE_SWIPE_MIN_DISTANCE_FRAC * width

    /** [horizontalQualifies] rotated 90° — the POINTER_DOWN commit's distance-only vertical gate. */
    private fun verticalQualifies(dx: Float, dy: Float): Boolean =
        abs(dy) > abs(dx) && abs(dy) >= PAGE_SWIPE_MIN_DISTANCE_FRAC * height

    /** Horizontal-dominant, far enough, and either fast enough or simply long enough. */
    private fun qualifiesFling(vx: Float, dx: Float, dy: Float): Boolean {
        val absDx = abs(dx)
        if (absDx <= abs(dy)) return false
        if (absDx < PAGE_SWIPE_MIN_DISTANCE_FRAC * width) return false
        val fast = abs(vx) >= minFlingVel
        val long = absDx >= PAGE_SWIPE_LONG_DISTANCE_FRAC * width
        return fast || long
    }

    /** Direction comes from displacement, never velocity — a decelerating finger can flip the sign. */
    private fun evaluateFlip(vx: Float, dx: Float, dy: Float) {
        if (!qualifiesFling(vx, dx, dy)) return
        if (!gateOpen()) return
        if (dx < 0) listener.onFlipNext() else listener.onFlipPrevious()
    }

    private fun evaluateInsert(vx: Float, dx: Float, dy: Float) {
        if (!qualifiesFling(vx, dx, dy)) return
        if (!gateOpen()) return
        if (dx < 0) listener.onInsertAfter() else listener.onInsertBefore()
    }

    /** The flip's rule rotated 90° — vertical-dominant, far enough, fast or simply long enough. */
    private fun qualifiesVerticalSwipe(vy: Float, dx: Float, dy: Float): Boolean {
        val absDy = abs(dy)
        if (absDy <= abs(dx)) return false
        if (absDy < PAGE_SWIPE_MIN_DISTANCE_FRAC * height) return false
        val fast = abs(vy) >= minFlingVel
        val long = absDy >= PAGE_SWIPE_LONG_DISTANCE_FRAC * height
        return fast || long
    }

    /** Direction from displacement, never velocity — one evaluation, routed on the `dy` sign, so
     *  the Contents swipe-down and the trail swipe-up (arc 6) can never both fire. */
    private fun evaluateVerticalSwipe(vy: Float, dx: Float, dy: Float) {
        if (!qualifiesVerticalSwipe(vy, dx, dy)) return
        if (!gateOpen()) return
        if (dy > 0) listener.onSwipeDown() else listener.onSwipeUp()
    }

    private fun clearSwipe() {
        swipeActive = false
        swipeTracker?.recycle(); swipeTracker = null
    }

    private fun clearTwoFinger() {
        twoFingerActive = false
        twoFingerTracker?.recycle(); twoFingerTracker = null
    }

    // ── Multi-finger stationary double-tap ──────────────────────────────────────

    private fun handleMultiTap(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { mfPeak = 1; mfArmed = false; mfMoved = false }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val count = ev.pointerCount
                if (count > mfPeak) mfPeak = count
                if (count >= 4) { mfArmed = false; mfMoved = true; return }
                if (!mfArmed) { mfArmed = true; mfDownTime = ev.eventTime }
                mfCx = (0 until count).map { ev.getX(it) }.average().toFloat()
                mfCy = (0 until count).map { ev.getY(it) }.average().toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                if (mfArmed && !mfMoved && ev.pointerCount >= 2) {
                    val count = ev.pointerCount
                    val cx = (0 until count).map { ev.getX(it) }.average().toFloat()
                    val cy = (0 until count).map { ev.getY(it) }.average().toFloat()
                    if (hypot((cx - mfCx).toDouble(), (cy - mfCy).toDouble()) > touchSlop) mfMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mfArmed && !mfMoved && ev.eventTime - mfDownTime <= ViewConfiguration.getLongPressTimeout()) {
                    evaluateTap(ev.eventTime)
                }
                mfArmed = false; mfMoved = false
            }
            MotionEvent.ACTION_CANCEL -> {
                // Ratta delivers the real UP for every finger count, so a cancel is only a cancel —
                // no BOOX 3-finger-interception case here. Forget the half-tap too.
                twoTapTime = 0L; threeTapTime = 0L
                mfArmed = false; mfMoved = false
            }
        }
    }

    private fun evaluateTap(now: Long) {
        when (mfPeak) {
            2 -> {
                val withinTime = twoTapTime != 0L && now - twoTapTime <= ViewConfiguration.getDoubleTapTimeout()
                val withinSlop = twoTapTime != 0L &&
                    hypot((mfCx - twoTapX).toDouble(), (mfCy - twoTapY).toDouble()) <= doubleTapSlop
                if (withinTime && withinSlop) { twoTapTime = 0L; escrow { listener.onUndo() } }
                else { twoTapTime = now; twoTapX = mfCx; twoTapY = mfCy }
            }
            3 -> {
                val withinTime = threeTapTime != 0L && now - threeTapTime <= ViewConfiguration.getDoubleTapTimeout()
                val withinSlop = threeTapTime != 0L &&
                    hypot((mfCx - threeTapX).toDouble(), (mfCy - threeTapY).toDouble()) <= doubleTapSlop
                if (withinTime && withinSlop) { threeTapTime = 0L; escrow { listener.onRedo() } }
                else { threeTapTime = now; threeTapX = mfCx; threeTapY = mfCy }
            }
        }
    }

    // ── 1-finger bare tap → follow (arc 6) ──────────────────────────────────────

    /**
     * The **inverse** of every other recogniser — it fires only when nothing else could have:
     * sub-slop (no swipe), under the long-press timeout (the long-press fires *at* the timeout and
     * stands the sequence down, so the two can never both act), single finger throughout (a second
     * finger disarms — the multi-taps own those). Commits through the same pen-tail [escrow] as
     * undo/redo, reporting the down point.
     */
    private fun handleFingerTap(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (gateOpen()) {
                    tapArmed = true
                    tapX = ev.x; tapY = ev.y
                    tapDownTime = ev.eventTime
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> tapArmed = false
            MotionEvent.ACTION_MOVE -> {
                if (tapArmed && hypot((ev.x - tapX).toDouble(), (ev.y - tapY).toDouble()) > touchSlop) {
                    tapArmed = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tapArmed && ev.eventTime - tapDownTime <= ViewConfiguration.getLongPressTimeout()) {
                    val x = tapX; val y = tapY
                    escrow { listener.onFingerTap(x, y) }
                }
                tapArmed = false
            }
            MotionEvent.ACTION_CANCEL -> tapArmed = false
        }
    }

    // ── 1-finger long-press → delete ────────────────────────────────────────────

    private fun handleLongPress(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (gateOpen()) {
                    longPressArmed = true
                    longPressX = ev.x; longPressY = ev.y
                    host.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelLongPress()  // a second finger is never a long-press
            MotionEvent.ACTION_MOVE -> {
                if (longPressArmed && hypot((ev.x - longPressX).toDouble(), (ev.y - longPressY).toDouble()) > touchSlop) {
                    cancelLongPress()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
        }
    }

    private fun cancelLongPress() {
        if (!longPressArmed) return
        longPressArmed = false
        host.removeCallbacks(longPressRunnable)
    }

    private fun cancelAll() {
        clearSwipe(); clearTwoFinger()
        mfArmed = false; mfMoved = false
        tapArmed = false
        cancelLongPress()
        Slog.d(TAG) { "gestures stood down" }
    }

    private companion object {
        const val TAG = "PageGestures"

        /** Minimum horizontal travel, as a fraction of the screen width, before anything counts. */
        const val PAGE_SWIPE_MIN_DISTANCE_FRAC = 0.30f

        /** Travel that qualifies on its own, however slowly the finger moved. */
        const val PAGE_SWIPE_LONG_DISTANCE_FRAC = 0.50f

        /** Fling threshold as a multiple of `scaledMinimumFlingVelocity`. */
        const val PAGE_SWIPE_MIN_VELOCITY_MULT = 1.0f
    }
}
