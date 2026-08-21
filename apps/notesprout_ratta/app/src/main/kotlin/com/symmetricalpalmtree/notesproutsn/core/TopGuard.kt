package com.symmetricalpalmtree.notesproutsn.core

import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The top guard band — the rule that no tappable chrome may sit against the top screen edge,
 * because on BOOX a tap there pulls the status bar down instead of hitting the control.
 *
 * **Notesprout SN is Supernote-only, so the guard is always 0.** There is no status-bar hazard on
 * Ratta hardware and chrome sits flush at the top edge. The function survives as the single place
 * that decision is recorded — no screen computes a guard of its own.
 *
 * [applyInsetPadding] is unrelated to the guard: it pads a root by the real system-bar insets, and
 * with [followIme] also follows the keyboard (the Unlock screen, whose field must stay visible).
 */
object TopGuard {

    /** Always 0 on Ratta hardware — see the class note. */
    @Suppress("UNUSED_PARAMETER")
    fun heightPx(context: Context): Int = 0

    fun applyInsetPadding(root: View, followIme: Boolean = false) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottom = if (!followIme) bars.bottom else {
                maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            }
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bottom)
            insets
        }
    }

    /** Immersive screens report a 0 inset, so they take the guard directly. On SN that is 0. */
    fun applyRootPadding(root: View) {
        val guard = heightPx(root.context)
        root.setPadding(root.paddingLeft, guard, root.paddingRight, root.paddingBottom)
    }
}
