package com.notesprout.android.search

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.notesprout.android.R
import com.notesprout.android.databinding.DialogNewNotebookBinding

object SearchDialog {

    fun show(
        context: Context,
        initialQuery: String = "",
        onSearch: (String) -> Unit,
        onCancel: () -> Unit
    ) {
        val dialogBinding = DialogNewNotebookBinding.inflate(LayoutInflater.from(context))
        dialogBinding.editNotebookName.apply {
            hint = "Search notebooks…"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initialQuery)
            setSelection(initialQuery.length)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Search")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss(); onCancel() }
            .setPositiveButton("Search", null)
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val query = dialogBinding.editNotebookName.text?.toString()?.trim().orEmpty()
            if (query.isBlank()) {
                dialogBinding.editNotebookName.error = "Enter a search phrase"
                return@setOnClickListener
            }
            dialog.dismiss()
            onSearch(query)
        }

        dialogBinding.editNotebookName.requestFocus()
        dialogBinding.editNotebookName.postDelayed({
            ViewCompat.getWindowInsetsController(dialogBinding.editNotebookName)
                ?.show(WindowInsetsCompat.Type.ime())
                ?: run {
                    val imm = context.getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm.showSoftInput(dialogBinding.editNotebookName, InputMethodManager.SHOW_IMPLICIT)
                }
        }, 100)
    }
}
