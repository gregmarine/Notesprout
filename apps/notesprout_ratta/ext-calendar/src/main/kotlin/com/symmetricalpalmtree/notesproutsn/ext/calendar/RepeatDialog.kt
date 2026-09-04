package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatRadioButton
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates

/**
 * "How does it repeat?" — everything behind the editor's Repeat glance button (arc 24 / Z2, the
 * user's design), in [TimePickerDialog]'s shape: the extension's own, built on a layout, and every
 * rule it applies is [EventDraft]'s and JVM-tested. This file is views and listeners only.
 *
 * **Two steps, frequency first** (the user's call): the sheet asks *whether* and *how often*, and
 * only then does a dialog ask about the details of that particular frequency. **Never** answers the
 * whole question, so it saves immediately and there is no second step to cancel out of.
 *
 * **It applies on Save and discards on Cancel — including the frequency choice.** The working copy
 * is a plain [EventDraft] held in this dialog; every tap runs one of the draft's own pure functions
 * over it and re-renders from the answer, and only the positive button hands it back. So a person can
 * open the dialog, walk the whole rule, and leave the event exactly as they found it — which is what
 * makes every latch on it free to be tapped at.
 *
 * The whole screen is redrawn after every tap, deliberately: choosing a frequency moves three other
 * controls, and six views updated one at a time is how one of them gets missed.
 *
 * Both counts are [CountLatches] rows since arc 24 / Z5b — the presets 1–6 and a keypad past them,
 * over a sentence line that says what the number adds up to. The ± steppers are gone.
 */
object RepeatDialog {

    /**
     * Step 1 — the frequency sheet. [onSaved] is called with the draft to adopt, and is **not**
     * called at all when the person cancels either step.
     */
    fun show(activity: Activity, draft: EventDraft, onSaved: (EventDraft) -> Unit) {
        val sheet = ActionSheetDialog(activity).title(activity.getString(R.string.editor_repeats))
        // "Never" is a complete answer: no details to ask about, so it saves on the tap.
        sheet.addAction(null, activity.getString(R.string.editor_repeat_never)) { onSaved(draft.withFreq(null)) }
        for (freq in Freq.entries) {
            sheet.addAction(null, activity.getString(freqLabel(freq))) { details(activity, draft, freq, onSaved) }
        }
        sheet.show()
    }

