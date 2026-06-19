package com.notesprout.android.sort

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatRadioButton
import android.widget.RadioGroup
import com.notesprout.android.R

class SortDialog(
    private val context: Context,
    private val current: SortPreferences,
    private val itemNoun: String = "Notebooks",
    private val onApply: (SortPreferences) -> Unit,
) {
    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_sort, null)

        val rgSortField = view.findViewById<RadioGroup>(R.id.rgSortField)
        val rgSortOrder = view.findViewById<RadioGroup>(R.id.rgSortOrder)
        val rgFolderSort = view.findViewById<RadioGroup>(R.id.rgFolderSort)
        val btnCancel = view.findViewById<AppCompatButton>(R.id.btnSortCancel)
        val btnApply = view.findViewById<AppCompatButton>(R.id.btnSortApply)

        view.findViewById<TextView>(R.id.tvFolderSortHeader).text = "Folders & $itemNoun"
        view.findViewById<AppCompatRadioButton>(R.id.rbNotebooksFirst).text = "$itemNoun first"

        rgSortField.check(when (current.field) {
            SortField.NAME -> R.id.rbSortName
            SortField.DATE_MODIFIED -> R.id.rbSortDateModified
        })

        rgSortOrder.check(when (current.order) {
            SortOrder.ASCENDING -> R.id.rbOrderAscending
            SortOrder.DESCENDING -> R.id.rbOrderDescending
        })

        rgFolderSort.check(when (current.folderSort) {
            FolderSort.FOLDERS_FIRST -> R.id.rbFolderFirst
            FolderSort.NOTEBOOKS_FIRST -> R.id.rbNotebooksFirst
            FolderSort.MIXED -> R.id.rbMixed
        })

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnApply.setOnClickListener {
            val field = when (rgSortField.checkedRadioButtonId) {
                R.id.rbSortDateModified -> SortField.DATE_MODIFIED
                else -> SortField.NAME
            }
            val order = when (rgSortOrder.checkedRadioButtonId) {
                R.id.rbOrderDescending -> SortOrder.DESCENDING
                else -> SortOrder.ASCENDING
            }
            val folderSort = when (rgFolderSort.checkedRadioButtonId) {
                R.id.rbNotebooksFirst -> FolderSort.NOTEBOOKS_FIRST
                R.id.rbMixed -> FolderSort.MIXED
                else -> FolderSort.FOLDERS_FIRST
            }
            onApply(SortPreferences(field, order, folderSort))
            dialog.dismiss()
        }

        dialog.show()

        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }
}
