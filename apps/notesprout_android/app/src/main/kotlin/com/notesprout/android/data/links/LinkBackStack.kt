package com.notesprout.android.data.links

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One "where a link follow came from" record — the origin page the user left when following a link.
 *
 * [notebookId] is the index UUID (same id used everywhere as `EXTRA_NOTEBOOK_ID`); [pageId] is the
 * page row id within that notebook. A swipe-up pops the newest entry and navigates back to it.
 */
@Serializable
data class BackEntry(
    val notebookId: String,
    val pageId: String,
)

/**
 * App-level, persisted back-stack for link navigation.
 *
 * Backed by a single [SharedPreferences] key holding a JSON-serialized `List<BackEntry>` (oldest
 * first, newest last — a stack). Mirrors [com.notesprout.android.data.recents.RecentsManager] and
 * [com.notesprout.android.state.AppStateManager] — it lives in SharedPreferences, never in
 * `notesprout.db` or any `.soil` file, so it survives notebook close/open and process death.
 *
 * Lifecycle (see [com.notesprout.android.NotebookActivity]):
 * - **push** on every successful link follow — the *origin* `{notebookId, pageId}` is recorded.
 * - **pop** on a swipe-up — returns the most recent origin to navigate back to; empty ⇒ no-op.
 * - **clear** on a "fresh" navigation (opening a notebook from MainActivity / Recents), which
 *   resets the trail. Following a link or switching notebooks via a link preserves/extends it.
 */
object LinkBackStack {

    /** Cap the trail so a runaway follow-loop can't grow the stored JSON without bound. */
    const val MAX_ENTRIES = 50
    private const val PREFS_NAME = "notesprout_link_backstack"
    private const val KEY_ENTRIES = "entries"

    private val codec = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(BackEntry.serializer())

    /** All entries, oldest first. Tolerant of malformed/absent JSON → empty list. */
    fun load(context: Context): List<BackEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { codec.decodeFromString(listSerializer, raw) }.getOrElse { emptyList() }
    }

    /** Push [entry] onto the stack (newest last), capped to [MAX_ENTRIES] by dropping the oldest. */
    fun push(context: Context, entry: BackEntry) {
        val updated = (load(context) + entry).takeLast(MAX_ENTRIES)
        persist(context, updated)
    }

    /** Pop and return the most recent entry, persisting the shortened stack. Empty ⇒ null. */
    fun pop(context: Context): BackEntry? {
        val current = load(context)
        if (current.isEmpty()) return null
        val entry = current.last()
        persist(context, current.dropLast(1))
        return entry
    }

    /** True when nothing is on the stack (a swipe-up would be a no-op). */
    fun isEmpty(context: Context): Boolean = load(context).isEmpty()

    /** Wipe the stack — the "fresh navigation" reset point. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    private fun persist(context: Context, entries: List<BackEntry>) {
        prefs(context).edit()
            .putString(KEY_ENTRIES, codec.encodeToString(listSerializer, entries))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
