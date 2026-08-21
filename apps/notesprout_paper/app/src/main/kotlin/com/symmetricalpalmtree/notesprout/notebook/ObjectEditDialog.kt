package com.symmetricalpalmtree.notesprout.notebook

import android.app.Activity
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.extension.EditSpec

/**
 * The object edit dialog (arc 4 / H2): an `AlertDialog` the core draws from an [EditSpec] — title,
 * one bordered text field prefilled with the spec's text (hint, `maxChars`, single- or multi-line),
 * **Save / Cancel**. Save hands the field's text to [onSave] (the provider decides what it means —
 * blank / unchanged → no change); Cancel does nothing.
 *
 * IME per `docs/design-system.md`: the keyboard opens with the dialog (`SOFT_INPUT_STATE_VISIBLE |
 * ADJUST_RESIZE`), and Save / Cancel are real click listeners that hide it through the **field's**
 * window token while the dialog is alive (BOOX doesn't auto-dismiss it; the activity's decor token
 * is the wrong token). Nothing hides it earlier — on Ratta a hardware keyboard types only while the
 * IME is shown. Enter on a single-line field = Save.
 */
object ObjectEditDialog {

    fun show(activity: Activity, spec: EditSpec, onSave: (String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_object, null)
        val field = view.findViewById<AppCompatEditText>(R.id.editText)
        field.hint = spec.hint
        field.filters = arrayOf(InputFilter.LengthFilter(spec.maxChars))
        if (spec.multiLine) {
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            field.minLines = 3
            field.maxLines = 8
            field.gravity = Gravity.TOP or Gravity.START
        } else {
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            field.maxLines = 1
            field.imeOptions = EditorInfo.IME_ACTION_DONE
        }
        field.setText(spec.text)
        field.setSelection(field.text?.length ?: 0)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(spec.title)
            .setView(view)
            .setPositiveButton(R.string.objects_edit_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        Dialogs.style(dialog)
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()

        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        fun hideIme() { imm.hideSoftInputFromWindow(field.windowToken, 0) }
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            val text = field.text?.toString().orEmpty()
            hideIme()
            dialog.dismiss()
            onSave(text)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            hideIme()
            dialog.dismiss()
        }
        if (!spec.multiLine) {
            field.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { save.performClick(); true } else false
            }
        }
        field.requestFocus()
    }
}
