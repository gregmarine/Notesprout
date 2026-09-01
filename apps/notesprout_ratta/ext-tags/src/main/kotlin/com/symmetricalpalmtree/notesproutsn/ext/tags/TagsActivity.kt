package com.symmetricalpalmtree.notesproutsn.ext.tags

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
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

        val parked = TagSession.showing
        if (parked == null || TagSession.store == null) {
            // The host never launched us, or it revoked the bind before we came up. Say so — a
            // screen that opens empty and does nothing reads as broken.
            Slog.d(TAG) { "no showing parked — refusing" }
            Dialogs.problem(this, R.string.tags_problem_title, R.string.tags_unavailable)
            finish()
            return
        }
        showing = parked

        binding.title.text = showing.targetLabel
        binding.targetLabel.setText(
            if (showing.targetKind == TagShowing.TARGET_NOTEBOOK) R.string.tags_on_notebook
            else R.string.tags_on_page,
        )
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnLongClickListener { hint(R.string.cd_tags_back) }
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
            page = 0
            renderList()
        }
        showing.prefill?.let { binding.input.setText(it) }

        // The page size is what actually FITS: rows are measured against the real band, and the
        // band shrinks when the IME opens (adjustResize). Re-render only when the height moves.
        // Posted, not called inline: this fires from inside a layout pass, and the render adds and
        // removes the band's own children.
        binding.listBand.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop || bandHeightPx < 0) binding.listBand.post { renderList() }
        }

        load()
    }

    /** MODE_ADD opens with the field ready to type into — the notebook's quick-add doors (W2) and
     *  the lasso's correction dialog (W3) both land here. */
    override fun onResume() {
        super.onResume()
        if (!::showing.isInitialized) return
        if (showing.mode == TagShowing.MODE_ADD && !binding.input.hasFocus()) {
            binding.input.requestFocus()
            binding.input.setSelection(binding.input.text?.length ?: 0)
            // Flag 0, not SHOW_IMPLICIT: an implicit show is skipped when a hardware keyboard is
            // attached, and on Ratta that would strand the field with no way to type into it.
            getSystemService(InputMethodManager::class.java)?.showSoftInput(binding.input, 0)
        }
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
        renderTarget()
        renderList()
    }

    private fun renderTarget() {
        val mine = index.tagsOf(showing.targetKind, showing.targetId)
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
            val attached = index.isAssigned(tag.id, showing.targetKind, showing.targetId)
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
        val query = binding.input.text?.toString().orEmpty()
        val rows = if (TagRules.display(query).isNotEmpty()) index.suggest(query) else index.sortedTags()
        val perPage = TagPaging.rowsPerPage(binding.listBand.height, rowHeightPx)
        val next = TagPaging.clampPage(page + delta, rows.size, perPage)
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
                val result = current.assign(text, showing.targetKind, showing.targetId)
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
            transform = { current -> current.unassign(tag.id, showing.targetKind, showing.targetId) },
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
