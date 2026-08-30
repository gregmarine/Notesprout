package com.symmetricalpalmtree.notesproutsn.core

import android.app.Activity
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.notesproutsn.screen.R

/** The e-ink AlertDialog look: no elevation, 2dp inkBlack bordered window. Apply after `create()`. */
object Dialogs {
    fun style(dialog: AlertDialog): AlertDialog {
        dialog.setOnShowListener {
            dialog.window?.setElevation(0f)
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
        }
        return dialog
    }

    private fun oneButton(
        activity: Activity, title: CharSequence, message: CharSequence, onDismiss: (() -> Unit)?,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = style(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .create()
        )
        if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    /**
     * The "why did nothing happen" dialog — one title, one message, OK. The rule: a toast only
     * confirms something that already happened ("copied"); anything the user must notice — a tap
     * that did nothing, a failure, a one-time download — is a dialog, because on e-ink a toast is
     * easy to miss and reads as "broken". No-op if the activity is going away.
     */
    fun problem(activity: Activity, title: CharSequence, message: CharSequence) =
        oneButton(activity, title, message, onDismiss = null)

    fun problem(activity: Activity, @StringRes titleRes: Int, message: CharSequence) =
        problem(activity, activity.getString(titleRes), message)

    fun problem(activity: Activity, @StringRes titleRes: Int, @StringRes messageRes: Int) =
        problem(activity, activity.getString(titleRes), activity.getString(messageRes))

    /**
     * The "it worked, and it mattered" dialog — same one-title/one-message/OK shape as [problem],
     * for the handful of confirmations where the toast-confirms rule isn't enough: the result
     * carries information (a count, a destination folder) the user would otherwise only get from a
     * toast that's easy to miss, or the confirming screen is about to close under it. [onDismiss]
     * runs whether OK or back dismissed it.
     */
    fun confirm(activity: Activity, title: CharSequence, message: CharSequence, onDismiss: (() -> Unit)? = null) =
        oneButton(activity, title, message, onDismiss)

    fun confirm(
        activity: Activity, @StringRes titleRes: Int, message: CharSequence, onDismiss: (() -> Unit)? = null,
    ) = confirm(activity, activity.getString(titleRes), message, onDismiss)

    fun confirm(
        activity: Activity, @StringRes titleRes: Int, @StringRes messageRes: Int, onDismiss: (() -> Unit)? = null,
    ) = confirm(activity, activity.getString(titleRes), activity.getString(messageRes), onDismiss)
}
