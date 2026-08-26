package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One visit. **Id and time only** — a recents list must never leak a name to plaintext prefs.
 *
 * The property is [id] because arc 13 gave this store a second tenant (templates), but the JSON key
 * stays `notebookId`: blobs written before that rename are on every device this app has ever run
 * on, and a field name is not worth a silently emptied recents shelf.
 */
@Serializable
data class RecentEntry(@SerialName("notebookId") val id: String, val timestamp: Long)

/**
 * The last [MAX] things used, most-recent first — **two shelves, one implementation**:
 *
 *  - `sn_recents` (the default constructor) — notebooks. Written by `NotebookActivity.onCreate`
 *    (opening a notebook is the event) and re-stamped at close by [touch]; read by the library's
 *    Recents shelf and by the notebook's own Recents panel (arc 10), **in stored order, never
 *    re-sorted** (`library/RecentsAssembly`).
 *  - `sn_recents_templates` ([templates]) — paper, by card id (arc 13 / G5). Written **only when a
 *    template is actually applied**: the New Notebook screen creating from a pick, and the notebook
 *    re-papering a page. Creating, importing, renaming and saving a template are not uses of it.
 *    Its ids may be sentinels with no row, which is why its pruning goes through
 *    `templates/TemplateShelves` rather than a bare set of alive rows.
 *
 * It is one class rather than two because the two stores differ in exactly one string. A second
 * copy of the "move to front, cap, prune" arithmetic is the sibling-copy trap in a small place.
 *
 * A corrupt or hand-edited blob reads as an empty list rather than throwing — this is a
 * convenience, never a source of truth.
 */
class RecentsPrefs private constructor(context: Context, file: String) {

    constructor(context: Context) : this(context, FILE)

    private val prefs = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
    private val codec = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RecentEntry.serializer())

    fun entries(): List<RecentEntry> = try {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        codec.decodeFromString(serializer, raw)
    } catch (_: Exception) {
        emptyList()
    }

    /** Move [id] to the front, dropping anything past [MAX]. */
    fun record(id: String, now: Long = System.currentTimeMillis()) {
        val list = entries().toMutableList()
        list.removeAll { it.id == id }
        list.add(0, RecentEntry(id, now))
        if (list.size > MAX) list.subList(MAX, list.size).clear()
        save(list)
    }

    /**
     * Re-stamp [id] where it already sits (arc 10) — the notebook is closing, and the time a
     * recents row shows is "when I last put it down", not "when I opened it". **Never inserts**:
     * an id that is not listed has no business appearing at close, and the order is untouched
     * because [record] already moved it to the front when it opened.
     */
    fun touch(id: String, now: Long = System.currentTimeMillis()) {
        val list = entries()
        if (list.none { it.id == id }) return
        save(list.map { if (it.id == id) it.copy(timestamp = now) else it })
    }

    fun remove(id: String) {
        val list = entries().toMutableList()
        if (list.removeAll { it.id == id }) save(list)
    }

    /** Drop every entry whose id is not in [aliveIds] (called when the list is read for display). */
    fun pruneDeleted(aliveIds: Set<String>) {
        val list = entries().toMutableList()
        if (list.removeAll { it.id !in aliveIds }) save(list)
    }

    private fun save(list: List<RecentEntry>) {
        prefs.edit().putString(KEY, codec.encodeToString(serializer, list)).apply()
    }

    companion object {
        private const val FILE = "sn_recents"
        private const val TEMPLATES_FILE = "sn_recents_templates"
        private const val KEY = "entries"
        const val MAX = 20

        /** The template library's recently-applied paper (arc 13 / G5). */
        fun templates(context: Context): RecentsPrefs = RecentsPrefs(context, TEMPLATES_FILE)
    }
}
