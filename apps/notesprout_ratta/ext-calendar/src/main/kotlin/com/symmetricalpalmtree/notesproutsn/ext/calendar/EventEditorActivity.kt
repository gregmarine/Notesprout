package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.calendar.databinding.ActivityEventEditorBinding
import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * One event, on a whole screen (arc 24 / Z2) — new or existing, fields only; Z3 fills `noteArea`.
 *
 * **Not a point, and not exported.** Like [EventsActivity] it is launched only in this process, by
 * the events screen, so there is no caller check to make: nothing outside this APK can start it.
 *
 * **The state is an [EventDraft], not an [Event].** Every field rule — the end date following the
 * start, all-day clearing the times, weekly seeding its weekday, a type offering its usual repeat
 * — is a pure function over that draft and is JVM-tested. This class turns taps into those calls and
 * the draft into views, and does nothing else with the rules.
 *
 * ## The two things that are subtle
 *
 * **The recurring prefill, and the anchor behind it.** Opening an occurrence of a series shows *that
 * occurrence's* dates, so "this occurrence" means the one that was tapped. But the series' real
 * anchor is older, and saving the prefill back unchanged must not silently re-anchor it — a birthday
 * would forget the year it started. [EventWrites.editSeries] implements that: it compares the edited
 * dates against `occurrenceStartCovering(original, viewedDay)` and `+ spanDays`, and keeps the
 * stored anchor when they come back untouched. So this screen must prefill with **exactly** those
 * two dates and hand [EventStore.edit] the **stored** original — which is why [original] is kept
 * alongside the draft rather than being reconstructed from it.
 *
 * **What Save calls.** A brand-new event is [EventStore.save] with `isNew = true` (its compensation
 * for a half-landed multi-batch write is to delete the row, which is only right for a row this save
 * minted). Everything else is [EventStore.edit]: at the chosen scope for a recurring original, and
 * at [Scope.ALL] for a one-off — which [EventWrites.editWithScope] routes to the same in-place
 * `editSeries` a whole-series edit takes.
 *
 * IME rules (Ratta): the keyboard is asked for with the **explicit** flag 0 from
 * [onWindowFocusChanged] behind a once-per-showing latch, and only for a NEW event — an existing one
 * is opened to be read before it is changed. It is **never hidden**: on Supernote hiding the IME
 * kills hardware key delivery too.
 */
class EventEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventEditorBinding

    /** The event as the store holds it — null for a new one. The scope ops are computed against
     *  **this**, never against the draft: the person tapped an occurrence of the series as it *is*. */
    private var original: Event? = null

    private lateinit var draft: EventDraft

    /** The draft as it was after loading (prefill included). [EventDraft.changedFrom] is asked of
     *  this, so Back only argues when something really moved. */
    private lateinit var initialDraft: EventDraft

    private var isNew = false
    private var viewedDay: LocalDate = LocalDate.now()
    private var loaded = false

    /** The reminder stepper's own state — an amount and a unit waiting to be added. It is not part
     *  of the draft: nothing is a reminder until ⊕ says so. */
    private var remindAmount = 1
    private var remindUnit = ReminderUnit.DAYS

    /** One write at a time; a second tap on Save is taken as read rather than queued. */
    private var busy = false

    /** A NEW event owes this showing one keyboard, raised at the first window focus. */
    private var pendingTitleFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // followIme: the fields must stay visible with the keyboard up — the layout resizes, the
        // keyboard is never hidden.
        TopGuard.applyInsetPadding(binding.root, followIme = true)

        viewedDay = CalendarDates.parse(intent.getStringExtra(EXTRA_DAY).orEmpty()) ?: LocalDate.now()
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        isNew = eventId == null

        listOf(binding.btnBack, binding.btnDelete, binding.btnSave, binding.btnEveryMinus,
            binding.btnEveryPlus, binding.btnCountMinus, binding.btnCountPlus,
            binding.btnRemindMinus, binding.btnRemindPlus, binding.btnRemindAdd).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        binding.btnBack.setOnClickListener { leave() }
        binding.btnBack.setOnLongClickListener { hint(R.string.cd_editor_back) }
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.setOnLongClickListener { hint(R.string.cd_editor_save) }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnDelete.setOnLongClickListener { hint(R.string.cd_editor_delete) }
        binding.btnDelete.visibility = if (isNew) View.GONE else View.VISIBLE
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        wireFields()

        if (eventId == null) {
            draft = EventDraft.blank(CalendarStore.newId(), viewedDay, System.currentTimeMillis())
            initialDraft = draft
            loaded = true
            pendingTitleFocus = true
            binding.inputTitle.setText(draft.title)
            render()
        } else {
            load(eventId)
        }
    }

    /**
     * A NEW event opens with the title ready to type into.
     *
     * **From `onWindowFocusChanged`, not `onResume`** (proven on the Nomad at arc 21 / W2): a
     * resumed Activity does not yet have window focus, and `showSoftInput` against an unfocused
     * window is dropped — the field ends up served and caret-ready with `mInputShown=false`, which
     * reads as "the keyboard is broken". The latch makes it once per showing, so coming back from a
     * picker does not re-raise a keyboard the person just put away.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !pendingTitleFocus) return
        pendingTitleFocus = false
        binding.inputTitle.requestFocus()
        binding.inputTitle.setSelection(binding.inputTitle.text?.length ?: 0)
        // Flag 0, not SHOW_IMPLICIT: an implicit show is skipped with a hardware keyboard attached,
        // and on Ratta hardware keys are delivered only while the IME is up — so an implicit show
        // would strand the field with no way to type into it.
        getSystemService(InputMethodManager::class.java)?.showSoftInput(binding.inputTitle, 0)
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    private fun load(id: String) {
        lifecycleScope.launch {
            val e = withContext(Dispatchers.IO) { readEvent(id) }
            if (isFinishing || isDestroyed) return@launch
            if (e == null) { failAndClose(); return@launch }
            original = e
            // The prefill: the occurrence being looked at, not the series anchor. A direct `copy`,
            // deliberately — this is not a user edit, so none of the field rules (the end following
            // the start, and so on) may run over it, and the two dates must land EXACTLY where
            // EventWrites.editSeries looks for them or the anchor-preservation test there fails.
            val covering = if (e.recurring) Recurrence.occurrenceStartCovering(e, viewedDay) else null
            draft = EventDraft.from(e).let {
                if (covering == null) it else it.copy(startDate = covering, endDate = covering.plusDays(e.spanDays))
            }
            initialDraft = draft
            loaded = true
            binding.inputTitle.setText(draft.title)
            binding.inputTitle.setSelection(binding.inputTitle.text?.length ?: 0)
            Slog.d(TAG) { "loaded ${e.id}: recurring=${e.recurring}, ${e.reminders.size} reminder(s)" }
            render()
        }
    }

    /** Blocking, IO only. */
    private fun readEvent(id: String): Event? {
        val binder = CalendarSession.store ?: return null
        return try {
            EventStore(binder).get(id)
        } catch (e: StoreUnavailable) {
            Slog.d(TAG) { "store unavailable: ${e.javaClass.simpleName}" }
            null
        } catch (e: Exception) {
            Slog.d(TAG) { "read failed: ${e.javaClass.simpleName}" }
            null
        }
    }

    /** Nothing to edit and nothing to fix: explain, then leave on the dialog's **dismiss**. */
    private fun failAndClose() {
        Dialogs.confirm(this, R.string.editor_problem_title, R.string.events_unavailable_body) { finish() }
    }

    // ── Wiring ───────────────────────────────────────────────────────────────

    private fun wireFields() {
        binding.btnType.setOnClickListener { typeSheet() }
        binding.inputTitle.doAfterTextChanged {
            // The field is the source of truth for the title; render() never writes it back, or
            // every keystroke would fight the caret.
            if (loaded) draft = draft.withTitle(it?.toString().orEmpty())
        }
        // Every listener that reads the draft to seed a picker goes through `ready()` first: the
        // draft is `lateinit` until the load answers, and a tap on a button that is already on the
        // glass must never be the thing that throws.
        binding.btnStartDate.setOnClickListener {
            if (!ready()) return@setOnClickListener
            DayPickerDialog.show(this, draft.startDate) { picked -> edit { it.withStartDate(picked) } }
        }
        binding.btnEndDate.setOnClickListener {
            if (!ready()) return@setOnClickListener
            DayPickerDialog.show(this, draft.endDate) { picked -> edit { it.withEndDate(picked) } }
        }
        binding.latchAllDay.setOnClickListener { edit { it.withAllDay(!it.allDay) } }
        binding.btnStartTime.setOnClickListener {
            if (!ready()) return@setOnClickListener
            TimePickerDialog.show(this, R.string.time_title, draft.startMinute) { m -> edit { it.withStartTime(m) } }
        }
        binding.btnEndTime.setOnClickListener {
            if (!ready()) return@setOnClickListener
            TimePickerDialog.show(this, R.string.time_title, draft.endMinute ?: draft.startMinute) { m ->
                edit { it.withEndTime(m) }
            }
        }
        // Long-press clears the end time — "no end time" is a real answer and needs a way back to it.
        binding.btnEndTime.setOnLongClickListener { edit { it.withEndTime(null) }; true }

        binding.btnRepeats.setOnClickListener { repeatSheet() }
        binding.btnEveryMinus.setOnClickListener { edit { it.withInterval(-1) } }
        binding.btnEveryPlus.setOnClickListener { edit { it.withInterval(1) } }
        weekdayLatches().forEach { (view, iso) -> view.setOnClickListener { edit { d -> d.toggleWeekday(iso) } } }
        binding.radioDayOfMonth.setOnClickListener { edit { it.withMonthlyMode(MonthlyMode.DAY_OF_MONTH) } }
        binding.radioOrdinal.setOnClickListener { edit { it.withMonthlyMode(MonthlyMode.ORDINAL_WEEKDAY) } }

        binding.btnEnds.setOnClickListener { endsSheet() }
        binding.btnUntilDate.setOnClickListener {
            if (!ready()) return@setOnClickListener
            DayPickerDialog.show(this, draft.untilDate ?: draft.startDate) { d -> edit { it.withUntil(d) } }
        }
        binding.btnCountMinus.setOnClickListener { edit { it.withCount(-1) } }
        binding.btnCountPlus.setOnClickListener { edit { it.withCount(1) } }

        binding.btnRemindMinus.setOnClickListener { stepRemind(-1) }
        binding.btnRemindPlus.setOnClickListener { stepRemind(1) }
        binding.latchRemindDays.setOnClickListener { remindUnit = ReminderUnit.DAYS; render() }
        binding.latchRemindWeeks.setOnClickListener { remindUnit = ReminderUnit.WEEKS; render() }
        binding.btnRemindAdd.setOnClickListener { addReminder() }
    }

    /** Every latch, paired with the ISO weekday it stands for — Sun-first on the bar, ISO in the
     *  set, because `DayOfWeek.value` is what the engine reads and one convention is enough. */
    private fun weekdayLatches(): List<Pair<View, Int>> = listOf(
        binding.latchSun to 7, binding.latchMon to 1, binding.latchTue to 2, binding.latchWed to 3,
        binding.latchThu to 4, binding.latchFri to 5, binding.latchSat to 6,
    )

    /** Whether the draft exists and nothing is being written — the one gate every control that
     *  reads or changes it passes through. */
    private fun ready(): Boolean = loaded && !busy

    /** One field changed: the pure rule, then the whole screen redrawn from the answer. Redrawing
     *  everything is deliberate — a field rule can move three other controls, and six views updated
     *  one at a time is how one of them gets missed. */
    private fun edit(change: (EventDraft) -> EventDraft) {
        if (!ready()) return
        draft = change(draft)
        render()
    }

    private fun stepRemind(delta: Int) {
        remindAmount = (remindAmount + delta).coerceIn(REMIND_RANGE)
        render()
    }

    private fun addReminder() {
        if (!ready()) return
        val duplicate = draft.reminders.any { it.amount == remindAmount && it.unit == remindUnit }
        val next = draft.addReminder(remindAmount, remindUnit)
        if (next == null) {
            // A tap that did nothing is a dialog, never a toast: on e-ink a missed toast reads as
            // "the button is broken". Two different refusals, two different sentences — telling
            // someone the list is full when the reminder is simply already on it is a wrong answer.
            Dialogs.problem(
                this,
                R.string.editor_problem_title,
                if (duplicate) R.string.editor_reminder_duplicate else R.string.editor_reminders_full,
            )
            return
        }
        draft = next
        render()
    }

    // ── Sheets ───────────────────────────────────────────────────────────────

    private fun typeSheet() {
        if (!ready()) return
        val sheet = ActionSheetDialog(this).title(getString(R.string.type_sheet_title))
        for (type in EventType.entries) {
            sheet.addAction(null, type.label) { edit { it.withType(type, isNew) } }
        }
        sheet.show()
    }

    private fun repeatSheet() {
        if (!ready()) return
        val sheet = ActionSheetDialog(this).title(getString(R.string.editor_repeats))
        sheet.addAction(null, getString(R.string.editor_repeat_never)) { edit { it.withFreq(null) } }
        for (freq in Freq.entries) {
            sheet.addAction(null, getString(freqLabel(freq))) { edit { it.withFreq(freq) } }
        }
        sheet.show()
    }

    private fun endsSheet() {
        if (!ready()) return
        ActionSheetDialog(this)
            .title(getString(R.string.editor_ends))
            .addAction(null, getString(R.string.editor_ends_never)) { edit { it.withEndMode(EndMode.NEVER) } }
            .addAction(null, getString(R.string.editor_ends_on_date)) { edit { it.withEndMode(EndMode.UNTIL) } }
            .addAction(null, getString(R.string.editor_ends_after)) { edit { it.withEndMode(EndMode.COUNT) } }
            .show()
    }

    /** og's three scopes, for an edit or a delete of a recurring event. */
    private fun scopeSheet(titleRes: Int, onPicked: (Scope) -> Unit) {
        ActionSheetDialog(this)
            .title(getString(titleRes))
            .addAction(null, getString(R.string.scope_this)) { onPicked(Scope.THIS) }
            .addAction(null, getString(R.string.scope_following)) { onPicked(Scope.FOLLOWING) }
            .addAction(null, getString(R.string.scope_all)) { onPicked(Scope.ALL) }
            .show()
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /** The whole screen from the draft. `inputTitle` is the one thing never written here — the
     *  field owns the title while it is being typed into. */
    private fun render() {
        if (!loaded) return
        val d = draft

        binding.btnType.text = d.type.label
        binding.btnStartDate.text = EventWording.dateWithYear(d.startDate)
        binding.btnEndDate.text = EventWording.dateWithYear(d.endDate)

        binding.latchAllDay.isSelected = d.allDay
        // GONE, never disabled — a disabled control is invisible on e-ink. The time sits beside its
        // date; the rows stay, so the screen does not reflow on one tap.
        val times = if (d.allDay) View.GONE else View.VISIBLE
        binding.btnStartTime.visibility = times
        binding.btnEndTime.visibility = times
        // A stored event can be timed and yet hold no start minute; the badge's own em dash is
        // what says "no time set" everywhere else, so it says it here too.
        binding.btnStartTime.text = d.startMinute?.let(EventWording::minute) ?: NO_TIME
        binding.btnEndTime.text = d.endMinute?.let(EventWording::minute) ?: getString(R.string.editor_no_end_time)

        binding.btnRepeats.text = getString(d.freq?.let(::freqLabel) ?: R.string.editor_repeat_never)
        binding.everyGroup.visibility = if (d.freq != null) View.VISIBLE else View.GONE
        binding.tvEvery.text = d.interval.toString()
        d.freq?.let { binding.tvEveryUnit.setText(unitLabel(it, d.interval)) }

        // Nothing conditional carries a divider of its own (the first walk's stacked-divider
        // lesson): the three group hairlines are the only lines on the screen.
        binding.rowWeekdays.visibility = if (d.freq == Freq.WEEKLY) View.VISIBLE else View.GONE
        weekdayLatches().forEach { (view, iso) -> view.isSelected = iso in d.weekdays }

        binding.rowMonthly.visibility = if (d.freq == Freq.MONTHLY) View.VISIBLE else View.GONE
        if (d.freq == Freq.MONTHLY) renderMonthly(d)

        binding.rowEnds.visibility = if (d.freq != null) View.VISIBLE else View.GONE
        binding.btnEnds.text = getString(
            when (d.endMode) {
                EndMode.NEVER -> R.string.editor_ends_never
                EndMode.UNTIL -> R.string.editor_ends_on_date
                EndMode.COUNT -> R.string.editor_ends_after_short
            },
        )
        binding.btnUntilDate.visibility = if (d.endMode == EndMode.UNTIL) View.VISIBLE else View.GONE
        binding.btnUntilDate.text = EventWording.dateWithYear(d.untilDate ?: d.startDate)
        binding.countGroup.visibility = if (d.endMode == EndMode.COUNT) View.VISIBLE else View.GONE
        binding.tvCount.text = d.endCount.toString()

        binding.tvRemindAmount.text = remindAmount.toString()
        binding.latchRemindDays.isSelected = remindUnit == ReminderUnit.DAYS
        binding.latchRemindWeeks.isSelected = remindUnit == ReminderUnit.WEEKS
        renderReminders(d)
    }

    /** The two monthly choices, named for the date the series is anchored on: "On day 17" and
     *  "On the 3rd Tue" — or "the last Tue", which is what [EventDraft.ordinalOf] answers for the
     *  fifth slot, because that is what [Recurrence] will actually do with it. */
    private fun renderMonthly(d: EventDraft) {
        binding.radioDayOfMonth.text = getString(R.string.editor_monthly_day, d.startDate.dayOfMonth)
        val (slot, isLast) = EventDraft.ordinalOf(d.startDate)
        val ordinal = getString(
            if (isLast) R.string.ordinal_last else when (slot) {
                1 -> R.string.ordinal_1
                2 -> R.string.ordinal_2
                3 -> R.string.ordinal_3
                else -> R.string.ordinal_4
            },
        )
        val weekday = CalendarDates.DAY_NAMES[d.startDate.dayOfWeek.value % 7]
        binding.radioOrdinal.text = getString(R.string.editor_monthly_ordinal, ordinal, weekday)
        binding.radioDayOfMonth.isChecked = d.monthlyMode == MonthlyMode.DAY_OF_MONTH
        binding.radioOrdinal.isChecked = d.monthlyMode == MonthlyMode.ORDINAL_WEEKDAY
    }

    /** The reminder chips, built in code — the row *count* is the content. GONE when there are
     *  none, so the row gives its space back rather than sitting empty. */
    private fun renderReminders(d: EventDraft) {
        binding.remindersRow.removeAllViews()
        binding.remindersRow.visibility = if (d.reminders.isEmpty()) View.GONE else View.VISIBLE
        val density = resources.displayMetrics.density
        val ink = ContextCompat.getColor(this, R.color.inkBlack)
        for (r in d.reminders) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.shape_bordered)
                setPadding((10 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                isClickable = true
                setOnClickListener { edit { it.removeReminder(r) } }
            }
            chip.addView(
                AppCompatTextView(this).apply {
                    text = EventWording.reminderLabel(r)
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ink)
                },
            )
            chip.addView(
                AppCompatImageView(this).apply {
                    setImageResource(R.drawable.ic_x)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = getString(R.string.cd_editor_remind_remove)
                    background = ColorDrawable(Color.TRANSPARENT)
                },
                LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()).also {
                    it.marginStart = (6 * density).toInt()
                },
            )
            binding.remindersRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.marginEnd = (8 * density).toInt() },
            )
        }
    }

    private fun freqLabel(freq: Freq): Int = when (freq) {
        Freq.DAILY -> R.string.editor_repeat_daily
        Freq.WEEKLY -> R.string.editor_repeat_weekly
        Freq.MONTHLY -> R.string.editor_repeat_monthly
        Freq.YEARLY -> R.string.editor_repeat_yearly
    }

    /** The interval's unit word, singular at 1 — "Every 1 days" is not a sentence. */
    private fun unitLabel(freq: Freq, interval: Int): Int = when (freq) {
        Freq.DAILY -> if (interval == 1) R.string.editor_unit_day else R.string.editor_unit_days
        Freq.WEEKLY -> if (interval == 1) R.string.editor_unit_week else R.string.editor_unit_weeks
        Freq.MONTHLY -> if (interval == 1) R.string.editor_unit_month else R.string.editor_unit_months
        Freq.YEARLY -> if (interval == 1) R.string.editor_unit_year else R.string.editor_unit_years
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private fun save() {
        if (!ready()) return
        when (draft.problem()) {
            EventRules.Problem.EMPTY_TITLE -> {
                Dialogs.problem(this, R.string.editor_problem_title, R.string.editor_problem_empty_title)
                return
            }
            EventRules.Problem.UNTIL_BEFORE_START -> {
                Dialogs.problem(this, R.string.editor_problem_title, R.string.editor_problem_until)
                return
            }
            null -> Unit
        }
        val existing = original
        // A recurring original is the only case with a question to ask; everything else is one road.
        if (existing?.recurrence != null) {
            scopeSheet(R.string.scope_title_edit) { scope -> write(scope) }
            return
        }
        write(Scope.ALL)
    }

    /**
     * The write itself, at [scope].
     *
     * A brand-new event takes [EventStore.save] with `isNew = true`; anything else takes
     * [EventStore.edit], which for a one-off original at [Scope.ALL] is the same in-place
     * `editSeries` a whole-series edit takes — and which is what preserves the series anchor when
     * the prefilled dates come back unchanged (see the class note).
     */
    private fun write(scope: Scope) {
        if (busy) return
        busy = true
        val edited = draft.toEvent(System.currentTimeMillis())
        val existing = original
        val day = viewedDay
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val binder = CalendarSession.store ?: return@withContext false
                try {
                    val store = EventStore(binder)
                    if (existing == null) {
                        store.save(edited, isNew = true)
                        true
                    } else {
                        // Null means the viewed day maps to no occurrence any more — another writer
                        // moved the series under us. Nothing was written, and saying so is better
                        // than a silent no-op.
                        store.edit(scope, existing, edited, day) != null
                    }
                } catch (e: StoreUnavailable) {
                    Slog.d(TAG) { "save failed: store unavailable" }
                    false
                } catch (e: Exception) {
                    Slog.d(TAG) { "save failed: ${e.javaClass.simpleName}" }
                    false
                }
            }
            busy = false
            if (isFinishing || isDestroyed) return@launch
            if (!ok) {
                Dialogs.problem(this@EventEditorActivity, R.string.editor_problem_title, R.string.editor_save_failed)
                return@launch
            }
            Slog.d(TAG) { "saved ${edited.id} at $scope" }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    private fun confirmDelete() {
        if (!ready()) return
        val existing = original ?: return
        if (existing.recurrence != null) {
            scopeSheet(R.string.scope_title_delete) { scope -> confirmDelete(existing, scope) }
            return
        }
        confirmDelete(existing, Scope.ALL)
    }

    /** The confirm names what is about to go — "every occurrence" only when that is what ALL on a
     *  recurring event means, because a delete of one occurrence and a delete of a series are not
     *  the same act and must not read the same. */
    private fun confirmDelete(event: Event, scope: Scope) {
        val wholeSeries = event.recurrence != null && scope == Scope.ALL
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.editor_delete_title, event.title))
                .setMessage(
                    if (wholeSeries) getString(R.string.editor_delete_body_series, event.title)
                    else getString(R.string.editor_delete_body_once),
                )
                .setPositiveButton(R.string.editor_delete_confirm) { _, _ -> delete(event, scope) }
                .setNegativeButton(R.string.cancel, null)
                .create(),
        ).show()
    }

    private fun delete(event: Event, scope: Scope) {
        if (busy) return
        busy = true
        val day = viewedDay
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val binder = CalendarSession.store ?: return@withContext false
                try {
                    // False means the viewed day maps to no occurrence: nothing to do, and never a
                    // whole-series delete by accident.
                    EventStore(binder).delete(scope, event, day)
                } catch (e: StoreUnavailable) {
                    Slog.d(TAG) { "delete failed: store unavailable" }
                    false
                } catch (e: Exception) {
                    Slog.d(TAG) { "delete failed: ${e.javaClass.simpleName}" }
                    false
                }
            }
            busy = false
            if (isFinishing || isDestroyed) return@launch
            if (!ok) {
                Dialogs.problem(this@EventEditorActivity, R.string.editor_problem_title, R.string.editor_delete_failed)
                return@launch
            }
            Slog.d(TAG) { "deleted ${event.id} at $scope" }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    // ── Leaving ──────────────────────────────────────────────────────────────

    /** Back discards — but never silently. The argument is only had when something really moved,
     *  and it is a two-button dialog because "Discard" is the destructive half and must be named. */
    private fun leave() {
        if (busy) return
        if (!loaded || !draft.changedFrom(initialDraft)) { finish(); return }
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.editor_discard_title)
                .setMessage(R.string.editor_discard_body)
                .setPositiveButton(R.string.editor_discard_confirm) { _, _ -> finish() }
                .setNegativeButton(R.string.editor_keep_editing, null)
                .create(),
        ).show()
    }

    private fun hint(res: Int): Boolean {
        Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
        return true
    }

    companion object {
        private const val TAG = "EventEditor"

        /** The event to open, or absent/null for a new one. */
        const val EXTRA_EVENT_ID = "com.symmetricalpalmtree.notesproutsn.ext.calendar.EVENT_ID"

        /** ISO `yyyy-MM-dd` — the day the event is being looked at from: the prefill for a new
         *  event's dates, and the `viewedDay` of every scope op. */
        const val EXTRA_DAY = "com.symmetricalpalmtree.notesproutsn.ext.calendar.DAY"

        /** What a time button reads when the field holds no minute — [EventWording.timeBadge]'s
         *  own answer, so the two surfaces say the same thing. */
        private const val NO_TIME = "—"

        /** The reminder stepper's range. Three reminders is the cap; ninety-nine days of lead is
         *  already more than a paper calendar gives you. */
        private val REMIND_RANGE = 1..99
    }
}
