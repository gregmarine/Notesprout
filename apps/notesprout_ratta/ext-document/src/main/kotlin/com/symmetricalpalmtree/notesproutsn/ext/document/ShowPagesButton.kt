package com.symmetricalpalmtree.notesproutsn.ext.document

import android.view.View
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The text document's "Show pages" button (arc 19 / M8) — the one control in this header that opens
 * something rather than closing it.
 *
 * A text document has no canvas on screen: the host routed the open straight here and never loaded
 * one. og says that ✓ Done shows the pages and Close seals to the library; SN has no ✓ (M6's user
 * call — the back arrow is the ONE leave door and every way out saves), so the "show the pages" half
 * became **its own button**, at the trailing edge, with its own glyph. The back arrow is untouched
 * and still means the library.
 *
 * **Both halves still leave this screen**, and that is the point: what differs is only what the host
 * does *after* the result lands. So the tap is two acts in a fixed order — tell the host how this
 * showing should end ([DocumentContract.CLOSE_SHOW_PAGES]), then take the ordinary leave path, which
 * flushes the buffer exactly as the back arrow's does. Nothing about saving changes here.
 *
 * **The advisory can fail and the leave still happens.** `closeNotebook` records host-side state the
 * host reads when the result arrives; if the call throws — a dead showing, a host that restarted —
 * the honest outcome is the *fail-safe* one the host already has: no advisory means to-library. A
 * writer who tapped a button must never be held on a screen because a bookkeeping call did not land.
 *
 * Visibility is [TextDocumentRules.showsPages]' and nothing else's — GONE, never disabled.
 */
internal class ShowPagesButton(
    private val binding: ActivityDocumentEditorBinding,
    /** The screen's lifecycle scope — a leave that outlives the screen has nothing to leave. */
    private val scope: CoroutineScope,
    /** The adopted state, or null before the first one has landed (when nothing is shown). */
    private val stateNow: () -> DocumentPageState?,
    /** A flip, or a Bring in / Merge, owns the buffer right now. */
    private val busy: () -> Boolean,
    /** The screen is already on its way out. */
    private val leaving: () -> Boolean,
    /** The ordinary leave path — `RESULT_OK`, flush first. Called on Main, exactly once. */
    private val leave: () -> Unit,
) {

    /** Set the moment a tap is accepted, so a double tap on e-ink cannot send two advisories. */
    private var tapped = false

    /** Listener and hint. Called once, from the Activity's chrome build. */
    fun install() {
        binding.btnShowPages.setOnClickListener { tap() }
        TooltipCompat.setTooltipText(binding.btnShowPages, binding.btnShowPages.contentDescription)
    }

    /** Draw the button for the adopted target: on screen only where [TextDocumentRules] says. */
    fun apply() {
        binding.btnShowPages.visibility = if (shows()) View.VISIBLE else View.GONE
    }

    /** The rule, over whatever state has been adopted — false before the first one, when this is
     *  not a text document, and on a page's document (where the flip cluster owns the width). */
    private fun shows(): Boolean {
        val state = stateNow() ?: return false
        return TextDocumentRules.showsPages(state.textDocument, state.scope)
    }

    /**
     * One tap — the ordinary way, and the debug hook's way. Returns whether it started, which only
     * the hook reads: a walk that thinks it opened the pages when the button was not even there
     * would report the wrong thing. Silent when refused, exactly as the button is.
     */
    fun tap(): Boolean {
        if (tapped || leaving() || busy() || !shows()) return false
        tapped = true
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    EditorSession.host?.closeNotebook(DocumentContract.CLOSE_SHOW_PAGES)
                } catch (e: Exception) {
                    // The class name only, and then onward: the host's fail-safe for a missing
                    // advisory is the library, which is a worse answer than the pages but a far
                    // better one than a screen that will not close.
                    Slog.d(TAG) { "close advisory failed: ${e.javaClass.simpleName}" }
                }
            }
            leave()
        }
        return true
    }

    private companion object {
        const val TAG = "DocumentEditor"
    }
}
