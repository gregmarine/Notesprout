package com.symmetricalpalmtree.notesproutsn.notebook

import android.text.InputType
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.markdown.HeadingPrefix

/**
 * Fix a heading's words: one field, Save, Cancel — the [NameDialog][com.symmetricalpalmtree.notesproutsn.library.NameDialog]
 * shape, because typing a line of text is the same interaction wherever it happens.
 *
 * **Hash-free.** The stored `text` is markdown (`"## Meeting notes"`) but the level lives in its own
 * column and is authoritative, so the field shows [HeadingPrefix.stripHeadingPrefix]'s bare title and
 * the caller puts the prefix back through `applyLevel`. A user who typed `##` here would only be
 * fighting the level buttons.
 *
 * **An empty Save is a real answer**, not a validation failure: clearing the words is how a heading
 * is taken back off the page, and this dialog does not second-guess it — [onSave] gets `""` and the
 * caller deletes. Cancel does nothing at all. Nothing here touches the store.
 *
 * **Ratta: the IME is never hidden.** On Supernote a hardware keyboard only delivers keys while the
 * IME is shown, so a `hideSoftInputFromWindow` anywhere in this dialog would strand a keyboard user
 * mid-title (the same rule as `UnlockActivity`). There is none, and there must not be one — the only
 * soft-input call here asks for the IME, on the way in.
 */
object HeadingEditDialog {

    fun show(activity: AppCompatActivity, heading: Heading, onSave: (String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val initial = HeadingPrefix.stripHeadingPrefix(heading.text)

        val input = AppCompatEditText(activity).apply {
            setText(initial)
            // Caret at the end, nothing selected: the user came here to fix a word, not to retype
            // the line, and a full selection turns the first keystroke into a wipe.
            setSelection(initial.length)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.inkBlack))
            background = ContextCompat.getDrawable(activity, R.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSingleLine()
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * d).toInt()
            setPadding(side, (16 * d).toInt(), side, 0)
            addView(input)
        }

        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.heading_edit_title)
                .setView(wrapper)
                .setPositiveButton(R.string.heading_edit_save) { _, _ ->
                    onSave(input.text?.toString()?.trim().orEmpty())
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        // Ask for the IME with the window rather than poking InputMethodManager after the fact: on
        // Supernote the panel has to be up for a hardware keyboard to type at all.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
