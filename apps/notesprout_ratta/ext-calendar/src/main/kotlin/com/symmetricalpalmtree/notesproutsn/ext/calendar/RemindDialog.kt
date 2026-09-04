package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Activity
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs

/**
 * "Remind me when?" — everything behind the editor's Remind glance button (arc 24 / Z2, the user's
 * design), in [TimePickerDialog]'s shape: a live preview over the controls that set it, and every
 * word it shows is [EventWording]'s so the dialog and the glance button cannot disagree.
 *
 * **One reminder** (the user's call). The editor therefore offers an amount and a unit, not a list:
 * Save replaces whatever the event held, and **None** clears it. [EventRules.REMINDERS_MAX] stays —
 * it is the store's rule, and an event written before this screen existed may still carry three;
 * saving from here is what reduces it to one.
 *
 * The amount is the shared [CountLatches] row since arc 24 / Z5b — six presets and a keypad past
 * them, the same control the repeat dialog's two counts use.
 *
 * **It applies on Save and discards on Cancel**: the amount and the unit are local `var`s until the
 * positive button hands a new draft back, which is what makes every latch free to be tapped at.
 */
object RemindDialog {

    /**
     * @param draft the event as it stands; the dialog seeds from its **first** reminder, or one day
     *   when it has none — a day of lead is the shortest one the look-ahead can act on.
     */
    fun show(activity: Activity, draft: EventDraft, onSaved: (EventDraft) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_remind, null)
        val tvRemind = view.findViewById<TextView>(R.id.tvRemind)
        val latchDays = view.findViewById<Button>(R.id.latchRemindDays)
        val latchWeeks = view.findViewById<Button>(R.id.latchRemindWeeks)

        val first = draft.reminders.firstOrNull()
        var amount = first?.amount?.coerceIn(REMIND_RANGE) ?: 1
        var unit = first?.unit ?: ReminderUnit.DAYS

        // Declared before `render` and assigned after it: the row answers through `render`, so the
        // knot only unties one way round (`RepeatDialog`'s two rows are wired the same way).
        lateinit var amountLatches: CountLatches

        fun render() {
            amountLatches.render(amount)
            // Latches, not a toggle: the armed half is `isSelected`, which reads as a border on
            // e-ink, and tapping the half already armed is a no-op rather than a flip to the other.
            latchDays.isSelected = unit == ReminderUnit.DAYS
            latchWeeks.isSelected = unit == ReminderUnit.WEEKS
            tvRemind.text = EventWording.reminderLabel(Reminder(amount, unit))
        }

        // Clamped, never refused: whatever the presets or the keypad answer, the range is what the
        // reminder ends up inside.
        amountLatches = CountLatches(
            view.findViewById(R.id.rowRemind), activity, REMIND_RANGE, R.string.editor_remind,
        ) { n -> amount = n.coerceIn(REMIND_RANGE); render() }
        latchDays.setOnClickListener { unit = ReminderUnit.DAYS; render() }
        latchWeeks.setOnClickListener { unit = ReminderUnit.WEEKS; render() }
        render()

        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.editor_remind)
                .setView(view)
                .setPositiveButton(R.string.editor_save) { _, _ ->
                    onSaved(draft.withReminder(Reminder(amount, unit)))
                }
                .setNegativeButton(R.string.cancel, null)
                // "No reminder" is a real answer and needs a way back to it — the third button,
                // because clearing is neither saving what the latches say nor changing nothing.
                .setNeutralButton(R.string.editor_remind_none) { _, _ -> onSaved(draft.withReminder(null)) }
                .create(),
        ).show()
    }

    /** The amount's range. Ninety-nine days of lead is already more than a paper calendar gives you. */
    private val REMIND_RANGE = 1..99
}
