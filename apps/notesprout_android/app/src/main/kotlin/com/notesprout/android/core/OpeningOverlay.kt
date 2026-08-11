package com.notesprout.android.core

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.notesprout.android.R
import java.util.WeakHashMap

/**
 * Source-side "Opening…" overlay for notebook launches.
 *
 * Opening a notebook is the one slow navigation in the app (seal the old file, relaunch, open the
 * `.soil`, load the first page) — noticeably so on e-ink, where the destination's own overlay only
 * appears once its first frame is drawn, leaving a dead gap between the tap and any feedback. This
 * helper closes that gap: the *source* screen raises the overlay at tap time, and only after that
 * frame has been committed does the launch itself run. The destination ([NotebookActivity]) shows
 * the same overlay from its first frame until the first page renders, so the user sees one
 * continuous "Opening…" from tap to page.
 *
 * [showThen] adds the shared [R.layout.overlay_opening] over the activity's content (reused on
 * later calls), defers [then] until the overlay's frame has actually been drawn (pre-draw + post —
 * a bare `startActivity` in the tap handler would race the frame on a busy main thread), and
 * auto-hides when the activity is next resumed after a pause, so a host that stays on the back
 * stack (library, Today, day window) is clean when the user returns. Hosts that `finish()` after
 * launching never resume, and the overlay simply dies with them.
 *
 * The overlay intercepts all touches while visible, so the double-tap a slow e-ink refresh invites
 * cannot reach the source screen twice.
 */
object OpeningOverlay {

    private val overlays = WeakHashMap<Activity, View>()
    private val autoHideArmed = WeakHashMap<Activity, Boolean>()

    /** Show the overlay, wait for its frame to be drawn, then run [then] (the launch). */
    fun showThen(activity: Activity, then: () -> Unit) {
        val overlay = obtain(activity)
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        armAutoHide(activity)
        overlay.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                overlay.viewTreeObserver.removeOnPreDrawListener(this)
                overlay.post(then)
                return true
            }
        })
        overlay.invalidate()
    }

    /** Hide the overlay on an aborted launch (target missing, etc.). */
    fun hide(activity: Activity) {
        overlays[activity]?.visibility = View.GONE
    }

    private fun obtain(activity: Activity): View {
        overlays[activity]?.let { return it }
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val overlay = LayoutInflater.from(activity).inflate(R.layout.overlay_opening, content, false)
        content.addView(overlay)
        overlays[activity] = overlay
        return overlay
    }

    /**
     * Hide again on the next resume-after-pause: the launch always pauses the source, so returning
     * to it later must find its own UI, not a stale white sheet. Armed once per activity; the
     * observer stays registered and just re-hides on every later return (idempotent).
     */
    private fun armAutoHide(activity: Activity) {
        if (activity !is LifecycleOwner) return
        if (autoHideArmed[activity] == true) return
        autoHideArmed[activity] = true
        var paused = false
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> paused = true
                Lifecycle.Event.ON_RESUME -> if (paused) hide(activity)
                else -> {}
            }
        })
    }
}
