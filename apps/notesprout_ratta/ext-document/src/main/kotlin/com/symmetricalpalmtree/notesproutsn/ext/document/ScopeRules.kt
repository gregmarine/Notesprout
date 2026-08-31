package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract

/**
 * The notebook document's decision table (arc 19 / M7) — **pure Kotlin**, for [FlipRules]' reason:
 * every rule here shows itself on a device as a line that reads wrong, a button that says the wrong
 * word, or — the dangerous one — a buffer pushed under the wrong key. None of them may live only
 * inside a view.
 *
 * Four decisions, and they are deliberately small:
 *
 * - **May the scope toggle run** ([mayToggle]) — a scope switch is a page flip in every way that
 *   matters (it pushes the outgoing text, moves the host's target and swaps the buffer), so it takes
 *   the same guards a flip does.
 * - **Which scope a tap goes to** ([other]) — the toggle is a two-state control and the icon names
 *   where the tap *goes*, not where the reader is.
 * - **What the source strip says** ([provenance]) — the same three host answers read differently in
 *   the two scopes, and one of the six is deliberately silent.
 * - **Whether a merge result may touch the buffer** ([mergeLands]) — an honest EMPTY window from a
 *   notebook whose pages had nothing to give must not blank a hand-authored document.
 *
 * Plus the guard that makes the mode routing structural on this side of the seam
 * ([restoredBufferApplies]).
 */
object ScopeRules {

    /**
     * Whether a scope toggle may start.
     *
     * @param busy a flip, or a Bring in / Merge, is already running — its buffer is not ours.
     * @param leaving the screen is on its way out; a switch would push into a closing showing.
     * @param hasTarget a state has been adopted, so there is a scope to switch *from*.
     */
    fun mayToggle(busy: Boolean, leaving: Boolean, hasTarget: Boolean): Boolean =
        !busy && !leaving && hasTarget

    /** The scope a tap on the toggle goes to, from the one currently shown. */
    fun other(scope: Int): Int =
        if (scope == DocumentContract.SCOPE_NOTEBOOK) {
            DocumentContract.SCOPE_PAGE
        } else {
            DocumentContract.SCOPE_NOTEBOOK
        }

    /** True for [DocumentContract.SCOPE_NOTEBOOK] — the one merged draft, not a page. */
    fun isNotebook(scope: Int): Boolean = scope == DocumentContract.SCOPE_NOTEBOOK

    /**
     * What the source strip's line says for a host `source` answer in a scope.
     *
     * The notebook scope's "no relationship" answer is [SourceLine.SILENT] rather than a line of its
     * own: the notebook document exists because the writer asked for it, and telling them it was not
     * merged from the pages would be noise about a thing they did on purpose (og). The **page**
     * scope keeps its "Not drafted from this page", which is the opposite situation — a page's
     * document is normally drafted, and its absence is worth naming.
     */
    fun provenance(scope: Int, source: Int): SourceLine = if (isNotebook(scope)) {
        when (source) {
            DocumentContract.SOURCE_DRAFTED -> SourceLine.MERGED
            DocumentContract.SOURCE_STALE -> SourceLine.MERGE_STALE
            else -> SourceLine.SILENT
        }
    } else {
        when (source) {
            DocumentContract.SOURCE_DRAFTED -> SourceLine.DRAFTED
            DocumentContract.SOURCE_STALE -> SourceLine.STALE
            else -> SourceLine.NONE
        }
    }

    /**
     * Whether a merge / Bring in result may be applied to the buffer at all.
     *
     * In the **notebook** scope the host can answer honestly with an empty window: the pages had
     * nothing to give (no documents, nothing recognizable). Applying a Replace then would blank a
     * document the writer may have authored entirely by hand, in exchange for nothing — so a blank
     * merge is a **silent no-op**, og's rule, and the strongest reason this function exists.
     *
     * The **page** scope is unchanged from M6: Bring in applies whatever came back, and an empty
     * seed simply fails to claim provenance ([DocumentSaver.adoptWindow]'s "an empty seed is not a
     * draft").
     */
    fun mergeLands(scope: Int, text: String): Boolean = !isNotebook(scope) || text.isNotBlank()

    /**
     * **The mode-routing guard's editor half.** A recreated screen wakes up holding a buffer, a
     * caret and possibly a draft claim from *some* target; whether that target is the one the fresh
     * load landed on is not something the screen may assume. A page document and the notebook
     * document are two different rows behind two different keys, and a process death between a
     * scope switch and its first save is exactly when they diverge.
     *
     * So: adopt the bundle only when the keys match on the nose. A missing key (an older bundle, or
     * one written before the load ever landed) is a mismatch — there is nothing to match against,
     * and dropping costs at worst one debounce of typing.
     */
    fun restoredBufferApplies(restoredKey: String?, loadedKey: String): Boolean =
        restoredKey != null && restoredKey == loadedKey

    /** The six lines the strip can draw — five words and one deliberate silence. */
    enum class SourceLine {
        /** Page scope: drafted from this page and unchanged since. */
        DRAFTED,

        /** Page scope: drafted, but the page has been written on since. */
        STALE,

        /** Page scope: no draft relationship — authored by hand, or empty. */
        NONE,

        /** Notebook scope: merged from this notebook's pages. */
        MERGED,

        /** Notebook scope: merged, but the pages have changed since. */
        MERGE_STALE,

        /** Notebook scope with no merge behind it: the strip says nothing at all. */
        SILENT,
    }
}
