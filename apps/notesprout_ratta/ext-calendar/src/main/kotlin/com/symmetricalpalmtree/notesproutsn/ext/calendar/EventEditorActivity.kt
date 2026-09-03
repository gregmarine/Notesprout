package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
 * One event, on a whole screen (arc 24 / Z2, rebuilt to the user's design) — new or existing, fields
 * only; Z3 fills `noteArea`.
 *
 * **Not a point, and not exported.** Like [EventsActivity] it is launched only in this process, by
 * the events screen, so there is no caller check to make: nothing outside this APK can start it.
 *
 * **The shape is three rows over the note area.** The title with the type beside it; the dates, the
 * All day switch and the times; then two **glance** buttons. A glance button says the word alone when
 * the thing is unset ("Repeat", "Remind me") and the value concisely when it is set ("Every 2 weeks",
 * "1 week before"); the details live in [RepeatDialog] and [RemindDialog], which apply on their own
 * Save and discard on Cancel. So no stepper, latch bar, radio or chip is on this screen at all.
 *
 * **Delete is not here** — an event's one destructive verb is a per-row trash icon on the events
 * list ([EventsActivity]), which is where the person is already looking at the thing they mean. The
 * top bar is `title … [Cancel] [Save]`; Cancel and system Back are the same door.
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
     *  this, so Cancel only argues when something really moved. */
    private lateinit var initialDraft: EventDraft

    private var isNew = false
    private var viewedDay: LocalDate = LocalDate.now()
    private var loaded = false

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

        // The bar says which of the two things this screen is. No tooltips on it: every control up
        // there is a word, and a word is its own name.
        binding.title.setText(if (isNew) R.string.editor_title_new else R.string.editor_title_edit)
        binding.btnCancel.setOnClickListener { leave() }
        binding.btnSave.setOnClickListener { save() }
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
        // A switch reports its own state, so `render()` writing `isChecked` back would re-enter this
        // listener on every redraw. The compare is what stops that: a checked value that already
        // matches the draft is the render talking, not the person.
        binding.swAllDay.setOnCheckedChangeListener { _, checked ->
            if (!loaded || checked == draft.allDay) return@setOnCheckedChangeListener
            if (!ready()) { binding.swAllDay.isChecked = draft.allDay; return@setOnCheckedChangeListener }
            edit { it.withAllDay(checked) }
        }
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

        // The two glance buttons. Each dialog holds its own working copy and hands back a whole
        // draft, so the screen's job is only to adopt it — the same one road every other edit takes.
        binding.btnRepeat.setOnClickListener {
            if (!ready()) return@setOnClickListener
            RepeatDialog.show(this, draft) { saved -> edit { saved } }
        }
        binding.btnRemind.setOnClickListener {
            if (!ready()) return@setOnClickListener
            RemindDialog.show(this, draft) { saved -> edit { saved } }
        }
    }

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

    // ── Sheets ───────────────────────────────────────────────────────────────

    private fun typeSheet() {
        if (!ready()) return
        val sheet = ActionSheetDialog(this).title(getString(R.string.type_sheet_title))
        for (type in EventType.entries) {
            sheet.addAction(null, type.label) { edit { it.withType(type, isNew) } }
        }
        sheet.show()
    }

    /** og's three scopes, for an edit of a recurring event. (A delete asks the same question, on the
     *  events list, where the delete now lives.) */
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

        binding.swAllDay.isChecked = d.allDay
        // GONE, never disabled — a disabled control is invisible on e-ink.
        val times = if (d.allDay) View.GONE else View.VISIBLE
        binding.btnStartTime.visibility = times
        binding.btnEndTime.visibility = times
        // A stored event can be timed and yet hold no start minute; the badge's own em dash is
        // what says "no time set" everywhere else, so it says it here too.
        binding.btnStartTime.text = d.startMinute?.let(EventWording::minute) ?: NO_TIME
        binding.btnEndTime.text = d.endMinute?.let(EventWording::minute) ?: getString(R.string.editor_no_end_time)

        // The glances: the word alone when unset, the value concisely when set. What "set" means for
        // a reminder is that the event has one — the editor writes at most one (RemindDialog).
        binding.btnRepeat.text = d.freq?.let { EventWording.repeatGlance(it, d.interval) }
            ?: getString(R.string.editor_repeat)
        binding.btnRemind.text = d.reminders.firstOrNull()?.let(EventWording::reminderLabel)
            ?: getString(R.string.editor_remind)
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

    // ── Leaving ──────────────────────────────────────────────────────────────

    /** Cancel discards — but never silently. The argument is only had when something really moved,
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
    }
}
