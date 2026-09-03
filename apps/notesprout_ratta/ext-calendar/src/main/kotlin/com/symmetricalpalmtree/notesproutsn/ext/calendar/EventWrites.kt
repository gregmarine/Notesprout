package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.Statement
import java.time.LocalDate

/**
 * What a save, a delete and the three recurring **scopes** come to, as statement lists (arc 24 /
 * Z1) — pure, so the shape of every write is pinned by `EventWritesTest` without a store.
 *
 * **Order inside a list is load-bearing.** The event row's upsert leads (the children's foreign key
 * needs its parent), then each child set is emptied and rewritten, then the note's own statements
 * last. Under the batch cap that whole list is ONE transaction, which is what makes "Cancel wrote
 * nothing" and "Save wrote everything" both true; past the cap the store's `compensated` write
 * keeps the promise by hand.
 *
 * **Every occurrence is computed on the ORIGINAL event's rule** (og's rule, and the only one that
 * makes sense): the person tapped an occurrence of the series as it *is*, and an edit that moved
 * the date must still remove the instance they were looking at.
 *
 * `now` is a parameter everywhere so a test can pin it, and nothing here reads a clock.
 */
object EventWrites {

    /**
     * A whole event as it should now read: the upsert, its three child sets rewritten, then
     * [noteStatements] — the note's stroke ops, which the caller has already built against
     * [NoteSql]. Idempotent end to end, so a retried batch converges on the same rows.
     */
    fun save(e: Event, now: Long, noteStatements: List<Statement> = emptyList()): List<Statement> {
        val out = ArrayList<Statement>(8 + noteStatements.size)
        out += EventSql.insertEvent(e, now)
        out += EventSql.updateEvent(e, now)
        out += EventSql.clearWeekdays(e.id)
        for (d in e.recurrence?.weekdays.orEmpty().sorted()) out += EventSql.insertWeekday(e.id, d)
        out += EventSql.clearExceptions(e.id)
        for (d in e.exceptions.sorted()) out += EventSql.insertException(e.id, d)
        out += EventSql.clearReminders(e.id)
        for (r in e.reminders) out += EventSql.insertReminder(e.id, r.amount, r.unit)
        out += noteStatements
        return out
    }

    /** The whole event, cascade and all. */
    fun delete(id: String): List<Statement> = listOf(EventSql.deleteEvent(id))

    /**
     * Deleting as seen on [viewedDay], at [scope]. Null means **nothing to do**: a recurring event
     * the viewed day maps to no occurrence of — which the screens can only reach by racing an edit,
     * and which must not be answered by deleting the series.
     */
    fun deleteWithScope(scope: Scope, event: Event, viewedDay: LocalDate, now: Long): List<Statement>? {
        if (!event.recurring || scope == Scope.ALL) return delete(event.id)
        val occurrence = Recurrence.occurrenceStartCovering(event, viewedDay) ?: return null
        return when (scope) {
            Scope.THIS -> exceptionOn(event.id, occurrence, now)
            // A split at (or before) the first occurrence leaves nothing behind — that is a whole delete.
            Scope.FOLLOWING ->
                if (!occurrence.isAfter(event.startDate)) delete(event.id)
                else listOf(EventSql.truncateEvent(event.id, occurrence.minusDays(1), now))
            Scope.ALL -> delete(event.id)   // unreachable; the guard above answered it
        }
    }

    /**
     * Editing as seen on [viewedDay], at [scope]. Null means nothing to do, as in [deleteWithScope].
     *
     * - **[Scope.THIS]** — the occurrence leaves the series (an exception at its *original* start)
     *   and comes back as a standalone one-off under [newId], carrying the edited fields, the
     *   reminders **and the note**. Changing the date in the editor therefore *moves* just that
     *   occurrence;
     * - **[Scope.FOLLOWING]** — the original ends the day before the occurrence and a fresh series
     *   starts under [newId] with no inherited exceptions (they belonged to the truncated tail);
     * - **[Scope.ALL]**, a non-recurring original, or a brand-new event — [editSeries], in place.
     */
    fun editWithScope(
        scope: Scope,
        original: Event?,
        edited: Event,
        viewedDay: LocalDate,
        newId: String,
        now: Long,
        noteStatements: List<Statement> = emptyList(),
    ): List<Statement>? {
        if (original == null || !original.recurring || scope == Scope.ALL) {
            return editSeries(original, edited, viewedDay, now, noteStatements)
        }
        val occurrence = Recurrence.occurrenceStartCovering(original, viewedDay) ?: return null
        return when (scope) {
            Scope.THIS -> exceptionOn(original.id, occurrence, now) +
                save(edited.copy(id = newId, recurrence = null, exceptions = emptySet(), createdAt = now), now, noteStatements)

            Scope.FOLLOWING ->
                if (!occurrence.isAfter(original.startDate)) editSeries(original, edited, viewedDay, now, noteStatements)
                else listOf(EventSql.truncateEvent(original.id, occurrence.minusDays(1), now)) +
                    save(edited.copy(id = newId, exceptions = emptySet(), createdAt = now), now, noteStatements)

            Scope.ALL -> editSeries(original, edited, viewedDay, now, noteStatements)   // unreachable
        }
    }

    /**
     * Which id an [editWithScope] lands the edited fields under — [edited]'s own for an in-place
     * series edit, [newId] for an override or a new series; null exactly when [editWithScope] is.
     * The store needs the answer to know whether it minted a row (and so what a failed write has
     * to compensate), and one function deciding it is what keeps the two from disagreeing.
     */
    fun editLandsUnder(scope: Scope, original: Event?, edited: Event, viewedDay: LocalDate, newId: String): String? {
        if (original == null || !original.recurring || scope == Scope.ALL) return edited.id
        val occurrence = Recurrence.occurrenceStartCovering(original, viewedDay) ?: return null
        if (scope == Scope.FOLLOWING && !occurrence.isAfter(original.startDate)) return edited.id
        return newId
    }

    /**
     * The whole series, edited in place.
     *
     * Two things carry forward from [original], and both were bugs in og before they were rules:
     * the **exceptions** (occurrences already removed stay removed), and the **anchor**. The editor
     * pre-fills its dates from the *tapped occurrence*, so saving those dates back unchanged would
     * silently re-anchor the series — a birthday would forget the year it started. When the dates
     * come back exactly as the prefill left them the stored anchor is kept; a deliberately changed
     * date re-anchors, which is what moving a series means.
     */
    fun editSeries(
        original: Event?,
        edited: Event,
        viewedDay: LocalDate,
        now: Long,
        noteStatements: List<Statement> = emptyList(),
    ): List<Statement> {
        val exceptions = if (edited.recurrence != null) original?.exceptions.orEmpty() else emptySet()
        val prefillStart = original?.takeIf { it.recurring }?.let { Recurrence.occurrenceStartCovering(it, viewedDay) }
        val untouched = original != null && prefillStart != null &&
            edited.startDate == prefillStart && edited.endDate == prefillStart.plusDays(original.spanDays)
        val anchored =
            if (untouched) edited.copy(startDate = original.startDate, endDate = original.endDate) else edited
        return save(anchored.copy(exceptions = exceptions), now, noteStatements)
    }

    /** One occurrence out of a series: the exception row, and the parent stamped so a reader can
     *  see the series changed even though none of its own columns did. */
    private fun exceptionOn(eventId: String, occurrenceStart: LocalDate, now: Long): List<Statement> =
        listOf(EventSql.insertException(eventId, occurrenceStart), EventSql.touchEvent(eventId, now))
}
