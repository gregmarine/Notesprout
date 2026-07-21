package com.notesprout.android

import android.app.Activity
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import com.notesprout.android.data.EventsRepository
import com.notesprout.android.data.UpcomingEvent
import com.notesprout.android.data.events.EventPayload
import com.notesprout.android.data.events.EventRecurrence
import com.notesprout.android.data.events.EventType
import com.notesprout.android.data.index.EventEntity
import com.notesprout.android.databinding.ItemEventBinding
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * Drives the Day-Detail **Events** view: loads the day's attached events (direct + recurring
 * occurrences, sorted all-day-first then by time) and renders them as bordered rows with tap-to-edit
 * and a per-row delete. The Add button opens the [EventEditorDialog] for a new event.
 *
 * All storage is the plaintext [EventsRepository]; there is no encryption gate (calendar/events are
 * inherently plaintext-on-device, same as the scratch pad).
 */
class EventsController(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val listView: LinearLayout,
    private val emptyView: TextView,
    private val addButton: View,
    private val date: () -> LocalDate,
    private val repo: EventsRepository = EventsRepository(),
) {

    private val dateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val occFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val timeFmt = DateFormat.getTimeFormat(activity)

    init {
        addButton.setOnClickListener { openEditor(null) }
    }

    /** Reload and repaint the list for the current [date] — today's events + the Upcoming look-ahead. */
    fun refresh() {
        scope.launch {
            val d = date()
            val events = repo.eventsForDay(d)
            val upcoming = repo.upcomingForDay(d)
            render(events, upcoming)
        }
    }

    private fun render(events: List<EventEntity>, upcoming: List<UpcomingEvent>) {
        listView.removeAllViews()
        emptyView.isVisible = events.isEmpty() && upcoming.isEmpty()
        val inflater = LayoutInflater.from(activity)

        // Today — labelled only when an Upcoming section follows it, so a lone list stays calm.
        if (events.isNotEmpty() && upcoming.isNotEmpty()) addSectionHeader("Today", topGap = false)
        for (ev in events) addEventRow(inflater, ev)

        // Upcoming — reminders leading up to a future occurrence.
        if (upcoming.isNotEmpty()) {
            addSectionHeader("Upcoming", topGap = events.isNotEmpty())
            for (u in upcoming) addUpcomingRow(inflater, u)
        }
    }

    private fun addEventRow(inflater: LayoutInflater, ev: EventEntity) {
        val row = ItemEventBinding.inflate(inflater, listView, false)
        row.tvEventTime.text = if (ev.allDay) "All day" else ev.startMinute?.let(::fmtMin) ?: "—"
        row.tvEventTitle.text = ev.title
        row.tvEventMeta.text = meta(ev)
        row.eventRow.setOnClickListener { openEditor(ev) }
        row.btnEventDelete.setOnClickListener { confirmDelete(ev) }
        listView.addView(row.root)
    }

    /** An Upcoming row: leading countdown, title, and type · occurrence-date · time meta. Edit/delete
     *  are keyed to the *occurrence* day (not the viewed day) so recurring scopes resolve correctly. */
    private fun addUpcomingRow(inflater: LayoutInflater, u: UpcomingEvent) {
        val ev = u.event
        val occDay = u.occurrenceStart.toEpochDay()
        val row = ItemEventBinding.inflate(inflater, listView, false)
        row.tvEventTime.text = countdown(u.daysUntil)
        row.tvEventTitle.text = ev.title
        row.tvEventMeta.text = upcomingMeta(u)
        row.eventRow.setOnClickListener { openEditor(ev, contextDay = occDay) }
        row.btnEventDelete.setOnClickListener { confirmDelete(ev, viewedDay = occDay) }
        listView.addView(row.root)
    }

    /** A bold black section label between the two lists. */
    private fun addSectionHeader(text: String, topGap: Boolean) {
        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(activity, R.color.inkBlack))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(6)
                if (topGap) topMargin = dp(6)
            }
        }
        listView.addView(tv)
    }

    private fun countdown(days: Int): String = if (days == 1) "Tomorrow" else "In $days days"

    private fun upcomingMeta(u: UpcomingEvent): String {
        val parts = mutableListOf(EventType.fromName(u.event.type).label)
        parts += u.occurrenceStart.format(occFmt)
        if (!u.event.allDay) u.event.startMinute?.let { parts += fmtMin(it) }
        return parts.joinToString(" · ")
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun meta(ev: EventEntity): String {
        val parts = mutableListOf(EventType.fromName(ev.type).label)
        if (!ev.allDay && ev.endMinute != null) parts += "ends ${fmtMin(ev.endMinute)}"
        if (ev.startEpochDay != ev.endEpochDay) {
            val s = LocalDate.ofEpochDay(ev.startEpochDay).format(dateFmt)
            val e = LocalDate.ofEpochDay(ev.endEpochDay).format(dateFmt)
            parts += "$s – $e"
        }
        EventPayload.fromJson(ev.data).recurrence?.let { parts += it.summary() }
        return parts.joinToString(" · ")
    }

    /** [contextDay] is the occurrence day the edit/delete scopes key off — the viewed day for a
     *  today row, the occurrence day for an Upcoming row (so recurring scopes resolve correctly). */
    private fun openEditor(existing: EventEntity?, contextDay: Long = date().toEpochDay()) {
        // For a recurring event, anchor the editor's dates on the tapped occurrence (not the series'
        // parent anchor) so an untouched date stays put and a changed date moves that occurrence.
        val occurrenceStart = existing?.takeIf { it.recurring }?.let { ev ->
            EventPayload.fromJson(ev.data).recurrence?.let { rule ->
                EventRecurrence.occurrenceStartCovering(rule, ev.startEpochDay, ev.endEpochDay, contextDay)
            }
        }
        EventEditorDialog.show(
            activity = activity,
            date = LocalDate.ofEpochDay(contextDay),
            existing = existing,
            occurrenceStart = occurrenceStart,
            onSaved = { entity ->
                if (existing != null && existing.recurring) {
                    promptEditScope(original = existing, edited = entity, viewedDay = contextDay)
                } else {
                    scope.launch { repo.save(entity); refresh() }
                }
            },
            onDeleted = { entity -> confirmDelete(entity, viewedDay = contextDay) },
        )
    }

    private fun promptEditScope(original: EventEntity, edited: EventEntity, viewedDay: Long) {
        val options = arrayOf("This event only", "This and following events", "All events in the series")
        styleAndShow(
            AlertDialog.Builder(activity)
                .setTitle("Edit repeating event")
                .setItems(options) { _, which ->
                    scope.launch {
                        when (which) {
                            0 -> repo.editOccurrence(original, edited, viewedDay)
                            1 -> repo.editThisAndFollowing(original, edited, viewedDay)
                            else -> repo.editSeries(edited, original, viewedDay)
                        }
                        refresh()
                    }
                }
                .setNegativeButton("Cancel", null)
                .create(),
        )
    }

    private fun confirmDelete(ev: EventEntity, viewedDay: Long = date().toEpochDay()) {
        if (!ev.recurring) {
            styleAndShow(
                AlertDialog.Builder(activity)
                    .setTitle("Delete event")
                    .setMessage("Delete “${ev.title}”?")
                    .setPositiveButton("Delete") { _, _ -> scope.launch { repo.delete(ev.id); refresh() } }
                    .setNegativeButton("Cancel", null)
                    .create(),
            )
            return
        }
        // Recurring: offer occurrence-scoped deletes.
        val options = arrayOf("This event only", "This and following events", "All events in the series")
        styleAndShow(
            AlertDialog.Builder(activity)
                .setTitle("Delete repeating event")
                .setItems(options) { _, which ->
                    scope.launch {
                        when (which) {
                            0 -> repo.deleteOccurrence(ev, viewedDay)
                            1 -> repo.deleteThisAndFollowing(ev, viewedDay)
                            else -> repo.delete(ev.id)
                        }
                        refresh()
                    }
                }
                .setNegativeButton("Cancel", null)
                .create(),
        )
    }

    private fun styleAndShow(dialog: AlertDialog) {
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    private fun fmtMin(m: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60)
        }
        return timeFmt.format(cal.time)
    }
}
