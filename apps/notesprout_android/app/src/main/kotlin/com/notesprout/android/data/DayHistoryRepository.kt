package com.notesprout.android.data

import com.notesprout.android.crypto.KeyScope
import com.notesprout.android.data.index.ActivityType
import com.notesprout.android.data.index.CalendarDao
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotebookActivityDao
import com.notesprout.android.data.index.NotebookActivityEntity
import com.notesprout.android.data.index.NotebookObject
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ObjectEntity
import com.notesprout.android.data.index.ObjectSummary
import com.notesprout.android.data.index.ObjectType
import com.notesprout.android.data.index.columnarLocked
import com.notesprout.android.data.index.notebookMeta
import com.notesprout.android.data.recents.ResolvedRecent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Query + logging layer for the Day-Detail "Notebooks" and "History" views.
 *
 * Backed by the global index ([NotesproutIndex], SQLCipher-encrypted under the global key):
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

    /**
     * Card cover info for a notebook: [locked] notebooks show a lock icon and expose no
     * [snapshotB64] (plaintext-leak guard, matching MainActivity's list rendering). Only
     * NOTEBOOK-scope (private) encryption locks — GLOBAL-scope covers render normally.
     */
    data class NotebookCover(val locked: Boolean, val snapshotB64: String?)

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
        withContext(Dispatchers.IO) { notebooksFor(date, kind, indexRepo.getAllFolders()) }

    /** [notebooksFor] with the folder list prefetched, so [notebooksForDay] shares one across kinds. */
    private suspend fun notebooksFor(
        date: LocalDate,
        kind: Kind,
        folders: List<ObjectEntity>,
    ): List<ResolvedRecent> = withContext(Dispatchers.IO) {
        val (start, end) = dayBounds(date)

        when (kind) {
            Kind.CREATED -> indexRepo.getNotebooksCreatedIn(start, end)
                .sortedByDescending { it.createdAt }
                .map { it.toResolved(it.createdAt, folders) }

            Kind.OPENED, Kind.EDITED -> {
                val type = if (kind == Kind.OPENED) ActivityType.OPENED else ActivityType.EDITED
                // Rows already come newest-first; keep the first (newest) per notebook.
                val newestPerNotebook = LinkedHashMap<String, Long>()
                for (row in activityDao.inRange(type, start, end)) {
                    newestPerNotebook.putIfAbsent(row.notebookId, row.timestamp)
                }
                // One blob-free batch read instead of a full-row fetch (cover blob included) per id.
                val summaries = indexRepo.getObjectSummaries(newestPerNotebook.keys)
                    .filter { it.deletedAt == null && it.type == ObjectType.NOTEBOOK }
                    .associateBy { it.id }
                newestPerNotebook.entries.mapNotNull { (nbId, ts) ->
                    summaries[nbId]?.toResolved(ts, folders)
                }.sortedByDescending { it.timestamp }
            }
        }
    }

    /**
     * One notebook's activity on a day, merged across all three [Kind]s — the row model behind the
     * calendar's long-press list. [timestamp] is the newest time across whichever flags are set.
     */
    data class DayNotebook(
        val notebookId: String,
        val notebookName: String,
        val folderPath: String,
        val timestamp: Long,
        val created: Boolean,
        val opened: Boolean,
        val edited: Boolean,
    ) {
        /** Human label for the flags, in the order things happen to a notebook. */
        val activityLabel: String
            get() = buildList {
                if (created) add("created")
                if (opened) add("opened")
                if (edited) add("edited")
            }.joinToString(" · ")
    }

    /**
     * Every notebook touched on [date], **one row per notebook** carrying all of that day's activity
     * flags. Newest-first. Unlike the day window's three separate lists, this is the merged view the
     * calendar long-press popup shows.
     */
    suspend fun notebooksForDay(date: LocalDate): List<DayNotebook> = withContext(Dispatchers.IO) {
        val folders = indexRepo.getAllFolders()
        val merged = LinkedHashMap<String, DayNotebook>()
        for (kind in listOf(Kind.CREATED, Kind.OPENED, Kind.EDITED)) {
            for (r in notebooksFor(date, kind, folders)) {
                val prev = merged[r.notebookId]
                merged[r.notebookId] = DayNotebook(
                    notebookId = r.notebookId,
                    notebookName = r.notebookName,
                    folderPath = r.folderPath,
                    timestamp = maxOf(prev?.timestamp ?: 0L, r.timestamp),
                    created = prev?.created == true || kind == Kind.CREATED,
                    opened = prev?.opened == true || kind == Kind.OPENED,
                    edited = prev?.edited == true || kind == Kind.EDITED,
                )
            }
        }
        merged.values.sortedByDescending { it.timestamp }
    }

    /**
     * Cover for a notebook card. Missing / undecodable rows resolve to a plain notebook icon
     * (no cover, not locked). Only NOTEBOOK-scope (private) encryption locks the cover behind an
     * icon; GLOBAL-scope covers render (the index is encrypted at rest and the key is available).
     */
    suspend fun coverFor(notebookId: String): NotebookCover = withContext(Dispatchers.IO) {
        val entity = indexRepo.getNotebook(notebookId) ?: return@withContext NotebookCover(false, null)
        val obj = runCatching { entity.notebookMeta() }.getOrNull()
            ?: return@withContext NotebookCover(false, null)
        val locked = obj.encrypted && obj.keyScope != KeyScope.GLOBAL
        NotebookCover(locked, if (locked) null else obj.snapshot)
    }

    /**
     * Lock state for a batch of notebooks, keyed by id — the Today dashboard's per-row question,
     * answered without [coverFor]'s full-row read. Deriving `locked` needs two scalar columns, and
     * paying for the cover blob (plus a base64 encode of it) per row was most of that list's load
     * time. A legacy pre-columnar row still takes the full read — its truth is in the JSON — but
     * those are rare after the compactor sweep. Missing / undecodable ids resolve absent or
     * unlocked, matching [coverFor].
     */
    suspend fun locksFor(ids: Collection<String>): Map<String, Boolean> =
        withContext(Dispatchers.IO) {
            indexRepo.getObjectSummaries(ids).associate { s ->
                s.id to if (s.legacy) coverFor(s.id).locked else s.columnarLocked()
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

        val pattern = "cal-daynote-____-%02d-%02d".format(java.util.Locale.ROOT, month, dayOfMonth)
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

    private fun ObjectSummary.toResolved(timestamp: Long, folders: List<ObjectEntity>) =
        ResolvedRecent(
            notebookId = id,
            notebookName = name,
            folderPath = buildFolderPath(parentId, folders),
            timestamp = timestamp,
        )

    /** Full breadcrumb for [parentId] (root → `"Notebooks"`) — mirrors RecentsManager. */
    private fun buildFolderPath(parentId: String?, allFolders: List<ObjectEntity>): String {
        val segments = mutableListOf<String>()
        val visited = mutableSetOf<String>() // corrupt parentId cycle must not spin the IO thread
        var currentId: String? = parentId
        while (currentId != null && visited.add(currentId)) {
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
