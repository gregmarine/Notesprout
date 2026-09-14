package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Bible from a selection (arc 39 "Lookup", the user's decision 2026-09-13): select "John 3:16"
 * in Write **or** in Preview, tap **Bible** in the text-selection toolbar, and NSE · Bible opens on
 * those verses over this screen; Back lands here with the caret, the undo stack and any unsaved
 * text exactly as they were.
 *
 * **The item lives in the system selection toolbar** — the one the writer already gets for Copy
 * and Paste — added through `setCustomSelectionActionModeCallback` on both surfaces, so it exists
 * only while text is selected (decision 4: selection only, no caret guessing). The toolbar is the
 * ROM's chrome and is not restyled. It is installed **only when the host offered it**
 * ([DocumentContract.EXTRA_DOCUMENT_BIBLE_AVAILABLE] on the Intent — the host's own discovery of
 * a reader that understands references; this extension never queries for another): without the
 * offer there is no item at all, never a disabled one.
 *
 * **The editor cannot open the Bible itself** — the reader admits only the host — so the tap is
 * two steps. First it asks the host over the callback binder (`IDocumentHost.openReference`): the
 * host resolves the words through the reader and parks the answer. That call is synchronous, and
 * the host can paint nothing, so this screen holds a "Opening the Bible…" dialog up for its life
 * ([ReadingPopup], no Cancel: a Binder call cannot be taken back). Then, on `REFERENCE_OPENED`,
 * it starts the host's **lookup screen** ([DocumentContract.ACTION_DOCUMENT_LOOKUP_SCREEN], for a
 * result, nothing on the Intent) — a live host screen that opens the reader over this one and
 * finishes when the reader does; the notebook itself is stopped under here and would not see a
 * child's result until this screen closed. The answer is a code, worded here: a "no" from the
 * reader is an **alert with an OK, never a toast** (decision 3 — the writer needs time to read
 * it), and so is a reader that could not be opened — whether the host said so or the lookup screen
 * came back with [DocumentContract.RESULT_LOOKUP_FAILED].
 *
 * Nothing selected, prepared or answered is logged — lengths and codes only.
 */
internal class LookupAction(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val editor: TextView,
    private val preview: TextView,
    /** Starts the host's lookup screen for a result; its callback is [onLookupScreenReturned].
     *  Registered by the screen in `onCreate` — a launcher may not be registered after STARTED. */
    private val lookupScreen: ActivityResultLauncher<Intent>,
) {

    private val wait = ReadingPopup(activity)

    /** Latched at the tap, released when the answer lands — e-ink gives a tap no feedback for
     *  hundreds of ms, and the second tap must not start a second showing. */
    private var inFlight = false

    /** Whether the host offered the door — the automation path is silent without it, exactly as
     *  the toolbar has no item. */
    private var installed = false

    /** Put the item on both surfaces' selection toolbars. Called once, and only when offered. */
    fun install() {
        installed = true
        editor.customSelectionActionModeCallback = callbackFor(editor)
        preview.customSelectionActionModeCallback = callbackFor(preview)
        Slog.d(TAG) { "installed on both surfaces" }
    }

    /** Take the wait down — the screen is going. */
    fun close() = wait.hide()

    /**
     * The one path (also the debug automation's `lookup`): prepare, ask, word the answer. [raw] is
     * the selection as the view gave it.
     */
    fun lookup(raw: CharSequence?) {
        if (!installed) { Slog.d(TAG) { "lookup: not offered" }; return }
        if (inFlight) { Slog.d(TAG) { "lookup: already in flight" }; return }
        val text = LookupText.prepare(raw)
        if (text == null) {
            Slog.d(TAG) { "lookup: nothing worth asking (${raw?.length ?: 0} chars)" }
            unknown(raw?.toString().orEmpty().trim().let(LookupText::preview))
            return
        }
        inFlight = true
        wait.show(R.string.document_lookup_opening)
        scope.launch {
            val code = withContext(Dispatchers.IO) {
                try {
                    val host = EditorSession.host ?: throw IllegalStateException("no showing")
                    host.openReference(text)
                } catch (e: Exception) {
                    Slog.d(TAG) { "lookup failed: ${e.javaClass.simpleName}" }
                    DocumentContract.REFERENCE_UNAVAILABLE
                }
            }
            inFlight = false
            wait.hide()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            Slog.d(TAG) { "lookup: ${text.length} chars → code $code" }
            when (code) {
                DocumentContract.REFERENCE_OPENED -> startLookupScreen()
                DocumentContract.REFERENCE_UNKNOWN -> unknown(text)
                else -> unavailable()
            }
        }
    }

    /** The second step: the host's own screen, which walks the reader's door and returns when the
     *  reader has closed. Package-pinned, so no other app's screen can answer the action. */
    private fun startLookupScreen() {
        val intent = Intent(DocumentContract.ACTION_DOCUMENT_LOOKUP_SCREEN).setPackage(BuildConfig.HOST_PACKAGE)
        try {
            lookupScreen.launch(intent)
            Slog.d(TAG) { "lookup screen started" }
        } catch (e: ActivityNotFoundException) {
            Slog.d(TAG) { "lookup screen missing — the host is older than its offer" }
            unavailable()
        }
    }

    /** The lookup screen came back: the reader was shown and closed (nothing to do — this screen is
     *  exactly as it was), or the reader could not be opened, which is the writer's to hear. */
    fun onLookupScreenReturned(result: ActivityResult) {
        Slog.d(TAG) { "lookup screen returned: resultCode=${result.resultCode}" }
        if (activity.isFinishing || activity.isDestroyed) return
        if (result.resultCode == DocumentContract.RESULT_LOOKUP_FAILED) unavailable()
    }

    private fun unavailable() {
        Dialogs.problem(activity, R.string.document_lookup_unavailable_title, R.string.document_lookup_unavailable_body)
    }

    /** The reader's "no" — and a selection not worth asking about, which reads the same. */
    private fun unknown(words: String) {
        Dialogs.problem(
            activity,
            R.string.document_lookup_unknown_title,
            activity.getString(R.string.document_lookup_unknown_body, words),
        )
    }

    private fun callbackFor(view: TextView) = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // After the system's own items (Cut / Copy / Paste sit in the low orders): a quick jump
            // belongs beside them, not ahead of them.
            menu.add(Menu.NONE, ITEM_ID, ORDER, R.string.document_lookup_action)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != ITEM_ID) return false
            val text = view.text ?: return true
            val start = view.selectionStart
            val end = view.selectionEnd
            val selected = if (start in 0..text.length && end in 0..text.length && start != end) {
                text.subSequence(minOf(start, end), maxOf(start, end))
            } else null
            mode.finish()
            lookup(selected)
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }

    private companion object {
        const val TAG = "DocumentLookup"
        const val ITEM_ID = 0x4C4B    // 'LK'
        const val ORDER = 100
    }
}
