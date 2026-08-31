package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract

/**
 * The text document's two chrome decisions (arc 19 / M8) — **pure Kotlin**, for [FlipRules]' and
 * [ScopeRules]' reason: each of them shows itself on a device as a control that is there when it
 * should not be, or missing when it is the only way onward.
 *
 * A **text document** is a notebook the library flagged as one: it opens straight into this editor
 * rather than onto paper, and it is the only kind of notebook whose name the editor may change.
 * Every other notebook reaching this screen came through the notebook's own Document button, and its
 * name belongs to the library.
 *
 * - **Show pages** ([showsPages]) — the text-document-only exit to the canvas, and the *only* thing
 *   in this header that is not a leave door: the back arrow still means "to the library" (M6's rule,
 *   which text documents do not get an exception from). It appears in **either scope** (the user's
 *   2026-08-31 checklist call, and og parity — og's ✓ was show-pages in both scopes; only og's
 *   extra Close was notebook-mode-only, a slot SN's back arrow already fills): a page document's
 *   header holds the extra 62 dp beside the flip cluster on the narrowest device, and the canvas
 *   lands on the page the editor is on either way. Hiding rather than disabling is this app's rule
 *   everywhere — a disabled control is visually silent on e-ink.
 * - **Rename** ([offersRename]) — the header title becomes tappable, in **either** scope: the name
 *   being edited is the notebook's, not the target's, so which document is on screen is irrelevant
 *   to it.
 */
object TextDocumentRules {

    /**
     * Whether the "Show pages" button is on screen.
     *
     * @param textDocument the host says this notebook is a text document.
     * @param scope the adopted target's scope — carried so a scope change re-decides the chrome,
     *   though both scopes answer the same way (see the class doc's both-scopes note).
     */
    fun showsPages(textDocument: Boolean, scope: Int): Boolean =
        textDocument &&
            (scope == DocumentContract.SCOPE_NOTEBOOK || scope == DocumentContract.SCOPE_PAGE)

    /** Whether the header title may be tapped to rename the notebook. */
    fun offersRename(textDocument: Boolean): Boolean = textDocument

    /**
     * Whether a typed name is worth asking the host about at all.
     *
     * Blank is a no-op and so is the name it already has: neither is a refusal worth a dialog, and
     * both dismiss silently. Everything else — the charset, the siblings, the reserved words — is
     * the **host's** to judge, because the host is the only side that can see the other notebooks.
     */
    fun renameWorthAsking(typed: String, current: String): Boolean {
        val name = typed.trim()
        return name.isNotEmpty() && name != current.trim()
    }
}
