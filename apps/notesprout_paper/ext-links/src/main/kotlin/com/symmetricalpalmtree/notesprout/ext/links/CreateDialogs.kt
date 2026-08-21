package com.symmetricalpalmtree.notesprout.ext.links

import android.app.Activity
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import com.symmetricalpalmtree.notesprout.core.ActionSheetDialog
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract

/**
 * The two dialogs [LinkPickerActivity]'s create flows put up (arc 7 / L3) — kept out of the screen
 * so the picker stays about picking. Neither one talks to the host: they collect an answer and hand
 * it back; the catalog call, its busy state and its refusals belong to the flow that asked.
 *
 * [insertPosition] is the sheet a New page raises when a page card is already chosen (before it or
 * after it — tapping outside is a cancel, the sheet's own default). [folderName] is the name prompt,
 * and it is the one place in this extension with an IME: the design-system pattern —
 * `SOFT_INPUT_STATE_VISIBLE | ADJUST_RESIZE` set **before** `show()`, Create / Cancel as real click
 * listeners that hide the keyboard through the **field's** window token while the dialog is still
 * alive, nothing hiding it earlier (on Ratta a hardware keyboard types only while the IME is shown).
 * The prompt does not close itself on Create: a refused name must keep the text the user typed, so
 * only the flow closes it, through [NamePrompt].
 */
object CreateDialogs {

    /**
     * A live name prompt, handed to the flow with the typed name. [busy] disarms the Create button
     * while the host call is out (a second tap would create twice — never `isEnabled`, which on
     * e-ink is invisible); [close] hides the keyboard the right way and dismisses.
     */
    class NamePrompt internal constructor(
        private val dialog: AlertDialog,
        private val field: AppCompatEditText,
        private val create: Button,
    ) {
        fun busy(on: Boolean) {
            create.isClickable = !on
        }

        fun close() {
            val imm = field.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(field.windowToken, 0)
            dialog.dismiss()
        }
    }

    /**
     * Where a new page goes relative to the page the user has already chosen. Title-less by design —
     * the two rows say the whole thing.
     */
    fun insertPosition(activity: Activity, onChosen: (before: Boolean) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        ActionSheetDialog(activity)
            .addAction(null, activity.getString(R.string.links_insert_before)) { onChosen(true) }
            .addAction(null, activity.getString(R.string.links_insert_after)) { onChosen(false) }
            .show()
    }

    /**
     * Ask for a folder name. [onCreate] gets the **trimmed** text and the live prompt; the host does
     * the real validating (charset, reserved names, duplicate siblings) and its refusal is the
     * message the flow shows — so nothing here rejects a name beyond the contract's length cap.
     */
    fun folderName(activity: Activity, onCreate: (String, NamePrompt) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_link_name, null)
        val field = view.findViewById<AppCompatEditText>(R.id.nameField)
        field.hint = activity.getString(R.string.links_folder_name_hint)
        field.filters = arrayOf(InputFilter.LengthFilter(ExtensionContract.MAX_NAME_CHARS))
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.maxLines = 1
        field.imeOptions = EditorInfo.IME_ACTION_DONE

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.links_new_folder)
            .setView(view)
            .setPositiveButton(R.string.links_create, null)
            .setNegativeButton(R.string.links_cancel, null)
            .create()
        Dialogs.style(dialog)
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()

        val create = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val prompt = NamePrompt(dialog, field, create)
        create.setOnClickListener {
            onCreate(field.text?.toString().orEmpty().trim(), prompt)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { prompt.close() }
        // Enter is Create — but `performClick` fires the listener even on a disarmed button, so the
        // busy state has to be re-checked here or a keyboard Done would create a second time.
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            if (create.isClickable) create.performClick()
            true
        }
        field.requestFocus()
    }
}
