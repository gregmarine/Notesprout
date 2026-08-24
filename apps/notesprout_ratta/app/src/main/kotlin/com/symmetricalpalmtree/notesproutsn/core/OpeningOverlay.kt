package com.symmetricalpalmtree.notesproutsn.core

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.symmetricalpalmtree.notesproutsn.R
import java.util.WeakHashMap

/**
 * The source-side "Opening…" overlay for a notebook launch (P1).
 *
 * Opening a notebook is the one slow navigation in the app — seal nothing, start an Activity, derive
 * or fetch the key, open the `.soil`, load the page — and on e-ink a tap that produces no frame for
 * hundreds of ms reads as a tap that missed. The *source* screen raises this box at tap time and the
 * launch runs only once that frame is on the glass; the destination shows the same box from its own
 * first frame until the page lands, so the user sees one continuous "Opening…" from tap to page.
 *
 * **The pre-draw + post sequencing is the whole point — do not "simplify" it.** Making the view
 * `VISIBLE` only schedules a traversal. `Dispatchers.Main` is an *async* Handler, so a coroutine
 * (or a bare `startActivity` in the tap handler) jumps the traversal's sync barrier: the launch
 * wins, the source is paused, and the overlay never draws at all. Waiting for `onPreDraw` and then
 * `post`ing puts the work strictly after the frame that shows the box.
 *
 * The root is full-screen but transparent and clickable: it shields the source screen from the
 * second tap a slow e-ink refresh invites, while only the box's region actually repaints.
 */
object OpeningOverlay {

    // The per-activity overlay is cached as a TAG LOOKUP in the activity's own view tree, never a
    // map keyed on the Activity: a WeakHashMap<Activity, View> would leak every Activity for the
    // process lifetime (the cached View strongly references its Activity through View.context —
    // value → key defeats the weak key). autoHideArmed stays a map because its Boolean value holds
    // no reference back to the key.
    private val TAG_KEY = "notesproutsn.openingOverlay"
    private val autoHideArmed = WeakHashMap<Activity, Boolean>()

    // True from showThen until the launch has actually run. It is what separates "the box is doing
    // its job" from "the box is stranded", and both values hold no reference back to the key.
    private val launchPending = WeakHashMap<Activity, Boolean>()

    /** A launch that has produced no frame by now is not going to; the box must not outlive it. */
    private const val WATCHDOG_MS = 4000L

    /** Show the overlay, wait for its frame to be drawn, then run [then] (the launch). */
    fun showThen(activity: Activity, then: () -> Unit) {
        val overlay = obtain(activity)
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        launchPending[activity] = true
        armAutoHide(activity)
        overlay.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                overlay.viewTreeObserver.removeOnPreDrawListener(this)
                overlay.post {
                    try {
                        then()
                    } finally {
                        // After the launch, not before: an auto-hide racing in between would pull
                        // the box out from under the very wait it exists for.
                        launchPending[activity] = false
                    }
                }
                return true
            }
        })
        // The backstop for a launch that never draws (the source is off-screen, so onPreDraw never
        // fires and `then` never runs): a hidden box beats a screen no tap can reach.
        overlay.postDelayed({
            if (launchPending[activity] == true) {
                launchPending[activity] = false
                hide(activity)
            }
        }, WATCHDOG_MS)
        overlay.invalidate()
    }

    /** Hide the overlay — for a launch that never happened (target gone, dialog instead). */
    fun hide(activity: Activity) {
        activity.findViewById<ViewGroup>(android.R.id.content)
            ?.findViewWithTag<View>(TAG_KEY)?.visibility = View.GONE
    }

    private fun obtain(activity: Activity): View {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.findViewWithTag<View>(TAG_KEY)?.let { return it }
        val overlay = LayoutInflater.from(activity).inflate(R.layout.overlay_opening, content, false)
        overlay.tag = TAG_KEY
        content.addView(overlay)
        return overlay
    }

    /**
     * **The invariant: this screen is never the thing on the glass with the box up.** A screen that
     * stays on the back stack (the library) must find its own UI when the user comes back, not a
     * stale box — and a stale box here is not cosmetic: the root is full-screen and clickable, so it
     * swallows *every* tap and the screen underneath is simply dead until the process is killed.
     *
     * So the rule is **hide on any resume with no launch pending**, not "hide on the first resume
     * after a pause" (B3). The pause-gated version assumed every arming is followed by a pause,
     * which the tap path guarantees and nothing else does: an activity armed while it is not
     * resumed — recreated in the background, or opening from `onCreate` on the launch-restore path —
     * resumes with no pause on record and hides nothing, forever. Observed once on the Nomad after a
     * restore behind a locked screen: the library came back dead to touch.
     *
     * `launchPending` is what keeps the restore path honest: the resume that arrives *while the
     * launch is still in flight* leaves the box alone, which is the one resume that must.
     *
     * Armed once per activity; the observer is idempotent. A source that finishes itself never
     * resumes and simply dies with its overlay.
     */
    private fun armAutoHide(activity: Activity) {
        if (activity !is LifecycleOwner) return
        if (autoHideArmed[activity] == true) return
        autoHideArmed[activity] = true
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && launchPending[activity] != true) hide(activity)
        })
    }
}
