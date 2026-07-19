package com.notesprout.android.core

import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Top guard band — the strip along the top edge of the screen that no tappable chrome may occupy.
 *
 * On BOOX, reaching for a control parked hard against the top edge tends to pull the Android status
 * bar down instead of hitting the button. MainActivity has always avoided this by padding its root
 * by the live system-bar inset; that works only because MainActivity leaves the status bar visible.
 * The drawing surfaces (Notebook, Calendar, Day detail, Page index, pickers) run immersive with the
 * bars hidden, so their inset is 0 and the same listener would be a no-op — they need the guard as a
 * fixed reservation instead.
 *
 * The guard is the *device's* status bar height, read from the platform resource. That value is
 * available whether or not the bar is currently showing, so a top toolbar sits at exactly the same
 * height on an immersive screen as it does in the library.
 *
 * Rule of thumb: the guard applies to anything the user taps. It does **not** constrain drawing —
 * canvases stay full-bleed and ink is welcome in the guard band. Drawing is excluded only from the
 * chrome's own bounds, which already follow the shifted toolbar.
 */
object TopGuard {

    /** Fallback when the platform resource is missing — matches the common 24dp status bar. */
    private const val FALLBACK_DP = 24f

    /** The guard height in pixels for this device. */
    fun heightPx(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) {
            val px = context.resources.getDimensionPixelSize(id)
            if (px > 0) return px
        }
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            FALLBACK_DP,
            context.resources.displayMetrics,
        ).toInt()
    }

    /**
     * Pad [root] by the live system bar insets — the library's behaviour, for screens that keep the
     * system bars visible. Preserves any horizontal padding already set on the view.
     */
    fun applyInsetPadding(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
    }

    /**
     * Reserve the guard as top padding on [root] — for immersive screens whose top-anchored chrome is
     * the first child of a vertical root. Preserves the view's other padding.
     */
    fun applyRootPadding(root: View) {
        val guard = heightPx(root.context)
        root.setPadding(root.paddingLeft, guard, root.paddingRight, root.paddingBottom)
    }
}
