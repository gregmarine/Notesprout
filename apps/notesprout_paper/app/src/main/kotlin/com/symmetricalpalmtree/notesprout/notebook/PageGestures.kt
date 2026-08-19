package com.symmetricalpalmtree.notesprout.notebook

import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesprout.core.Slog
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Finger gestures over the paper: single-finger horizontal swipe flips a page (past the last page it
 * inserts a new one), single-finger **vertical swipe down** opens the Contents (arc 5 / C1 — the
 * flip's distance / fling rule on the height, `dy > 0` only; a swipe up is reserved and does nothing),
 * two-finger horizontal swipe inserts before/after, multi-finger stationary double-tap is undo
 * (2 fingers) / redo (3 fingers), and a single-finger long-press asks to delete the page. All are pen-gated: a resting palm produces MotionEvents that a writing stylus does not,
 * so every recogniser refuses to start (and cancels) while [isPenActive], re-checks the gate at
 * finger-up, and commits tap-actions after a [PaperView.PEN_ACTIVE_TAIL_MS] escrow. The detector
 * stands down entirely while a lasso [selectionActive] (g-paper claims finger input then) and never
 * arms when the down lands on chrome or comes from a stylus.
 *
 * Observer only: fed from the Activity's `dispatchTouchEvent`, it consumes nothing — actions are side
 * effects, so pen ink and the toolbar buttons still see every event. Thresholds are ported verbatim
 * from the reference notebook / calendar detectors.
 */
class PageGestures(
    private val host: View,
    private val isPenActive: () -> Boolean,
    private val selectionActive: () -> Boolean,
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
        fun onDeleteRequested() {}
        /** One-finger swipe down the paper (arc 5 / C1) — the host opens the Contents, or nothing. */
        fun onSwipeDown() {}
    }

    private val vc = ViewConfiguration.get(host.context)
    private val touchSlop = vc.scaledTouchSlop
    private val doubleTapSlop = vc.scaledDoubleTapSlop
    private val minFlingVel = ViewConfiguration.get(host.context).scaledMinimumFlingVelocity * PAGE_SWIPE_MIN_VELOCITY_MULT
    private val width get() = host.resources.displayMetrics.widthPixels.toFloat()
    private val height get() = host.resources.displayMetrics.heightPixels.toFloat()

    /** Set on the first down; when true the whole sequence is ignored (stylus, chrome, or gated). */
    private var ignoreSequence = false

    // ── Single-/two-finger swipe ────────────────────────────────────────────────
    private var pageSwipeActive = false
    private var pageSwipeStartX = 0f
    private var pageSwipeStartY = 0f
    private var pageTracker: VelocityTracker? = null
    private var twoFingerActive = false
    private var twoFingerStartX = 0f
    private var twoFingerStartY = 0f
    private var twoFingerTracker: VelocityTracker? = null

    // ── Multi-finger double-tap ─────────────────────────────────────────────────
    private var mfPeak = 1
    private var mfArmed = false
    private var mfMoved = false
    private var mfDownTime = 0L
    private var mfCx = 0f
    private var mfCy = 0f
    private var twoTapTime = 0L; private var twoTapX = 0f; private var twoTapY = 0f
    private var threeTapTime = 0L; private var threeTapX = 0f; private var threeTapY = 0f

    // ── Long-press → delete ─────────────────────────────────────────────────────
    private var longPressArmed = false
    private var longPressX = 0f
    private var longPressY = 0f
    private val longPressRunnable = Runnable {
        if (longPressArmed && gateOpen()) {
            longPressArmed = false
            listener.onDeleteRequested()
        }
    }

    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignoreSequence = selectionActive() || overChrome(ev) || isStylus(ev)
                if (ignoreSequence) { cancelAll(); return }
            }
            else -> if (ignoreSequence) return
        }
        if (selectionActive()) { cancelAll(); ignoreSequence = true; return }
        handleSwipe(ev)
        handleMultiTap(ev)
        handleLongPress(ev)
    }

    private fun gateOpen(): Boolean = !isPenActive() && !selectionActive()

    private fun isStylus(ev: MotionEvent): Boolean {
        val t = ev.getToolType(0)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    /** Post a tap-action after the pen tail, dropping it if the gate closed meanwhile. */
    private fun escrow(action: () -> Unit) {
        host.postDelayed({ if (gateOpen()) action() }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    // ── Swipe: 1-finger flip, 2-finger insert ───────────────────────────────────

    private fun handleSwipe(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pageSwipeActive = true
                pageSwipeStartX = ev.x; pageSwipeStartY = ev.y
                pageTracker?.recycle()
                pageTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pageSwipeActive) {
                    val dx = ev.getX(0) - pageSwipeStartX
                    val dy = ev.getY(0) - pageSwipeStartY
                    val tracker = pageTracker
                    if (tracker != null && horizontalQualifies(dx, dy)) {
                        tracker.addMovement(ev); tracker.computeCurrentVelocity(1000)
                        evaluateFlip(tracker.getXVelocity(0), dx, dy)
                        clearSwipe(); return
                    }
                }
                clearSwipe()
                if (ev.pointerCount == 2) {
                    twoFingerActive = true
                    twoFingerStartX = (ev.getX(0) + ev.getX(1)) / 2f
                    twoFingerStartY = (ev.getY(0) + ev.getY(1)) / 2f
                    twoFingerTracker?.recycle()
                    twoFingerTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                } else {
                    // 3+ fingers: commit a qualifying two-finger insert before the extra finger kills it.
                    if (twoFingerActive) {
                        val endX = (ev.getX(0) + ev.getX(1)) / 2f
                        val endY = (ev.getY(0) + ev.getY(1)) / 2f
                        val dx = endX - twoFingerStartX; val dy = endY - twoFingerStartY
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
                if (twoFingerActive && ev.pointerCount == 2) {
                    val tracker = twoFingerTracker
                    if (tracker != null) {
                        tracker.addMovement(ev); tracker.computeCurrentVelocity(1000)
                        val endX = (ev.getX(0) + ev.getX(1)) / 2f
                        val endY = (ev.getY(0) + ev.getY(1)) / 2f
                        evaluateInsert(tracker.getXVelocity(0), endX - twoFingerStartX, endY - twoFingerStartY)
                    }
                    clearTwoFinger()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pageSwipeActive) pageTracker?.addMovement(ev)
                if (twoFingerActive && ev.pointerCount >= 2) twoFingerTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_UP -> {
                if (pageSwipeActive) {
                    val tracker = pageTracker
                    if (tracker != null) {
                        tracker.addMovement(ev); tracker.computeCurrentVelocity(1000)
                        val dx = ev.x - pageSwipeStartX; val dy = ev.y - pageSwipeStartY
                        evaluateFlip(tracker.getXVelocity(0), dx, dy)
                        evaluateSwipeDown(tracker.getYVelocity(0), dx, dy)   // exclusive with the flip: one axis dominates
                    }
                }
                clearSwipe(); clearTwoFinger()
            }
            MotionEvent.ACTION_CANCEL -> { clearSwipe(); clearTwoFinger() }
        }
    }

    private fun horizontalQualifies(dx: Float, dy: Float): Boolean =
        abs(dx) > abs(dy) && abs(dx) >= PAGE_SWIPE_MIN_DISTANCE_FRAC * width

    private fun qualifiesFling(vx: Float, dx: Float, dy: Float): Boolean {
        val absDx = abs(dx)
        if (absDx <= abs(dy)) return false
        if (absDx < PAGE_SWIPE_MIN_DISTANCE_FRAC * width) return false
        val fast = abs(vx) >= minFlingVel
        val long = absDx >= PAGE_SWIPE_LONG_DISTANCE_FRAC * width
        return fast || long
    }

    /** Direction from displacement, never velocity, to avoid a sign flip when the finger decelerates. */
    private fun evaluateFlip(vx: Float, dx: Float, dy: Float) {
        if (!qualifiesFling(vx, dx, dy)) return
        if (!gateOpen()) return
        if (dx < 0) listener.onFlipNext() else listener.onFlipPrevious()
    }

    /** The flip's rule mirrored onto the vertical axis (Q4 / C1 Q4): vertical-dominant, ≥ 30 % of the
     *  height + a fling **or** ≥ 50 % of the height, downward only, same pen / selection gate. */
    private fun qualifiesVerticalFling(vy: Float, dx: Float, dy: Float): Boolean {
        val absDy = abs(dy)
        if (absDy <= abs(dx)) return false
        if (absDy < PAGE_SWIPE_MIN_DISTANCE_FRAC * height) return false
        val fast = abs(vy) >= minFlingVel
        val long = absDy >= PAGE_SWIPE_LONG_DISTANCE_FRAC * height
        return fast || long
    }

    private fun evaluateSwipeDown(vy: Float, dx: Float, dy: Float) {
        if (dy <= 0f) return   // a swipe up is reserved
        if (!qualifiesVerticalFling(vy, dx, dy)) return
        if (!gateOpen()) return
        listener.onSwipeDown()
    }

    private fun evaluateInsert(vx: Float, dx: Float, dy: Float) {
        if (!qualifiesFling(vx, dx, dy)) return
        if (!gateOpen()) return
        if (dx < 0) listener.onInsertAfter() else listener.onInsertBefore()
    }

    private fun clearSwipe() {
        pageSwipeActive = false
        pageTracker?.recycle(); pageTracker = null
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
                // BOOX: the Onyx SDK intercepts 3-finger touches and cancels — ACTION_UP never fires.
                // A cancel on an armed, stationary 3-finger gesture counts as the tap.
                if (mfArmed && !mfMoved && mfPeak == 3) evaluateTap(SystemClock.uptimeMillis(), threeOnly = true)
                else { twoTapTime = 0L; threeTapTime = 0L }
                mfArmed = false; mfMoved = false
            }
        }
    }

    private fun evaluateTap(now: Long, threeOnly: Boolean = false) {
        when (if (threeOnly) 3 else mfPeak) {
            2 -> {
                val withinTime = twoTapTime != 0L && now - twoTapTime <= ViewConfiguration.getDoubleTapTimeout()
                val withinSlop = twoTapTime != 0L && hypot((mfCx - twoTapX).toDouble(), (mfCy - twoTapY).toDouble()) <= doubleTapSlop
                if (withinTime && withinSlop) { twoTapTime = 0L; escrow { listener.onUndo() } }
                else { twoTapTime = now; twoTapX = mfCx; twoTapY = mfCy }
            }
            3 -> {
                val withinTime = threeTapTime != 0L && now - threeTapTime <= ViewConfiguration.getDoubleTapTimeout()
                val withinSlop = threeTapTime != 0L && hypot((mfCx - threeTapX).toDouble(), (mfCy - threeTapY).toDouble()) <= doubleTapSlop
                if (withinTime && withinSlop) { threeTapTime = 0L; escrow { listener.onRedo() } }
                else { threeTapTime = now; threeTapX = mfCx; threeTapY = mfCy }
            }
        }
    }

    // ── Single-finger long-press → delete ───────────────────────────────────────

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
        cancelLongPress()
        Slog.d(TAG) { "gestures stood down" }
    }

    private companion object {
        const val TAG = "PageGestures"
        const val PAGE_SWIPE_MIN_DISTANCE_FRAC = 0.30f
        const val PAGE_SWIPE_LONG_DISTANCE_FRAC = 0.50f
        const val PAGE_SWIPE_MIN_VELOCITY_MULT = 1.0f
    }
}
