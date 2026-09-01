package com.symmetricalpalmtree.notesproutsn.library

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.FuzzyRank
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType

/**
 * The library's **search shelf** (arc 20 / Q1) — the field in the top bar, the query behind it, and
 * the cards it produces. It sits beside [LibraryActivity] the way [TemplateShelfView] sits beside
 * the template browser, and for the same reason: the library is about *where you are standing*, and
 * a search has no where.
 *
 * Three decisions are worth reading before changing anything here:
 *
 *  - **The whole library, always.** Folders and notebooks from anywhere in the tree, whatever folder
 *    the user is in. A search scoped to the current folder answers "no" for a notebook two folders
 *    over, which is the one question a search exists to answer.
 *  - **The Search key runs it, not every keystroke.** Filtering as you type is a full card page
 *    repaint — covers included — per pause on an e-ink panel. The user types, then says when.
 *  - **The query lives in memory only.** Prefs hold ids and enum names, never a display name, and a
 *    typed query is a name; it also means a relaunch never reopens a stale shelf. It *does* survive
 *    a hop to another shelf and back within the process, pre-filled and selected, because retyping
 *    on the Supernote's on-screen keyboard is the expensive part (the arc-13 dialog's call).
 */
class LibrarySearch(
    private val activity: Activity,
    private val repo: IndexRepository,
    private val field: EditText,
    /** Ask the library to re-list and redraw — its own `refresh`. */
    private val onQueryRun: () -> Unit,
) {

    /** What is being searched for. Empty until the user runs one; never written to disk. */
    var query: String = ""
        private set

    init {
        field.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) { run(); true } else false
        }
    }

    // ── The field ────────────────────────────────────────────────────────────

    /**
     * Entering search mode: the last query comes back select-all'd, ready to be typed over, and the
     * keyboard opens on its own — the field is the only reason the mode exists, so making the user
     * tap it first would be a step that means nothing.
     */
    fun open() {
        field.setText(query)
        field.setSelection(0, query.length)
        field.requestFocus()
        // Flag 0, not SHOW_IMPLICIT: an implicit show is documented as skippable when a hard
        // keyboard is attached — and on Ratta hardware keys are delivered ONLY while the IME is
        // shown, so an implicit show that the system quietly declined would stand the user in a
        // search mode nothing can be typed into, by either keyboard.
        field.post { imm()?.showSoftInput(field, 0) }
    }

    /** Leaving search mode. The query survives in memory; the keyboard and the focus do not. */
    fun close() {
        hideKeyboard()
        dropFocus()
    }

    /**
     * Run whatever is in the field.
     *
     * The keyboard is dismissed here, and this is the **one** place in the app that dismisses it —
     * a deliberate, narrow exception to the Ratta rule that nothing may hide the IME (hiding it also
     * stops a hardware keyboard's keys from being delivered). The rule protects a user who still
     * needs to type; this fires only when they have said they are done, and the results are what
     * the keyboard would otherwise be covering. Tapping the field brings it back.
     */
    fun run() {
        val typed = field.text.toString().trim()
        // Submitting nothing when nothing was searched for is **inert** — it must not take the
        // keyboard away. The Search button does not toggle the mode off, so it collects the taps
        // that Pinned and Recents would have toggled with, and answering those by dismissing an
        // expensive on-screen keyboard for a screen that does not change is the worst reading of
        // them. Clearing a query that IS there stays a real act: it empties the shelf.
        if (typed.isEmpty() && query.isEmpty()) return
        query = typed
        Slog.d(TAG) { "search run, ${query.length} chars" }
        hideKeyboard()
        dropFocus()
        onQueryRun()
    }

    private fun hideKeyboard() = imm()?.hideSoftInputFromWindow(field.windowToken, 0)

    /**
     * Actually give up focus. A bare `clearFocus()` does not: it re-runs the root's focus search,
     * and the field is the only view in this bar that is focusable **in touch mode** — so focus
     * comes straight back, and with it `TextView`'s caret `Blink`, which invalidates every 500 ms.
     * On an EPD panel that is a permanent partial-refresh loop on a screen whose whole rule is that
     * nothing repaints unless something happened.
     */
    private fun dropFocus() {
        field.isFocusableInTouchMode = false
        field.clearFocus()
        field.isFocusableInTouchMode = true
    }

    private fun imm(): InputMethodManager? =
        activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    // ── The shelf ────────────────────────────────────────────────────────────

    /**
     * What an empty shelf says: two different answers, because "you have not searched yet" and
     * "there is nothing called that" are two different things to be told.
     */
    fun emptyTextRes(): Int =
        if (FuzzyRank.isRunnable(query)) R.string.library_search_empty else R.string.library_search_prompt

    /**
     * The cards, ranked by [SearchAssembly]. Two blob-free whole-library listings per run — names
     * and dates, no covers; the library fetches the covers for the visible page only, exactly as it
     * does for every other shelf.
     *
     * Every card's second line is its **parent folder's name** (the Recents shelf's call — on a flat
     * shelf "where is it" beats "when"), resolved from the folder listing that is already in hand,
     * so the subtitles cost no extra reads at all. Folders get one too: this shelf is flat, and
     * folder names are only unique per parent, so two called "Notes" would be one card twice.
     */
    suspend fun cards(pinnedIds: Set<String>): List<CardItem> {
        if (!FuzzyRank.isRunnable(query)) return emptyList()
        val folders = repo.allFolders()
        val notebooks = repo.allNotebooks()
        val folderNames = folders.associate { it.id to it.name }
        val root = activity.getString(R.string.recents_parent_root)
        return SearchAssembly.rank(folders, notebooks, query).map { s ->
            val where = parentLabel(s, folderNames, root)
            if (s.type == ObjectType.FOLDER) CardItem.Folder(s, subtitle = where)
            else CardItem.Notebook(s, pinned = s.id in pinnedIds, subtitle = where)
        }
    }

    /** The folder a card lives in. Unknown (a parent that is not an alive folder) reads as the
     *  root, the same fallback the Recents shelf makes — a card must always say where it is. */
    private fun parentLabel(s: ObjectSummary, folderNames: Map<String, String>, root: String): String =
        s.parentId?.let { folderNames[it] } ?: root

    private companion object {
        const val TAG = "LibrarySearch"
    }
}
