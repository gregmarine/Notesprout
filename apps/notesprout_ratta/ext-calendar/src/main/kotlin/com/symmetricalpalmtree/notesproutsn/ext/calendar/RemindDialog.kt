package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Activity
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
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
 * **It applies on Save and discards on Cancel**: the amount and the unit are local `var`s until the
 * positive button hands a new draft back, which is what makes the stepper free to be tapped at.
 */
object RemindDialog {

    /**
     * @param draft the event as it stands; the dialog seeds from its **first** reminder, or one day
     *   when it has none — a day of lead is the shortest one the look-ahead can act on.
     */
    fun show(activity: Activity, draft: EventDraft, onSaved: (EventDraft) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_remind, null)
        val tvRemind = view.findViewById<TextView>(R.id.tvRemind)
        val tvAmount = view.findViewById<TextView>(R.id.tvRemindAmount)
        val latchDays = view.findViewById<Button>(R.id.latchRemindDays)
        val latchWeeks = view.findViewById<Button>(R.id.latchRemindWeeks)

        val first = draft.reminders.firstOrNull()
        var amount = first?.amount?.coerceIn(REMIND_RANGE) ?: 1
        var unit = first?.unit ?: ReminderUnit.DAYS

        fun render() {
            tvAmount.text = amount.toString()
            // Latches, not a toggle: the armed half is `isSelected`, which reads as a border on
            // e-ink, and tapping the half already armed is a no-op rather than a flip to the other.
            latchDays.isSelected = unit == ReminderUnit.DAYS
            latchWeeks.isSelected = unit == ReminderUnit.WEEKS
            tvRemind.text = EventWording.reminderLabel(Reminder(amount, unit))
        }

        fun stepper(id: Int, delta: Int) {
            val button = view.findViewById<ImageButton>(id)
            TooltipCompat.setTooltipText(button, button.contentDescription)
            // Clamped, never disabled: a disabled control is invisible on e-ink, so at the ends the
            // stepper simply has nothing left to do.
            button.setOnClickListener { amount = (amount + delta).coerceIn(REMIND_RANGE); render() }
        }
        stepper(R.id.btnRemindMinus, -1)
        stepper(R.id.btnRemindPlus, 1)
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
                // because clearing is neither saving what the stepper says nor changing nothing.
                .setNeutralButton(R.string.editor_remind_none) { _, _ -> onSaved(draft.withReminder(null)) }
                .create(),
        ).show()
    }

    /** The stepper's range. Ninety-nine days of lead is already more than a paper calendar gives you. */
    private val REMIND_RANGE = 1..99
}
