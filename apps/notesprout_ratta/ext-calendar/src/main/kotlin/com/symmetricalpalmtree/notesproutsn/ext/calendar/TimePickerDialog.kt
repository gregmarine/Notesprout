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
 * "What time?" — the editor's start/end time dialog (arc 24 / Z2), in [DayPickerDialog]'s shape:
 * the extension's own, built on the layout, and every rule it applies is [TimeMath]'s and
 * JVM-tested. This file is views and listeners only.
 *
 * **Three independent parts, not a clock.** Two steppers and a latch pair — hour 1..12 wrapping,
 * minutes in fives wrapping 0..55, AM/PM — because that is the whole vocabulary of "a time on a
 * calendar page" and it is four tap targets rather than a scrolling wheel nobody can land on with a
 * pen on e-ink. **Neither stepper carries into the other**: rolling the minutes past the top does
 * not move the hour, so no stepper can walk the value off the end of the day and nothing changes
 * behind the person's back.
 *
 * The preview line above them is the same sentence the row will read once the dialog is gone
 * ([EventWording.minute]) — one wording, so the picker cannot describe the time one way and the
 * editor another.
 *
 * OK and Cancel are the AlertDialog's own buttons: Cancel writes nothing at all, which is what makes
 * the steppers free to be tapped at.
 */
object TimePickerDialog {

    /**
     * @param initialMinute the time the field already holds, or null for a field that has none —
     *   which opens at [TimeMath.DEFAULT_MINUTE] rather than at midnight, because midnight is a
     *   time almost nobody means and the top of the morning is a time many do.
     */
    fun show(
        activity: Activity,
        @StringRes titleRes: Int,
        initialMinute: Int?,
        onPicked: (Int) -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_time_picker, null)
        val tvTime = view.findViewById<TextView>(R.id.tvTime)
        val tvHour = view.findViewById<TextView>(R.id.tvHour)
        val tvMinute = view.findViewById<TextView>(R.id.tvMinute)
        val latchAm = view.findViewById<Button>(R.id.latchAm)
        val latchPm = view.findViewById<Button>(R.id.latchPm)

        val start = initialMinute ?: TimeMath.DEFAULT_MINUTE
        var hour = TimeMath.hour12(start)
        var minute = TimeMath.minuteOfHour(start)
        var pm = TimeMath.isPm(start)

        fun render() {
            tvHour.text = hour.toString()
            tvMinute.text = if (minute < 10) "0$minute" else minute.toString()
            latchAm.isSelected = !pm
            latchPm.isSelected = pm
            tvTime.text = EventWording.minute(TimeMath.minuteOfDay(hour, minute, pm))
        }

        // Every icon button names itself on a long press — words read better than glyphs on e-ink,
        // and a stepper arrow says nothing about *which* number it steps.
        fun stepper(id: Int, onStep: () -> Unit) {
            val button = view.findViewById<ImageButton>(id)
            TooltipCompat.setTooltipText(button, button.contentDescription)
            button.setOnClickListener { onStep(); render() }
        }
        stepper(R.id.btnHourMinus) { hour = TimeMath.stepHour(hour, -1) }
        stepper(R.id.btnHourPlus) { hour = TimeMath.stepHour(hour, 1) }
        stepper(R.id.btnMinuteMinus) { minute = TimeMath.stepMinute(minute, -1) }
        stepper(R.id.btnMinutePlus) { minute = TimeMath.stepMinute(minute, 1) }
        // Latches, not a toggle: the armed half is `isSelected`, which reads as a border on e-ink,
        // and tapping the half that is already armed is a no-op rather than a flip to the other.
        latchAm.setOnClickListener { pm = false; render() }
        latchPm.setOnClickListener { pm = true; render() }
        render()

        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setView(view)
                .setPositiveButton(R.string.ok) { _, _ -> onPicked(TimeMath.minuteOfDay(hour, minute, pm)) }
                .setNegativeButton(R.string.cancel, null)
                .create(),
        ).show()
    }
}
