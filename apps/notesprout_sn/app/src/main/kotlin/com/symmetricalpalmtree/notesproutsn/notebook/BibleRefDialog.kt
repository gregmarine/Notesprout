package com.symmetricalpalmtree.notesproutsn.notebook

import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs

/**
 * Say which passage (arc 38 / R3): one field, Save, Cancel — [HeadingEditDialog]'s recipe to the
 * line, because typing a line of text is the same interaction wherever it happens. Three callers,
 * one shape: the lasso bar's Bible (prefilled with what the recognizer read), the Insert bar's
 * (empty), and the Edit of a lone Bible link (the wrapped text's own words).
 *
 * **The words shown are the user's own, and stay so** (the arc's decision 2): what is typed here is
 * what the page will read. The reference underneath is the extension's answer to it, and the caller
 * never rewrites the field with the canonical form.
 *
 * **A blank Save is a Cancel here**, which is the one deliberate difference from the heading and
 * text dialogs, where blank means delete: a Bible link's text is its reference, and "no words" is
 * not a reference to anything — there is nothing honest to keep and nothing to write. Unlink is how
 * a reference stops being a link; Delete is how it leaves the page.
 *
 * **Ratta: the IME is never hidden.** On Supernote a hardware keyboard only delivers keys while the
 * IME is shown, so a `hideSoftInputFromWindow` anywhere in this dialog would strand a keyboard user
 * mid-reference (`UnlockActivity`'s rule, and [HeadingEditDialog]'s). There is none, and there must
 * not be one — the only soft-input call here asks for the IME, on the way in.
 *
 * **Arc 40 "Verses"** added one row under the field on the two creating doors: the **"Insert the
 * verses"** pill (`Widget.Notesprout.Toggle`'s shape, built here in code), off by default. On,
 * the reference still resolves exactly as before and then its verses are read and land instead
 * of the words typed — the caller's business; the dialog only reports the switch. The Edit of a
 * placed reference never shows it: an edit changes what a reference points at, not what kind of
 * object it is.
 *
 * Nothing here touches the store, and nothing typed is ever logged.
 */
object BibleRefDialog {

    /**
     * @param initial what the field opens with — the recognized words, an earlier attempt the
     *   reader refused, or the link's own text. The caret lands at the end with nothing selected:
     *   the user came to fix a chapter number, not to retype the line.
     * @param onSave the field's trimmed text, never blank (a blank Save does nothing at all).
     */
    fun show(activity: AppCompatActivity, initial: String, onSave: (String) -> Unit) =
        show(activity, initial, offerVerses = false) { typed, _ -> onSave(typed) }

    /**
     * The creating doors' showing (arc 40): with [offerVerses] the "Insert the verses" pill sits
     * under the field, and [onSave]'s second argument is whether it was on. Off, this is [show]
     * exactly.
     */
    fun show(
        activity: AppCompatActivity,
        initial: String,
        offerVerses: Boolean,
        onSave: (typed: String, verses: Boolean) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
        val pad = (12 * d).toInt()

        val input = AppCompatEditText(activity).apply {
            setText(initial)
            setSelection(initial.length)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.inkBlack))
            setHintTextColor(ContextCompat.getColor(activity, R.color.inkLight))
            setHint(R.string.bible_reference_hint)
            background = ContextCompat.getDrawable(activity, R.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSingleLine()
        }
        // The pill: an AppCompatCheckBox with no button and the two-state pill as its background —
        // `Widget.Notesprout.Toggle` item for item (the style cannot be applied to a view built in
        // code, so its four items are set here; the 56 dp width is the pill drawable's geometry).
        val verses = AppCompatCheckBox(activity).apply {
            buttonDrawable = null
            background = ContextCompat.getDrawable(activity, R.drawable.toggle_pill)
            stateListAnimator = null
            isChecked = false
            contentDescription = activity.getString(R.string.bible_verses_switch)
        }
        val versesRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, 0)
            addView(
                AppCompatTextView(activity).apply {
                    text = activity.getString(R.string.bible_verses_switch)
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(activity, R.color.inkBlack))
                    setOnClickListener { verses.toggle() }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                verses,
                LinearLayout.LayoutParams(
                    (56 * d).toInt(),
                    activity.resources.getDimensionPixelSize(R.dimen.toolbar_button_size),
                ),
            )
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * d).toInt()
            setPadding(side, (16 * d).toInt(), side, 0)
            addView(input)
            if (offerVerses) addView(versesRow)
        }

        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.bible_reference_title)
                .setView(wrapper)
                .setPositiveButton(R.string.heading_edit_save) { _, _ ->
                    val typed = input.text?.toString()?.trim().orEmpty()
                    if (typed.isNotEmpty()) onSave(typed, offerVerses && verses.isChecked)
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
