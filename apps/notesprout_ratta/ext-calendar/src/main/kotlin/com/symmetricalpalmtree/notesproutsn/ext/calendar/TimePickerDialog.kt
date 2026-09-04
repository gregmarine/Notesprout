package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Activity
import android.widget.Button
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs

/**
 * "What time?" — the editor's start/end time dialog (arc 24 / Z2; the face is Z5b), in
 * [DayPickerDialog]'s shape: the extension's own, built on the layout, and every rule it applies is
 * [TimeMath]'s or [ClockFaceModel]'s and JVM-tested. This file is views and listeners only.
 *
 * **A clock, and two taps.** Pick the hour on the dial and the face turns to minutes by itself; pick
 * the minute and you are done. That one automatic step is the whole reason it is two taps rather
 * than three, and the hour/minute latches above the dial are the way back from it — tapping either
 * shows that face again, so a wrong hour is one tap from being right.
 *
 * The minute face is on [TimeMath.MINUTE_STEP]'s grain, which is the same call the steppers this
 * replaced made: five minutes is what a calendar entry is worth, and a sixty-position dial is one
 * nobody lands on with a pen.
 *
 * OK and Cancel are the AlertDialog's own buttons: Cancel writes nothing at all, which is what makes
 * the whole face free to be tapped at.
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
        val tvHour = view.findViewById<Button>(R.id.tvHour)
        val tvMinute = view.findViewById<Button>(R.id.tvMinute)
        val clock = view.findViewById<ClockFaceView>(R.id.clockFace)
        val latchAm = view.findViewById<Button>(R.id.latchAm)
        val latchPm = view.findViewById<Button>(R.id.latchPm)

        val start = initialMinute ?: TimeMath.DEFAULT_MINUTE
        var hour = TimeMath.hour12(start)
        var minute = TimeMath.minuteOfHour(start)
        var pm = TimeMath.isPm(start)
        var face = ClockFaceModel.Face.HOURS

        fun render() {
            tvHour.text = ClockFaceModel.label(ClockFaceModel.Face.HOURS, hour)
            tvMinute.text = ClockFaceModel.label(ClockFaceModel.Face.MINUTES, minute)
            // The latch that is down is the face that is showing — one state, read two ways.
            tvHour.isSelected = face == ClockFaceModel.Face.HOURS
            tvMinute.isSelected = face == ClockFaceModel.Face.MINUTES
            clock.face = face
            clock.hour = hour
            clock.minute = minute
            latchAm.isSelected = !pm
            latchPm.isSelected = pm
        }

        clock.onPicked = { value ->
            if (face == ClockFaceModel.Face.HOURS) {
                hour = value
                // The one automatic step. Nothing moves the face back on its own — that is the
                // latches' job, so a person is never carried away from a face they are still using.
                face = ClockFaceModel.Face.MINUTES
            } else {
                minute = value
            }
            render()
        }
        tvHour.setOnClickListener { face = ClockFaceModel.Face.HOURS; render() }
        tvMinute.setOnClickListener { face = ClockFaceModel.Face.MINUTES; render() }
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
