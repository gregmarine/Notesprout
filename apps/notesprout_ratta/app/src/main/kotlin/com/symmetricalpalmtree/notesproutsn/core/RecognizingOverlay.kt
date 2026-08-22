package com.symmetricalpalmtree.notesproutsn.core

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.symmetricalpalmtree.notesproutsn.R

/**
 * The "Recognizing…" box shown while a heading convert is out at the recognizer extension (N2) —
 * [OpeningOverlay]'s smaller sibling, and deliberately not a dialog.
 *
 * The wait belongs to the level tap the user has just made: half a second when the extension is
 * warm, several seconds when its process is cold on a Nomad. A dialog would be a full e-ink repaint
 * that the result has to undo a moment later, while this repaints only the box's region and leaves
 * the page — and the selection the convert is about — on the glass underneath. The root is
 * transparent, clickable and focusable, so the second tap a slow refresh invites lands on the shield
 * instead of the toolbar.
 *
 * **No `showThen` machinery here** — unlike a notebook launch, nothing on Main follows the show: the
 * recognize call runs off Main, so the traversal that draws the box is never jumped.
 *
 * Frame-silence: the show is one chrome frame at a deliberate act (a level tap on chrome), the same
 * justification as the selection toolbar's own show — never a frame under live ink.
 *
 * The caller owns the pairing and always hides in a `finally`; both calls are idempotent, and the
 * view is cached per activity so a second convert reuses it rather than stacking shields. The cache
 * is a **tag lookup in the activity's own view tree**, never a map keyed on the Activity: a
 * `WeakHashMap<Activity, View>` here would leak every Activity for the process lifetime, because the
 * cached View strongly references its Activity through `View.context` — value → key defeats the
 * weak key entirely.
 */
object RecognizingOverlay {

    private val TAG_KEY = "notesproutsn.recognizingOverlay"

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val overlay = obtain(activity) ?: return
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
    }

    fun hide(activity: Activity) {
        activity.findViewById<ViewGroup>(android.R.id.content)
            ?.findViewWithTag<View>(TAG_KEY)?.visibility = View.GONE
    }

    private fun obtain(activity: Activity): View? {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        content.findViewWithTag<View>(TAG_KEY)?.let { return it }
        val overlay = LayoutInflater.from(activity).inflate(R.layout.overlay_recognizing, content, false)
        overlay.tag = TAG_KEY
        content.addView(overlay)
        return overlay
    }
}
