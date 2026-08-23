package com.symmetricalpalmtree.notesproutsn.library

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs

/**
 * The one "type a name" dialog — new folder and rename are the same interaction with different
 * words, so they are the same code.
 *
 * The positive button is wired **after** `show()` so a rejected name keeps the dialog open: the
 * default `setPositiveButton` listener dismisses before anything can object, which would throw the
 * user's typing away every time they hit an invalid character. [onAccept] therefore decides when to
 * close, and gets a `dismiss` callback to do it with — the duplicate-name check is a database
 * round-trip, so only the caller knows when the name is really good. That also means re-entry is
 * the caller's to guard: the accept path crosses a coroutine, and on e-ink an unguarded OK gets
 * double-tapped (the library's own latch lesson).
 *
 * New folder also carries a **second, optional field** (arc 5): the folder's default-notebook-name
 * scheme. The caller builds it with [SchemeDialog.buildField], passes it as [extraField], and reads
 * it back itself in its accept closure — rename passes nothing and knows nothing about schemes.
 */
object NameDialog {

    /**
     * The bordered single-line text input every library dialog uses — one recipe, so the two
     * fields stacked in the New-folder dialog can never drift visibly apart. A non-empty
     * [initial] arrives select-all'd, ready to be typed over.
     */
    fun input(context: Context, hintRes: Int, initial: String? = null): EditText {
        val pad = (12 * context.resources.displayMetrics.density).toInt()
        return EditText(context).apply {
            setHint(hintRes)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.inkBlack))
            background = ContextCompat.getDrawable(context, R.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSingleLine()
            if (!initial.isNullOrEmpty()) { setText(initial); setSelection(0, initial.length) }
        }
    }

    fun show(
        activity: Activity,
        titleRes: Int,
        confirmRes: Int,
        initial: String = "",
        hintRes: Int = R.string.rename_hint,
        extraField: SchemeDialog.FieldViews? = null,
        onAccept: (name: String, dismiss: () -> Unit) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density

        val input = input(activity, hintRes, initial)
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * d).toInt()
            setPadding(side, (16 * d).toInt(), side, 0)
            addView(input)
            extraField?.addTo(this)
        }

        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setView(wrapper)
                .setPositiveButton(confirmRes, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            onAccept(input.text.toString().trim()) { dialog.dismiss() }
        }
    }

    /** Turn a [NameRules.Problem] into the sentence the user sees. */
    fun problemMessage(activity: Activity, problem: NameRules.Problem): String = activity.getString(
        when (problem) {
            NameRules.Problem.EMPTY -> R.string.name_empty
            NameRules.Problem.RESERVED -> R.string.name_reserved
            NameRules.Problem.CHARSET -> R.string.name_charset
        }
    )
}
