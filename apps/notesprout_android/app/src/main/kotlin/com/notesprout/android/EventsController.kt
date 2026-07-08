package com.notesprout.android

import android.app.Activity
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import com.notesprout.android.data.EventsRepository
import com.notesprout.android.data.events.EventPayload
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
    private val timeFmt = DateFormat.getTimeFormat(activity)

    init {
        addButton.setOnClickListener { openEditor(null) }
    }

    /** Reload and repaint the list for the current [date]. */
    fun refresh() {
        scope.launch {
            val events = repo.eventsForDay(date())
            render(events)
        }
    }

    private fun render(events: List<EventEntity>) {
        listView.removeAllViews()
        emptyView.isVisible = events.isEmpty()
        val inflater = LayoutInflater.from(activity)
        for (ev in events) {
            val row = ItemEventBinding.inflate(inflater, listView, false)
            row.tvEventTime.text = if (ev.allDay) "All day" else ev.startMinute?.let(::fmtMin) ?: "—"
            row.tvEventTitle.text = ev.title
            row.tvEventMeta.text = meta(ev)
            row.eventRow.setOnClickListener { openEditor(ev) }
            row.btnEventDelete.setOnClickListener { confirmDelete(ev) }
            listView.addView(row.root)
        }
    }

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

    private fun openEditor(existing: EventEntity?) {
        EventEditorDialog.show(
            activity = activity,
            date = date(),
            existing = existing,
            onSaved = { entity -> scope.launch { repo.save(entity); refresh() } },
            onDeleted = { entity -> confirmDelete(entity) },
        )
    }

    private fun confirmDelete(ev: EventEntity) {
        val recurring = ev.recurring
        val message = if (recurring)
            "Delete “${ev.title}”? This removes the whole repeating series."
        else "Delete “${ev.title}”?"
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Delete event")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ -> scope.launch { repo.delete(ev.id); refresh() } }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    private fun fmtMin(m: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60)
        }
        return timeFmt.format(cal.time)
    }
}
