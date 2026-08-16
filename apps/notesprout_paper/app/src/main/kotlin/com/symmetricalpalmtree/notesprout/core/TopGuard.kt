package com.symmetricalpalmtree.notesprout.core

import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object TopGuard {

    private const val FALLBACK_DP = 24f

    fun heightPx(context: Context): Int {
        if (isRattaDevice()) return 0
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

    fun applyRootPadding(root: View) {
        val guard = heightPx(root.context)
        root.setPadding(root.paddingLeft, guard, root.paddingRight, root.paddingBottom)
    }
}
