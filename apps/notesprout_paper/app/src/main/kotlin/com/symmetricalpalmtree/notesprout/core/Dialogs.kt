package com.symmetricalpalmtree.notesprout.core

import android.app.Activity
import androidx.annotation.StringRes
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

    /**
     * The "why did nothing happen" dialog — one title, one message, OK. The rule (settled in M1):
     * a toast only confirms something that already happened ("copied"); anything the user must notice
     * — a tap that did nothing, a failure, a one-time download — is a dialog, because on e-ink a
     * toast is easy to miss and reads as "broken". No-op if the activity is going away.
     */
    fun problem(activity: Activity, title: CharSequence, message: CharSequence) {
        if (activity.isFinishing || activity.isDestroyed) return
        style(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .create()
        ).show()
    }

    fun problem(activity: Activity, @StringRes titleRes: Int, message: CharSequence) =
        problem(activity, activity.getString(titleRes), message)

    fun problem(activity: Activity, @StringRes titleRes: Int, @StringRes messageRes: Int) =
        problem(activity, activity.getString(titleRes), activity.getString(messageRes))
}
