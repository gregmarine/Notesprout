package com.symmetricalpalmtree.notesprout.core

import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.notesprout.R

/** The e-ink AlertDialog look: no elevation, 2dp inkBlack bordered window. Apply after `create()`. */
object Dialogs {
    fun style(dialog: AlertDialog): AlertDialog {
        dialog.setOnShowListener {
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
        }
        return dialog
    }
}
