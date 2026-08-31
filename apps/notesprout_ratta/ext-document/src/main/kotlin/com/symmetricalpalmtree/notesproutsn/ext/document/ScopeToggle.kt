package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import android.view.View
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding

/**
 * The header's scope toggle (arc 19 / M7) — the one control that moves the editor between **this
 * page's document** and **the notebook's one merged final draft**.
 *
 * **The icon names where the tap goes, not where you are.** Standing on a page it shows a notebook
 * ("Notebook document"); standing on the notebook document it shows a page ("Page document"). A
 * two-state control that showed its own state would need a second control to explain it, and this
 * header has no room for one. Every swap re-sets the tooltip as well as the content description —
 * `TooltipCompat` snapshots the text it was given, so a button whose description changed under it
 * would long-press with yesterday's word.
 *
 * It is **always visible** — both scopes, Write and Preview. It is how the reader gets back, and a
 * way back that disappears in one of the two places is not a way back. What *does* go is the page
 * cluster: in the notebook scope `‹ n / m ›` numbers nothing, so the arrows and the indicator are
 * **GONE** rather than disabled — a disabled control is visually silent on e-ink, and this app's
 * rule everywhere is to remove rather than grey out. (The `Ctrl+PgUp` / `Ctrl+PgDn` chords need no
 * change: [FlipRules] already answers BLOCKED for a target with no page index, and the chord stays
 * consumed so it never leaks to the IME.)
 *
 * The switch itself is [PageFlipController.switchScope] — a page flip in every way that matters —
 * and whether it may run at all is [ScopeRules.mayToggle]. Nothing here decides either.
 */
internal class ScopeToggle(
    private val activity: Activity,
    private val binding: ActivityDocumentEditorBinding,
    /** The adopted state's scope, or null before the first one has landed. */
    private val scopeNow: () -> Int?,
    /** A flip, or a Bring in / Merge, owns the buffer right now. */
    private val busy: () -> Boolean,
    /** The screen is on its way out. */
    private val leaving: () -> Boolean,
    /** Chrome that must go before the buffer is swapped — the overflow panel. */
    private val onTapped: () -> Unit,
    /** Run the switch to the given scope. */
    private val switchTo: (Int) -> Unit,
) {

    /** Listener and hint. Called once, from the Activity's chrome build. */
    fun install() {
        binding.btnScope.setOnClickListener {
            onTapped()
            tap()
        }
        TooltipCompat.setTooltipText(binding.btnScope, binding.btnScope.contentDescription)
    }

    /** One tap on the toggle — the ordinary way, and the debug hook's way. Silent when it may not
     *  run: nothing has been said about a tap that was never allowed. */
    fun tap() {
        val scope = scopeNow()
        if (!ScopeRules.mayToggle(busy(), leaving(), scope != null)) return
        switchTo(ScopeRules.other(scope!!))
    }

    /** Draw the header for a target in [scope] — the toggle's face, and the page cluster's fate. */
    fun apply(scope: Int) {
        val notebook = ScopeRules.isNotebook(scope)
        binding.btnScope.setImageResource(
            if (notebook) R.drawable.ic_file_text else R.drawable.ic_notebook,
        )
        val hint = activity.getString(
            if (notebook) R.string.cd_document_scope_page else R.string.cd_document_scope_notebook,
        )
        binding.btnScope.contentDescription = hint
        TooltipCompat.setTooltipText(binding.btnScope, hint)

        // GONE, never disabled: the notebook document is not a page, and there is nothing to number
        // or to walk. `pageIndicator` already empties itself for a −1 index; taking its space back
        // as well keeps the header from carrying a hole where the count was.
        val pages = if (notebook) View.GONE else View.VISIBLE
        binding.btnPagePrev.visibility = pages
        binding.pageIndicator.visibility = pages
        binding.btnPageNext.visibility = pages
    }
}
