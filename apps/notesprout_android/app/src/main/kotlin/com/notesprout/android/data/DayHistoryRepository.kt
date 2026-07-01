package com.notesprout.android.data

import com.notesprout.android.data.index.ActivityType
import com.notesprout.android.data.index.CalendarDao
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotebookActivityDao
import com.notesprout.android.data.index.NotebookActivityEntity
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ObjectEntity
import com.notesprout.android.data.index.ObjectType
import com.notesprout.android.data.recents.ResolvedRecent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Query + logging layer for the Day-Detail "Notebooks" and "History" views.
 *
 * Backed by the plaintext global index ([NotesproutIndex]):
 * - **Opened / Edited** come from the `notebook_activity` table (forward-only log; see
 *   [NotebookActivityEntity]).
 * - **Created** is derived retroactively from [ObjectEntity.createdAt] — no rows are logged.
 *
 * Day boundaries use the device-default time zone: `[startOfDay, startOfNextDay)` in epoch millis.
 */
class DayHistoryRepository(
    private val activityDao: NotebookActivityDao = NotesproutIndex.notebookActivityDao(),
    private val calendarDao: CalendarDao = NotesproutIndex.calendarDao(),
    private val indexRepo: IndexRepository = IndexRepository(NotesproutIndex.dao()),
) {

    /** What a caller asks for. CREATED is derived; OPENED/EDITED read the activity log. */
    enum class Kind { OPENED, EDITED, CREATED }

    // region Logging

    /** Record that [notebookId] was opened, stamped *now*. No-op on a blank id. */
    suspend fun logOpened(notebookId: String) = log(notebookId, ActivityType.OPENED)

    /** Record that [notebookId] was edited (content changed this session), stamped *now*. */
    suspend fun logEdited(notebookId: String) = log(notebookId, ActivityType.EDITED)

    private suspend fun log(notebookId: String, type: String) = withContext(Dispatchers.IO) {
        if (notebookId.isBlank()) return@withContext
        activityDao.insert(
            NotebookActivityEntity(
                id = UUID.randomUUID().toString(),
                notebookId = notebookId,
                activityType = type,
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    // endregion

    // region Queries

    /**
     * Notebooks with activity of [kind] on [date], resolved for display and deduped to **one card
     * per notebook** (keeping that day's most-recent time). Missing / soft-deleted / non-notebook
     * rows are dropped. Newest-first.
     */
    suspend fun notebooksFor(date: LocalDate, kind: Kind): List<ResolvedRecent> =
        withContext(Dispatchers.IO) {
            val (start, end) = dayBounds(date)
            val folders = indexRepo.getAllFolders()

            when (kind) {
                Kind.CREATED -> indexRepo.getAllNotebooks()
                    .filter { it.createdAt in start until end }
                    .sortedByDescending { it.createdAt }
                    .map { it.toResolved(it.createdAt, folders) }

                Kind.OPENED, Kind.EDITED -> {
                    val type = if (kind == Kind.OPENED) ActivityType.OPENED else ActivityType.EDITED
                    // Rows already come newest-first; keep the first (newest) per notebook.
                    val newestPerNotebook = LinkedHashMap<String, Long>()
                    for (row in activityDao.inRange(type, start, end)) {
                        newestPerNotebook.putIfAbsent(row.notebookId, row.timestamp)
                    }
                    newestPerNotebook.entries.mapNotNull { (nbId, ts) ->
                        val nb = indexRepo.getNotebook(nbId)
                        if (nb == null || nb.deletedAt != null || nb.type != ObjectType.NOTEBOOK) null
                        else nb.toResolved(ts, folders)
                    }.sortedByDescending { it.timestamp }
                }
            }
        }

    /**
     * The page id (`cal-daynote-YYYY-MM-DD`) of the read-only day note for [date], if one exists and
     * holds content; null otherwise.
     */
    suspend fun dayNotePageId(date: LocalDate): String? = withContext(Dispatchers.IO) {
        val id = "cal-daynote-$date"
        calendarDao.dayNotePagesWithContent(id).firstOrNull()
    }

    /**
     * Years (descending) that have *any* data for the given month/day: an activity row, a notebook
     * created that day, or a day note with content. Used to constrain the History year picker.
     */
    suspend fun yearsWithData(month: Int, dayOfMonth: Int): List<Int> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val years = sortedSetOf<Int>()

        fun addMatching(millis: Long) {
            val d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            if (d.monthValue == month && d.dayOfMonth == dayOfMonth) years.add(d.year)
        }

        activityDao.allTimestamps(ActivityType.OPENED).forEach(::addMatching)
        activityDao.allTimestamps(ActivityType.EDITED).forEach(::addMatching)
        indexRepo.getAllNotebooks().forEach { addMatching(it.createdAt) }

        val pattern = "cal-daynote-____-%02d-%02d".format(month, dayOfMonth)
        for (pageId in calendarDao.dayNotePagesWithContent(pattern)) {
            // cal-daynote-YYYY-MM-DD → parse the YYYY segment.
            pageId.removePrefix("cal-daynote-").take(4).toIntOrNull()?.let { years.add(it) }
        }

        years.toList().sortedDescending()
    }

    // endregion

    // region Helpers

    private fun ObjectEntity.toResolved(timestamp: Long, folders: List<ObjectEntity>) =
        ResolvedRecent(
            notebookId = id,
            notebookName = name,
            folderPath = buildFolderPath(parentId, folders),
            timestamp = timestamp,
        )

    /** Full breadcrumb for [parentId] (root → `"Notebooks"`) — mirrors RecentsManager. */
    private fun buildFolderPath(parentId: String?, allFolders: List<ObjectEntity>): String {
        val segments = mutableListOf<String>()
        var currentId: String? = parentId
        while (currentId != null) {
            val folder = allFolders.find { it.id == currentId } ?: break
            segments.add(0, folder.name)
            currentId = folder.parentId
        }
        segments.add(0, "Notebooks")
        return segments.joinToString(" › ")
    }

    private fun dayBounds(date: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    // endregion
}
