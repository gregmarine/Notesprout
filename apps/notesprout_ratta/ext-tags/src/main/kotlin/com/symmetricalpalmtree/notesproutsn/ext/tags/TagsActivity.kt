package com.symmetricalpalmtree.notesproutsn.ext.tags

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.tags.databinding.ActivityTagsBinding
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.TagIndex
import com.symmetricalpalmtree.notesproutsn.extension.TagRules
import com.symmetricalpalmtree.notesproutsn.extension.TagShowing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * The extension-owned tag screen (arc 21 / W1; UI-rule tier 2) — SN's **third** screen-owning point
 * and the first whose screen carries **no paper**. There is no `PaperView`, no g-paper call and
 * therefore **no EPD handoff**: M3 measured the answer for a non-drawing child screen and it is
 * stop-behind, cross-process included. Do not add one.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is exported
 * (it has to be — the host launches it by action) and only a `startActivityForResult` from the host
 * package gets in. A plain `am start` from a shell has a null `callingPackage` and is refused.
 *
 * Everything it works on is lent: the store binder from `begin` and the [TagShowing] from
 * `configureShowing`, both read out of [TagSession] (same process). The screen opens **no file** and
 * writes nothing to disk itself — the index is one value in the host's store.
 *
 * **MODE_MANAGE** (arc 21 / W2) puts one more state in front of that, and only one: an **overview**
 * of the notebook and every page the host named, each row over the tags it carries. Tapping a row
 * makes it the target and the screen becomes exactly what it is in every other mode; the back arrow
 * returns to the overview, and only from the overview does it leave. Adding and removing therefore
 * have one implementation and one set of gestures whatever door was taken to get here — the
 * alternative, editing many targets on one screen, would have needed a second grammar for "which of
 * these am I acting on".
 *
 * Three surfaces, three unambiguous gestures:
 *  - the target's own tags — **tap removes** it from this notebook or page. The tag itself stays in
 *    the library (the wizard's lifecycle call: a tag persists until it is explicitly deleted);
 *  - the add field — text, then ⊕ or the keyboard's Done. Nothing is a tag until it is normalized
 *    ([TagRules]) and nothing is created twice: an existing identity is attached, with the casing it
 *    was first entered in;
 *  - the list below — every tag while the field is empty, the matching ones while it is not. A
 *    **tap toggles** this target's membership (the ✓ says which way), a **long press deletes the
 *    tag everywhere**, behind a confirm that names how much of the library it reaches.
 *
 * **Every edit is written before it is shown.** `edit` re-reads the index inside
 * [TagSession.writes], applies the change, writes it, and only then does the screen redraw — so what
 * is on the glass is what is in the store, and a failed write leaves the screen showing what is
 * still true.
 *
 * IME rules (Ratta): the keyboard is asked for with the **explicit** flag 0 — `SHOW_IMPLICIT` is
 * skippable with a hardware keyboard attached, and on Supernote hardware keys are delivered only
 * while the IME is shown — and it is **never hidden** while the field has focus.
 */
class TagsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTagsBinding
    private lateinit var showing: TagShowing

    private var index: TagIndex = TagIndex.EMPTY
    private var page = 0
    private var rowHeightPx = 1
    private var targetRowHeightPx = 1

    /**
     * The target every edit on this screen lands on. In BROWSE and ADD it is the showing's, for the
     * whole showing. In MANAGE it is whichever row of the overview was tapped — so the rest of the
     * screen never has to ask which mode it is in, only what the target is.
     */
    private lateinit var target: Target

    /** MANAGE's rows, built once from the showing: the notebook, then every page the host named. */
    private var manageTargets: List<Target> = emptyList()

    /** True while MANAGE is showing its list of targets rather than one target's tags. */
    private var overview = false

    /** Where the overview was left when a target was opened. Kept apart from [page], which belongs
     *  to the tag list: coming back from Page 20 must land where Page 20 was, not at the top. */
    private var overviewPage = 0

    /** MODE_ADD owes this showing one keyboard, raised at the first window focus. See
     *  [onWindowFocusChanged] for why it cannot be `onResume`. */
    private var pendingAddFocus = false

    /** A thing tags hang on, and the words this screen uses for it. [header] is the section line
     *  above its tags; [rowLabel] is how the overview lists it. */
    private class Target(
        val notebookId: String,
        val pageId: String?,
        val header: String,
        val rowLabel: String,
    )

    /** One edit at a time: e-ink gives a tap no feedback for hundreds of ms, so the second tap is
     *  taken as read rather than queued behind a store write. */
    private var busy = false

    /** The band height the current page size was computed from — a re-layout with the same height
     *  (the IME closing, a row being added) must not redraw the list under the user's finger. */
    private var bandHeightPx = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            super.onCreate(savedInstanceState)
            return
        }
        super.onCreate(savedInstanceState)
        binding = ActivityTagsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        rowHeightPx = TagRowView.rowHeightPx(this)
        targetRowHeightPx = TagRowView.targetRowHeightPx(this)

        val parked = TagSession.showing
        if (parked == null || TagSession.store == null) {
            // The host never launched us, or it revoked the bind before we came up. Say so — a
            // screen that opens empty and does nothing reads as broken. It must leave on the
            // dialog's *dismiss*, never beside it: `Dialogs.problem` has no dismiss callback, so
            // finishing on the next line tears the window down before the dialog is drawn and the
            // screen flashes and vanishes with nothing said — the very thing this branch exists to
            // avoid. `failAndClose` is the same shape further down.
            Slog.d(TAG) { "no showing parked — refusing" }
            failAndClose(R.string.tags_unavailable)
            return
        }
        showing = parked
        buildTargets()

        binding.title.text = showing.targetLabel
        // The arrow leaves — except inside a MANAGE target, where it comes back out to the
        // overview first. Both Backs are the same door, so the dispatcher takes the same route.
        binding.btnBack.setOnClickListener { leave() }
        binding.btnBack.setOnLongClickListener {
            hint(if (canReturnToOverview()) R.string.cd_tags_back_to_overview else R.string.cd_tags_back)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        binding.btnAdd.setOnClickListener { addTyped() }
        binding.btnAdd.setOnLongClickListener { hint(R.string.cd_tags_add) }
        binding.btnPrevPage.setOnClickListener { turnPage(-1) }
        binding.btnPrevPage.setOnLongClickListener { hint(R.string.cd_tags_prev_page) }
        binding.btnNextPage.setOnClickListener { turnPage(1) }
        binding.btnNextPage.setOnLongClickListener { hint(R.string.cd_tags_next_page) }

        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { addTyped(); true } else false
        }
        binding.input.doAfterTextChanged {
            // The filter runs over the index already in memory — never a store call per keystroke.
            // Repainting the list region is the accepted EPD cost.
            //
            // Silent in MANAGE's overview: the field is not even on screen up there, and the only
            // writes it takes are the clears the two state changes make — which must not send the
            // list of targets back to its first page.
            if (overview) return@doAfterTextChanged
            page = 0
            renderList()
        }
        showing.prefill?.let { binding.input.setText(it) }
        pendingAddFocus = showing.mode == TagShowing.MODE_ADD

        // The page size is what actually FITS: rows are measured against the real band, and the
        // band shrinks when the IME opens (adjustResize). Re-render only when the height moves.
        // Posted, not called inline: this fires from inside a layout pass, and the render adds and
        // removes the band's own children.
        binding.listBand.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop || bandHeightPx < 0) binding.listBand.post { renderList() }
        }

        load()
    }

    // ── Targets and modes (arc 21 / W2) ──────────────────────────────────────

    /**
     * Work out what this showing is about, once. MANAGE opens on the overview and its targets are
     * the notebook plus every page the host named — **in the host's words**: a page number is the
     * host's to resolve and the extension has no idea what a page is. Every other mode has the one
     * target the showing named.
     */
    private fun buildTargets() {
        val notebook = Target(
            notebookId = showing.notebookId,
            pageId = showing.pageId,
            header = getString(
                if (showing.targetKind == TagShowing.TARGET_NOTEBOOK) R.string.tags_on_notebook
                else R.string.tags_on_page,
            ),
            rowLabel = getString(R.string.tags_manage_notebook_row),
        )
        if (showing.mode != TagShowing.MODE_MANAGE) {
            target = notebook
            overview = false
            return
        }
        manageTargets = TagManage.targets(
            notebookId = showing.notebookId,
            notebookLabel = notebook.rowLabel,
            pageIds = showing.pageIds,
            pageLabels = showing.pageLabels,
        ).map { row ->
            if (row.pageId == null) notebook
            else Target(
                notebookId = row.notebookId,
                pageId = row.pageId,
                header = getString(R.string.tags_on_named, row.label),
                rowLabel = row.label,
            )
        }
        target = notebook
        overview = true
    }

    private fun canReturnToOverview(): Boolean =
        ::showing.isInitialized && showing.mode == TagShowing.MODE_MANAGE && !overview

    /** The one leave door, taken by the arrow and by Back alike. */
    private fun leave() {
        if (canReturnToOverview()) { showOverview(); return }
        finish()
    }

    private fun showOverview() {
        overview = true
        // The field belongs to a target; there is none up here, so it goes back to empty rather
        // than filtering a list it is not over. The flag above is set first on purpose — it is
        // what keeps the watcher's page reset off this clear.
        binding.input.setText("")
        page = overviewPage
        render()
    }

    /** A row of the overview was tapped: that target's own screen, which is every other mode's. */
    private fun openTarget(t: Target) {
        if (busy) return
        overviewPage = page
        target = t
        overview = false
        page = 0
        binding.input.setText("")
        render()
    }

    /**
     * MODE_ADD opens with the field ready to type into — the notebook's quick-add doors (W2) and
     * the lasso's correction dialog (W3) both land here. MANAGE never does: its overview has no
     * field, and a keyboard over a list you came to read is a keyboard in the way.
     *
     * **From `onWindowFocusChanged`, not `onResume`** (proven on the Nomad at W2): a resumed
     * Activity does not yet have window focus, and `showSoftInput` against an unfocused window is
     * dropped — the field ends up served and caret-ready with `mInputShown=false`, which reads as
     * "the keyboard is broken" rather than "it was asked for too early". The latch makes it a
     * once-per-showing act, so coming back from a dialog does not re-raise a keyboard the user
     * just dismissed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !pendingAddFocus) return
        pendingAddFocus = false
        binding.input.requestFocus()
        binding.input.setSelection(binding.input.text?.length ?: 0)
        // Flag 0, not SHOW_IMPLICIT: an implicit show is skipped when a hardware keyboard is
        // attached, and on Ratta that would strand the field with no way to type into it.
        getSystemService(InputMethodManager::class.java)?.showSoftInput(binding.input, 0)
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    private fun load() {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { readIndex() }
            if (isFinishing || isDestroyed) return@launch
            when (outcome) {
                is Read.Ok -> {
                    index = outcome.index
                    Slog.d(TAG) { "loaded: ${index.tags.size} tags, ${index.assignments.size} assignments" }
                    render()
                }
                is Read.Failed -> failAndClose(outcome.messageRes)
            }
        }
    }

    private sealed class Read {
        class Ok(val index: TagIndex) : Read()
        class Failed(val messageRes: Int) : Read()
    }

    private fun readIndex(): Read {
        val store = TagSession.store ?: return Read.Failed(R.string.tags_unavailable)
        return try {
            Read.Ok(TagStore(store).read())
        } catch (e: IndexUnreadable) {
            // Unreadable is NOT empty: nothing here may write over it.
            Slog.d(TAG) { "index unreadable" }
            Read.Failed(R.string.tags_unreadable)
        } catch (e: StoreUnavailable) {
            Slog.d(TAG) { "store unavailable: ${e.message}" }
            Read.Failed(R.string.tags_unavailable)
        }
    }

    /** Nothing can be shown and nothing can be fixed from here: explain, then leave. */
    private fun failAndClose(messageRes: Int) {
        Dialogs.confirm(this, getString(R.string.tags_problem_title), getString(messageRes)) { finish() }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun render() {
        // The whole target half stands down in the overview: there is no single target up there,
        // and six views hidden one at a time is how one of them gets missed.
        binding.targetSection.visibility = if (overview) View.GONE else View.VISIBLE
        if (overview) renderOverview() else { renderTarget(); renderList() }
    }

    /**
     * MANAGE's overview: the notebook and every page, each over the tags it carries, paged against
     * the real band like every other list on this screen. It is never empty — the notebook is
     * always a row — so there is no empty state to show.
     */
    private fun renderOverview() {
        binding.listLabel.setText(R.string.tags_manage_targets)
        binding.listEmpty.visibility = View.GONE

        bandHeightPx = binding.listBand.height
        val perPage = TagPaging.rowsPerPage(bandHeightPx, targetRowHeightPx)
        page = TagPaging.clampPage(page, manageTargets.size, perPage)
        val pageCount = TagPaging.pageCount(manageTargets.size, perPage)

        val separator = getString(R.string.tags_manage_separator)
        binding.listBand.removeAllViews()
        for (t in TagPaging.slice(manageTargets, page, perPage)) {
            val mine = index.tagsOf(t.notebookId, t.pageId)
            binding.listBand.addView(
                TagRowView.buildTarget(
                    context = this,
                    label = t.rowLabel,
                    tags = TagManage.summary(
                        tags = mine.map { it.display },
                        none = getString(R.string.tags_manage_no_tags),
                        separator = separator,
                    ),
                    onClick = { openTarget(t) },
                ),
            )
        }

        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageIndicator.text = getString(R.string.tags_page_indicator, page + 1, pageCount)
    }

    private fun renderTarget() {
        binding.targetLabel.text = target.header
        val mine = index.tagsOf(target.notebookId, target.pageId)
        binding.targetTags.removeAllViews()
        for (tag in mine) {
            binding.targetTags.addView(
                TagRowView.build(
                    context = this,
                    label = tag.display,
                    trailingIcon = R.drawable.ic_x,
                    trailingDescription = getString(R.string.cd_tags_remove),
                    onClick = { removeFromTarget(tag) },
                ),
            )
        }
        binding.targetEmpty.visibility = if (mine.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderList() {
        if (!::showing.isInitialized) return
        if (overview) { renderOverview(); return }
        val query = binding.input.text?.toString().orEmpty()
        val filtering = TagRules.display(query).isNotEmpty()
        val rows = if (filtering) index.suggest(query) else index.sortedTags()
        binding.listLabel.setText(if (filtering) R.string.tags_matching else R.string.tags_all)

        bandHeightPx = binding.listBand.height
        val perPage = TagPaging.rowsPerPage(bandHeightPx, rowHeightPx)
        page = TagPaging.clampPage(page, rows.size, perPage)
        val pageCount = TagPaging.pageCount(rows.size, perPage)

        binding.listBand.removeAllViews()
        for (tag in TagPaging.slice(rows, page, perPage)) {
            val attached = index.isAssigned(tag.id, target.notebookId, target.pageId)
            binding.listBand.addView(
                TagRowView.build(
                    context = this,
                    label = tag.display,
                    trailingIcon = if (attached) R.drawable.ic_check else R.drawable.ic_plus,
                    trailingDescription = null,
                    onClick = { if (attached) removeFromTarget(tag) else attach(tag.display) },
                    onLongClick = { confirmDelete(tag) },
                ),
            )
        }

        if (rows.isEmpty()) {
            binding.listEmpty.setText(if (filtering) R.string.tags_no_match else R.string.tags_all_empty)
            binding.listEmpty.visibility = View.VISIBLE
        } else {
            binding.listEmpty.visibility = View.GONE
        }

        // INVISIBLE, never GONE: the band must not grow and shift every row under the finger the
        // moment the tag count crosses a page boundary. The arrows never disable — a disabled
        // control is invisible on e-ink; at the ends they simply have nothing to do.
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageIndicator.text = getString(R.string.tags_page_indicator, page + 1, pageCount)
    }

    private fun turnPage(delta: Int) {
        val count: Int
        val rowHeight: Int
        if (overview) {
            count = manageTargets.size
            rowHeight = targetRowHeightPx
        } else {
            val query = binding.input.text?.toString().orEmpty()
            count = (if (TagRules.display(query).isNotEmpty()) index.suggest(query) else index.sortedTags()).size
            rowHeight = rowHeightPx
        }
        val perPage = TagPaging.rowsPerPage(binding.listBand.height, rowHeight)
        val next = TagPaging.clampPage(page + delta, count, perPage)
        if (next == page) return
        page = next
        renderList()
    }

    // ── Edits ────────────────────────────────────────────────────────────────

    /** The ⊕ / Done path: whatever is typed, normalized, created if new, attached. */
    private fun addTyped() {
        val text = binding.input.text?.toString().orEmpty()
        if (!TagRules.isValid(text)) {
            // A tap that did nothing is a dialog, never a toast — on e-ink a missed toast reads as broken.
            Dialogs.problem(this, R.string.tags_problem_title, R.string.tags_invalid)
            return
        }
        attach(text) {
            // The field is cleared only once the tag has actually landed. Focus and the keyboard
            // stay exactly as they were: nothing here ever hides the IME (the Ratta rule).
            binding.input.setText("")
            page = 0
        }
    }

    private fun attach(text: String, onDone: () -> Unit = {}) {
        // The canonical spelling is decided on IO (an existing tag keeps the casing it was first
        // entered in) and read back on Main — the coroutine hop is what publishes it.
        val display = AtomicReference(TagRules.display(text))
        edit(
            transform = { current ->
                val result = current.assign(text, target.notebookId, target.pageId)
                display.set(result.display)
                // Attaching a tag that is already there is not a failure and not a write — the
                // "nothing changed" answer still clears the field and still names the tag.
                if (result.index === current) null else result.index
            },
            onDone = {
                onDone()
                toast(getString(R.string.tags_added_toast, display.get()))
            },
        )
    }

    private fun removeFromTarget(tag: TagIndex.Tag) {
        edit(
            transform = { current -> current.unassign(tag.id, target.notebookId, target.pageId) },
            onDone = { toast(getString(R.string.tags_removed_toast, tag.display)) },
        )
    }

    /**
     * Deleting a tag reaches every notebook and page it is on, and there is no undo for a tag
     * operation — so the confirm names the size of it in the same words the removal will use.
     */
    private fun confirmDelete(tag: TagIndex.Tag) {
        if (busy) return
        val usage = index.usageOf(tag.id)
        val body = if (usage.total == 0) getString(R.string.tags_delete_unused)
        else getString(R.string.tags_delete_body, blastRadius(usage))
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.tags_delete_title, tag.display))
                .setMessage(body)
                .setPositiveButton(R.string.tags_delete_confirm) { _, _ -> deleteTag(tag) }
                .setNegativeButton(R.string.cancel, null)
                .create(),
        ).show()
    }

    /** "2 notebooks and 1 page" — whichever halves are non-zero, in that order. */
    private fun blastRadius(usage: TagIndex.Usage): String {
        val notebooks = when {
            usage.notebooks == 0 -> null
            usage.notebooks == 1 -> getString(R.string.tags_delete_notebooks, 1)
            else -> getString(R.string.tags_delete_notebooks_plural, usage.notebooks)
        }
        val pages = when {
            usage.pages == 0 -> null
            usage.pages == 1 -> getString(R.string.tags_delete_pages, 1)
            else -> getString(R.string.tags_delete_pages_plural, usage.pages)
        }
        return when {
            notebooks != null && pages != null -> getString(R.string.tags_delete_and, notebooks, pages)
            notebooks != null -> notebooks
            else -> pages.orEmpty()
        }
    }

    private fun deleteTag(tag: TagIndex.Tag) {
        edit(
            transform = { current -> current.deleteTag(tag.id) },
            onDone = { toast(getString(R.string.tags_deleted_toast, tag.display)) },
        )
    }

    /**
     * One edit, written before it is shown.
     *
     * [transform] runs on IO inside [TagSession.writes] against a **freshly read** index — never the
     * one on screen — because the service's call-shaped `assign` writes the same single value from a
     * Binder thread. It returns the index to write, or null for "nothing changed". Only after the
     * write lands does the screen adopt it, so the glass and the store can never disagree.
     */
    private fun edit(transform: (TagIndex) -> TagIndex?, onDone: () -> Unit) {
        if (busy) { Slog.d(TAG) { "edit: busy" }; return }
        busy = true
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { applyEdit(transform) }
            busy = false
            if (isFinishing || isDestroyed) return@launch
            when (outcome) {
                is TagWrites.Outcome.Written -> {
                    index = outcome.index
                    setResult(Activity.RESULT_OK)
                    render()
                    onDone()
                }
                is TagWrites.Outcome.Unchanged -> {
                    index = outcome.index
                    render()
                    onDone()
                }
                is TagWrites.Outcome.Failed -> Dialogs.problem(
                    this@TagsActivity, R.string.tags_problem_title, sentence(outcome.reason),
                )
            }
        }
    }

    private fun applyEdit(transform: (TagIndex) -> TagIndex?): TagWrites.Outcome {
        val store = TagSession.store
            ?: return TagWrites.Outcome.Failed(TagWrites.Reason.STORE_UNAVAILABLE)
        return TagWrites.apply(TagStore(store), transform)
    }

    /** A [TagWrites.Reason] in this screen's own words — the service says the same reasons to the
     *  host as marshalable messages, and neither side may read the other's wording. */
    private fun sentence(reason: TagWrites.Reason): Int = when (reason) {
        TagWrites.Reason.STORE_UNAVAILABLE -> R.string.tags_unavailable
        TagWrites.Reason.INDEX_UNREADABLE -> R.string.tags_unreadable
        TagWrites.Reason.INDEX_FULL -> R.string.tags_full
        TagWrites.Reason.NOT_A_TAG -> R.string.tags_invalid
        TagWrites.Reason.SAVE_FAILED -> R.string.tags_save_failed
    }

    // ── Small things ─────────────────────────────────────────────────────────

    /** A toast only ever confirms something that has already happened (the toast-vs-dialog rule). */
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** Every icon button names itself on a long press — words read better than glyphs on e-ink. */
    private fun hint(res: Int): Boolean {
        toast(getString(res))
        return true
    }

    private companion object {
        const val TAG = "TagsActivity"
    }
}
