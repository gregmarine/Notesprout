package com.symmetricalpalmtree.notesproutsn.notebook

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.launch

/**
 * The notebook screen's side of the link picker (arc 6 / K2): launches [LinkPickerActivity] for a
 * create (the toolbar's Link on a wrappable selection) or an edit (Edit on a lone link), and
 * applies the payload it returns. Lives beside [NotebookActivity] rather than in it — the Paper
 * `LinkFlow` precedent, and the screen file is already at its documented size cap.
 *
 * What crosses where: the current notebook's pages reach the picker through [LinkPickerRelay]
 * (armed here, after one `drain()` so previews see the ink the user just wrote); the Intent
 * carries only the edit prefill payload (ids, never content); the result carries only the
 * composed payload. Everything the *application* of a result needs — the wrapped selection, the
 * edited link — is captured **here at launch**: the selection may not survive the round trip, and
 * the captured copy is what the user pointed at (the heading-convert discipline).
 *
 * K3 adds one thing crossing the other way: the picker may **create** a page in the current
 * notebook, which it cannot do itself (the `.soil` is open here). The relay carries a creator armed
 * with [createPage], run under the host's page-op lock; a page that lands makes every `Structural`
 * undo snapshot describe a page list that no longer exists, so the return calls [onPagesChanged].
 *
 * One door in ([busy]) — released at the **top** of the result callback, which runs before
 * `onResume` (the S2 latch trap). A host process death while the picker showed loses the pending
 * capture with the process; the redelivered result then finds nothing to apply and says so
 * honestly (the Paper `links_result_lost` shape) rather than applying a payload to a guess.
 */
class LinkPickFlow(
    private val activity: AppCompatActivity,
    private val session: () -> NotebookSession,
    private val displayedPageId: () -> String,
    /** Apply a create: the captured selection + the picker's payload → wrap (K1's path). */
    private val applyCreate: (Selection, String) -> Unit,
    /** Apply an edit: link id, payload before, payload after (already known unequal). */
    private val applyEdit: (String, String, String) -> Unit,
    /** K3: create a page in the current notebook, host-side, under its page-op lock. */
    private val createPage: suspend (anchorPageId: String?, before: Boolean) -> PageRef?,
    /**
     * K3: the current notebook's page structure changed while the picker was up. The host clears
     * its undo stack — every `Structural` snapshot in it describes a page list that no longer
     * exists — and refreshes the page indicator and the Contents gate.
     */
    private val onPagesChanged: () -> Unit,
) {

    private var busy = false
    private var pendingCreate: Selection? = null
    private var pendingEdit: PageLink? = null

    /** Set by the armed creator when a page really landed in the current notebook; the *only*
     *  thing that decides whether [onPagesChanged] fires on the way back. */
    private var pagesChanged = false

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            busy = false                       // before anything can bail — the S2 trap
            LinkPickerRelay.showing = null
            // Before the RESULT_OK check, so a cancel clears the stale snapshots too, and before
            // applyCreate, so the new link's LinkCreated survives the clear and stays undoable.
            if (pagesChanged) { pagesChanged = false; onPagesChanged() }
            val create = pendingCreate.also { pendingCreate = null }
            val edit = pendingEdit.also { pendingEdit = null }
            val payload = result.data?.getStringExtra(LinkPickerActivity.EXTRA_RESULT_PAYLOAD)
            if (result.resultCode != Activity.RESULT_OK || payload.isNullOrEmpty()) return@registerForActivityResult
            when {
                create != null -> applyCreate(create, payload)
                edit != null ->
                    // Unchanged payload = no-op: no store write, no undo step (chrome rides
                    // inside the payload, so equality covers both target and style).
                    if (payload != edit.payload) applyEdit(edit.id, edit.payload, payload)
                else -> {
                    // The process was rebuilt under the picker: the capture died with it.
                    Slog.d(TAG) { "result with nothing pending — host recreated mid-showing" }
                    Dialogs.problem(activity, R.string.link_result_lost_title, activity.getString(R.string.link_result_lost_body))
                }
            }
        }

    /** Link on a wrappable selection → the picker in create shape. */
    fun beginCreate(selection: Selection) = begin(selection, null)

    /** Edit on a lone link → the picker prefilled with its payload. */
    fun beginEdit(link: PageLink) = begin(null, link)

    /** Drop the relay on the screen's way out — the source closes over the session being sealed. */
    fun close() {
        LinkPickerRelay.showing = null
    }

    private fun begin(selection: Selection?, edit: PageLink?) {
        if (busy) return
        busy = true
        activity.lifecycleScope.launch {
            val s = session()
            // Previews read rows; the stroke the user just lifted the pen from may still be queued.
            runCatching { s.store.drain() }
            pendingCreate = selection
            pendingEdit = edit
            pagesChanged = false
            LinkPickerRelay.showing = LinkPickerRelay.Showing(
                notebookId = s.notebookId,
                currentPageId = displayedPageId(),
                source = sessionSource(s),
                createPage = ::createCurrentPage,
            )
            launcher.launch(LinkPickerActivity.intent(activity, edit?.payload))
        }
    }

    /**
     * The picker's New page in the current notebook (K3), as the relay hands it out: the host does
     * the insert under its page-op lock, and a page that really landed arms [pagesChanged] so the
     * return clears the host's now-stale `Structural` undo snapshots.
     */
    private suspend fun createCurrentPage(anchorPageId: String?, before: Boolean): PickerPage? {
        val page = createPage(anchorPageId, before) ?: return null
        pagesChanged = true
        return PickerPage(page.id, page.width, page.height)
    }

    /** The current notebook through its live session — never a second open of its `.soil`. */
    private fun sessionSource(s: NotebookSession): PickerPageSource = object : PickerPageSource {
        override suspend fun pages(): List<PickerPage> =
            s.pages.map { PickerPage(it.id, it.width, it.height) }

        override suspend fun content(pageId: String): PageContent? =
            if (s.isOpen) PageReads.content(s.db.dao(), pageId) else null
    }

    private companion object {
        const val TAG = "LinkPickFlow"
    }
}
