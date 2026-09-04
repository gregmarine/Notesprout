package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Activity
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesproutsn.core.Dialogs

/**
 * The way past six (arc 24 / Z5b) — the digit pad behind [CountLatches]' More latch, shared by the
 * repeat interval, the "after N times" count and the reminder's amount. What it types is
 * [KeypadModel]'s and JVM-tested; this file is views and listeners only.
 *
 * **Done is a key, not a button bar.** The thumb is already on the grid, so ✓ finishes there and the
 * dialog's own bar carries Cancel alone — two doors to one answer is one door too many. Cancel
 * writes nothing, which is what makes the whole pad free to be tapped at.
 *
 * The preview shows [current] until a digit lands and the typed string after that, so the field
 * being changed is never blank and the person can always see which number they are replacing.
 */
object KeypadDialog {

    /**
     * @param current the value the field holds now — shown until something is typed, and handed
     *   back unchanged by a ✓ on an empty pad (a ⌫ back to nothing is not "clear the field").
     * @param range what the field will accept; it sets both the typing width and the final clamp.
     */
    fun show(
        activity: Activity,
        @StringRes titleRes: Int,
        current: Int,
        range: IntRange,
        onDone: (Int) -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_keypad, null)
        val preview = view.findViewById<TextView>(R.id.tvKeypad)
        val model = KeypadModel(range, current)

        fun render() {
            preview.text = model.text.ifEmpty { current.toString() }
        }

        val keys = listOf(
            R.id.key0 to 0, R.id.key1 to 1, R.id.key2 to 2, R.id.key3 to 3, R.id.key4 to 4,
            R.id.key5 to 5, R.id.key6 to 6, R.id.key7 to 7, R.id.key8 to 8, R.id.key9 to 9,
        )
        for ((id, digit) in keys) {
            val key = view.findViewById<Button>(id)
            key.text = digit.toString()
            key.setOnClickListener { model.digit(digit); render() }
        }

        // The two icon keys name themselves on a long press — a glyph on e-ink is worth learning
        // only if there is something that says what it means.
        fun iconKey(id: Int, onTap: () -> Unit): ImageButton {
            val button = view.findViewById<ImageButton>(id)
            TooltipCompat.setTooltipText(button, button.contentDescription)
            button.setOnClickListener { onTap() }
            return button
        }
        iconKey(R.id.keyBackspace) { model.backspace(); render() }
        render()

        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .create(),
        )
        iconKey(R.id.keyDone) {
            onDone(model.value() ?: current)
            dialog.dismiss()
        }
        dialog.show()
    }
}
