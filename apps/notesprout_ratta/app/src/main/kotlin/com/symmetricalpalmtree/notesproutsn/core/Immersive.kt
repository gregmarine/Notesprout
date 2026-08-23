package com.symmetricalpalmtree.notesproutsn.core

import android.view.View
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The one immersive recipe — hide the system bars, transient-by-swipe to peek them back. Both the
 * notebook window and the Contents dialog's window (post-`show()`, once its decor is attached) go
 * through here, so a tune to the bar behaviour can never leave the two windows on one physical
 * screen disagreeing.
 */
object Immersive {
    fun apply(window: Window, anchor: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, anchor).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
