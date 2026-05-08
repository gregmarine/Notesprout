package com.notesprout.notesprout.ui

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog

object EinkStyle {
    fun flatBackground(cornerRadiusDp: Float = 8f, borderWidthDp: Float = 1.5f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            setStroke(dpToPx(borderWidthDp).toInt(), Color.BLACK)
            cornerRadius = dpToPx(cornerRadiusDp)
        }
    }

    fun flatBackgroundNoBorder(cornerRadiusDp: Float = 8f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = dpToPx(cornerRadiusDp)
        }
    }

    fun applyToDialog(dialog: AlertDialog) {
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawable(flatBackground(cornerRadiusDp = 8f, borderWidthDp = 1.5f))
    }

    private fun dpToPx(dp: Float): Float {
        return dp * Resources.getSystem().displayMetrics.density
    }
}
