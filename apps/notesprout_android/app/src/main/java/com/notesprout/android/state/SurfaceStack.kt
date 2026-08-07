package com.notesprout.android.state

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A screen a cold launch can reopen. The library is implicit — it is always the bottom of the stack. */
@Serializable
enum class AppSurface { NOTEBOOK, CALENDAR, DAY_WINDOW, SCRATCHPAD, TASKS, ROUTINE, TODAY }

/**
 * One surface Activity, as it sits on the task's back stack.
 *
 * [token] identifies the Activity *instance*, not the surface type: it survives recreation via
 * `onSaveInstanceState`, so an Activity that Android rebuilds (config change, or a task the OS
 * restores itself after process death) re-attaches to its existing entry instead of duplicating it.
 * The same notebook can legitimately appear twice in one stack (open notebook → calendar → day
 * window → open the same notebook again), which is why identity can't just be the surface + payload.
 */
@Serializable
data class SurfaceEntry(
    val token: String,
    val surface: AppSurface,
    /** NOTEBOOK — the notebook UUID. */
    val notebookId: String? = null,
    /** DAY_WINDOW — ISO-8601 date. */
    val dayDate: String? = null,
    /** DAY_WINDOW — `DayDetailActivity.ViewMode` name. */
    val dayView: String? = null,
    /** ROUTINE — the routine row's id. */
    val routineId: String? = null,
)

/**
 * The chain of surfaces the user has open, so a cold launch can put back what they were actually
 * doing — a scratch pad opened from the calendar comes back *over the calendar*, not over the library.
 *
 * The surface Activities maintain it themselves, from two lifecycle hooks:
 *
 *  - `onCreate` → [attach]: append myself (or refresh in place, if I'm a recreated instance).
 *    Needed as well as [markTop] because a restored stack is launched with `startActivities`, and
 *    everything below the top is created without ever being resumed.
 *  - `onResume` → [markTop]: I'm on screen, so drop anything still recorded above me. That is what
 *    pops a surface the user backed out of — no `onDestroy` bookkeeping, which would be unreliable
 *    exactly when it matters (a killed process gets no `onDestroy`).
 *
 * `MainActivity.onResume` calls [reset]: the library is on screen, so nothing is stacked on it.
 * Every path back to the library therefore heals the stack, so a stale entry can't outlive a visit
 * home. Ordering holds because the leaving Activity's `onPause` always precedes the revealed
 * Activity's `onResume`.
 *
 * Exact in memory for the life of the process, mirrored to prefs on every mutation — the mirror is
 * what survives the kill, and what `MainActivity` rebuilds from on the next cold launch.
 * Main thread only.
 */
object SurfaceStack {

    /** `onSaveInstanceState` key each surface Activity stores its [SurfaceEntry.token] under. */
    const val KEY_TOKEN = "surface_token"

    private const val PREFS_NAME = "notesprout_view_state"
    private const val KEY_STACK  = "surface_stack"
    // Pre-stack installs recorded only the reopen-this-notebook id. Read once, as a one-entry stack.
    private const val KEY_LEGACY_NOTEBOOK_ID = "last_notebook_id"

    private val codec = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SurfaceEntry.serializer())

    private var cached: MutableList<SurfaceEntry>? = null

    /** The stack, bottom-first. */
    fun load(context: Context): List<SurfaceEntry> = entries(context).toList()

    /** Record [entry] — appended if new, refreshed in place if this instance is already on the stack. */
    fun attach(context: Context, entry: SurfaceEntry) {
        val entries = entries(context)
        val at = entries.indexOfFirst { it.token == entry.token }
        if (at >= 0) entries[at] = entry else entries.add(entry)
        persist(context, entries)
    }

    /** [attach], then drop every entry above this one — they are Activities the user has left. */
    fun markTop(context: Context, entry: SurfaceEntry) {
        val entries = entries(context)
        val at = entries.indexOfFirst { it.token == entry.token }
        if (at < 0) {
            entries.add(entry)
        } else {
            entries[at] = entry
            while (entries.size > at + 1) entries.removeAt(entries.lastIndex)
        }
        persist(context, entries)
    }

    /** Nothing is stacked on the library. */
    fun reset(context: Context) = persist(context, mutableListOf())

    private fun entries(context: Context): MutableList<SurfaceEntry> =
        cached ?: read(context).toMutableList().also { cached = it }

    private fun read(context: Context): List<SurfaceEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_STACK, null)
            ?: return prefs.getString(KEY_LEGACY_NOTEBOOK_ID, null)
                ?.let { listOf(SurfaceEntry("legacy", AppSurface.NOTEBOOK, notebookId = it)) }
                ?: emptyList()
        return runCatching { codec.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun persist(context: Context, entries: MutableList<SurfaceEntry>) {
        cached = entries
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STACK, codec.encodeToString(serializer, entries))
            .remove(KEY_LEGACY_NOTEBOOK_ID)
            .apply()
    }
}
