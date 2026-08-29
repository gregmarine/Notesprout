package com.symmetricalpalmtree.notesproutsn.importing

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import com.symmetricalpalmtree.notesproutsn.R

/**
 * The import's staged-progress box (arc 16 / I1) — the library has no status line of its own, and
 * an import is long enough that a tap with no answer would read as a tap that missed.
 *
 * It is deliberately **not** `OpeningOverlay`: that one exists to get a frame on the glass *before*
 * a `startActivity` jumps the traversal's sync barrier, and pays for it with a pre-draw hop, a
 * watchdog and a resume-time auto-hide. Nothing here needs any of that — the import's very first
 * step is a hop to IO, so the main thread is free to draw the moment the view is made visible, and
 * the flow's own `finally` takes the box down on every path including a cancelled one. What is
 * shared is the shape: a full-screen transparent click-eater with one bordered box in the middle.
 *
 * The stage text is rewritten in place ([stage]) rather than re-shown, so each step is one small
 * repaint rather than a whole frame.
 */
object ImportOverlay {

    // A tag lookup in the activity's own view tree, never a map keyed on the Activity: a
    // Map<Activity, View> leaks every Activity for the process lifetime, because the cached View
    // strongly references its Activity through View.context (the OpeningOverlay lesson).
    private const val TAG_KEY = "notesproutsn.importOverlay"

    fun show(activity: Activity, @StringRes textRes: Int) {
        val overlay = obtain(activity) ?: return
        stageIn(overlay, textRes)
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        overlay.invalidate()
    }

    /** Move the commentary on. A no-op when the box is not up. */
    fun stage(activity: Activity, @StringRes textRes: Int) {
        val overlay = find(activity) ?: return
        if (overlay.visibility != View.VISIBLE) return
        stageIn(overlay, textRes)
    }

    fun hide(activity: Activity) {
        find(activity)?.visibility = View.GONE
    }

    private fun stageIn(overlay: View, @StringRes textRes: Int) {
        overlay.findViewById<TextView>(R.id.importStage)?.setText(textRes)
    }

    private fun find(activity: Activity): View? =
        activity.findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag(TAG_KEY)

    private fun obtain(activity: Activity): View? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        find(activity)?.let { return it }
        val overlay = LayoutInflater.from(activity).inflate(R.layout.overlay_import, content, false)
        overlay.tag = TAG_KEY
        content.addView(overlay)
        return overlay
    }
}