    /** Step 2 — the details of [freq], over a working copy that starts at `draft.withFreq(freq)`. */
    private fun details(activity: Activity, draft: EventDraft, freq: Freq, onSaved: (EventDraft) -> Unit) {
        var working = draft.withFreq(freq)

        val view = activity.layoutInflater.inflate(R.layout.dialog_repeat, null)
        val tvEvery = view.findViewById<TextView>(R.id.tvEvery)
        val rowWeekdays = view.findViewById<LinearLayout>(R.id.rowWeekdays)
        val rowMonthly = view.findViewById<View>(R.id.rowMonthly)
        val radioDayOfMonth = view.findViewById<AppCompatRadioButton>(R.id.radioDayOfMonth)
        val radioOrdinal = view.findViewById<AppCompatRadioButton>(R.id.radioOrdinal)
        val btnUntilDate = view.findViewById<Button>(R.id.btnUntilDate)
        val countGroup = view.findViewById<View>(R.id.countGroup)
        val tvCount = view.findViewById<TextView>(R.id.tvCount)
        val latches = weekdayLatches(view)

        // The Ends row: one row of one-armed latches, `LatchGroup` the exclusivity.
        val ends = LatchGroup(listOf(EndMode.NEVER, EndMode.UNTIL, EndMode.COUNT))
        val endLatches: List<Pair<Button, EndMode>> = listOf(
            view.findViewById<Button>(R.id.latchEndsNever) to EndMode.NEVER,
            view.findViewById<Button>(R.id.latchEndsUntil) to EndMode.UNTIL,
            view.findViewById<Button>(R.id.latchEndsCount) to EndMode.COUNT,
        )

        // Declared before `render`, assigned after it: the two count rows call back into `edit`,
        // which calls `render`, which has to redraw them — the knot only unties one way round.
        lateinit var everyLatches: CountLatches
        lateinit var countLatches: CountLatches

        fun render() {
            val d = working
            // The sentence the glance button will read — one wording for how often it repeats.
            tvEvery.text = d.freq?.let { EventWording.repeatGlance(it, d.interval) }.orEmpty()
            everyLatches.render(d.interval)

            rowWeekdays.visibility = if (d.freq == Freq.WEEKLY) View.VISIBLE else View.GONE
            latches.forEach { (button, iso) -> button.isSelected = iso in d.weekdays }

            rowMonthly.visibility = if (d.freq == Freq.MONTHLY) View.VISIBLE else View.GONE
            if (d.freq == Freq.MONTHLY) renderMonthly(activity, d, radioDayOfMonth, radioOrdinal)

            // The latch background keys on `isSelected`, as the weekday row does.
            ends.pressed(d.endMode).forEachIndexed { i, down -> endLatches[i].first.isSelected = down }
            btnUntilDate.visibility = if (d.endMode == EndMode.UNTIL) View.VISIBLE else View.GONE
            btnUntilDate.text = EventWording.dateWithYear(d.untilDate ?: d.startDate)
            countGroup.visibility = if (d.endMode == EndMode.COUNT) View.VISIBLE else View.GONE
            tvCount.text = if (d.endCount == 1) {
                activity.getString(R.string.editor_times_one)
            } else {
                activity.getString(R.string.editor_times_n, d.endCount)
            }
            countLatches.render(d.endCount)
        }

        /** One rule applied, then the whole dialog redrawn from the answer. */
        fun edit(change: (EventDraft) -> EventDraft) {
            working = change(working)
            render()
        }

        // The two counts: six presets and a keypad each (arc 24 / Z5b). Both answer through the
        // draft's value-setters, so the clamp is the draft's — one rule, wherever the number arrives
        // from. The keypad's title is the word the number belongs to.
        everyLatches = CountLatches(
            view.findViewById(R.id.rowEvery), activity, EventRules.INTERVAL_RANGE, R.string.editor_every,
        ) { n -> edit { it.withIntervalValue(n) } }
        countLatches = CountLatches(
            view.findViewById(R.id.rowCount), activity, EventRules.END_COUNT_RANGE,
            R.string.editor_ends_after_short,
        ) { n -> edit { it.withCountValue(n) } }

        latches.forEach { (button, iso) -> button.setOnClickListener { edit { d -> d.toggleWeekday(iso) } } }
        radioDayOfMonth.setOnClickListener { edit { it.withMonthlyMode(MonthlyMode.DAY_OF_MONTH) } }
        radioOrdinal.setOnClickListener { edit { it.withMonthlyMode(MonthlyMode.ORDINAL_WEEKDAY) } }

        endLatches.forEach { (b, mode) -> b.setOnClickListener { edit { it.withEndMode(ends.resolve(it.endMode, mode)) } } }
        // The day picker opens over this dialog — its own window, so nothing here is torn down and
        // the working draft survives the trip.
        btnUntilDate.setOnClickListener {
            DayPickerDialog.show(activity, working.untilDate ?: working.startDate) { picked ->
                edit { it.withUntil(picked) }
            }
        }
        render()

        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(freqLabel(freq))
                .setView(view)
                .setPositiveButton(R.string.editor_save) { _, _ -> onSaved(working) }
                // Cancel discards the frequency choice too: nothing was handed back, so the event
                // keeps whatever repeat it had before the sheet was opened.
                .setNegativeButton(R.string.cancel, null)
                .create(),
        ).show()
    }

    /** Every latch, paired with the ISO weekday it stands for — Sun-first on the bar, ISO in the
     *  set, because `DayOfWeek.value` is what the engine reads and one convention is enough. */
    private fun weekdayLatches(view: View): List<Pair<Button, Int>> = listOf(
        view.findViewById<Button>(R.id.latchSun) to 7,
        view.findViewById<Button>(R.id.latchMon) to 1,
        view.findViewById<Button>(R.id.latchTue) to 2,
        view.findViewById<Button>(R.id.latchWed) to 3,
        view.findViewById<Button>(R.id.latchThu) to 4,
        view.findViewById<Button>(R.id.latchFri) to 5,
        view.findViewById<Button>(R.id.latchSat) to 6,
    )

    /** The two monthly choices, named for the date the series is anchored on: "On day 17" and
     *  "On the 3rd Tue" — or "the last Tue", which is what [EventDraft.ordinalOf] answers for the
     *  fifth slot, because that is what [Recurrence] will actually do with it. */
    private fun renderMonthly(
        activity: Activity,
        d: EventDraft,
        radioDayOfMonth: AppCompatRadioButton,
        radioOrdinal: AppCompatRadioButton,
    ) {
        radioDayOfMonth.text = activity.getString(R.string.editor_monthly_day, d.startDate.dayOfMonth)
        val (slot, isLast) = EventDraft.ordinalOf(d.startDate)
        val ordinal = activity.getString(
            if (isLast) R.string.ordinal_last else when (slot) {
                1 -> R.string.ordinal_1
                2 -> R.string.ordinal_2
                3 -> R.string.ordinal_3
                else -> R.string.ordinal_4
            },
        )
        val weekday = CalendarDates.DAY_NAMES[d.startDate.dayOfWeek.value % 7]
        radioOrdinal.text = activity.getString(R.string.editor_monthly_ordinal, ordinal, weekday)
        radioDayOfMonth.isChecked = d.monthlyMode == MonthlyMode.DAY_OF_MONTH
        radioOrdinal.isChecked = d.monthlyMode == MonthlyMode.ORDINAL_WEEKDAY
    }

    /** The frequency's own word — the sheet row, and the details dialog's title. */
    private fun freqLabel(freq: Freq): Int = when (freq) {
        Freq.DAILY -> R.string.editor_repeat_daily
        Freq.WEEKLY -> R.string.editor_repeat_weekly
        Freq.MONTHLY -> R.string.editor_repeat_monthly
        Freq.YEARLY -> R.string.editor_repeat_yearly
    }
}
