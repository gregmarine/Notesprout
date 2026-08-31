package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract

/**
 * The decision core of the document editor's host hooks (arc 19 / M6) — **pure, no Android, no
 * `.soil`**, precisely so every rule below is provable off-device. [DocumentHostHooks] needs an
 * open [NotebookSession] and therefore cannot be constructed in a JVM test at all; what it must
 * get right is exactly what lives here, and it routes through these functions rather than
 * restating them (the [com.symmetricalpalmtree.notesproutsn.extension.DocumentHostSession] recipe:
 * the untestable shell stays thin over a tested core).
 *
 * Four rules, each pinned by test:
 *  - **Which page the editor is on** ([resolveTarget]). The target is the host's own memory of
 *    where the editor flipped to; the displayed page is the fallback whenever that memory is
 *    absent or names a page that is no longer in the notebook (deleted under the editor, or a
 *    stale id restored from saved state).
 *  - **What the source strip says** ([source]) — M2's staleness comparison, unchanged.
 *  - **Where a flip lands** ([flipIndex]) — bounds only; there is no wrap, because the first and
 *    last page are where the editor's arrows say "First page" / "Last page".
 *  - **What the read window is loaded with** ([openDecision] / [flipDecision]): a stored document,
 *    or a **fresh draft the host has not stored** ([Serve.Seed] — the `seeded = true` answer whose
 *    watermark the host parks and whose text becomes real only when the editor saves it).
 *
 * The two decisions are deliberately separate functions rather than one with a flag: opening
 * consumes a seed the host staged *before* the launch (the notebook ran recognition with the user
 * watching, behind "Reading this page…"), while a flip recognizes inline, silently, behind a
 * stopped host. Same outcome type, different inputs — and a reader can see both tables at once.
 */
object DocumentTargetRules {

    /**
     * What the host loads its read window with, and how the answer describes it.
     *
     * [Serve.Stored] is the stored document — `seeded = false`, and the source strip follows the
     * row's own watermark ([source]). An absent document is `Stored("")`, which is the same thing
     * said about nothing: an empty window and [DocumentContract.SOURCE_NONE].
     *
     * [Serve.Seed] is a draft that exists **only in the window** — `seeded = true`,
     * [DocumentContract.SOURCE_DRAFTED], and the host parks the watermark it read *before*
     * recognizing. Nothing is written; the editor's next drafted save is what makes it real.
     */
    sealed class Serve {
        abstract val text: String
        data class Stored(override val text: String) : Serve()
        data class Seed(override val text: String) : Serve()
    }

    /**
     * The page the editor's target names — [target] when it is still one of [pageIds], otherwise
     * the page the user is looking at. A null target is the first `current()` of a showing (or one
     * whose saved state carried nothing); a target outside [pageIds] is a page deleted while the
     * editor was up, and falling back is the only honest answer left.
     */
    fun resolveTarget(target: String?, pageIds: List<String>, displayed: String): String =
        if (target != null && target in pageIds) target else displayed

    /**
     * The source strip's state for a document with watermark [docWatermark], against the page's
     * content maximum [pageMax] now. Never drafted (or no document) is
     * [DocumentContract.SOURCE_NONE]; a page that has moved on since the draft is
     * [DocumentContract.SOURCE_STALE]; anything else still describes the page.
     */
    fun source(docWatermark: Long?, pageMax: Long): Int = when {
        docWatermark == null -> DocumentContract.SOURCE_NONE
        pageMax > docWatermark -> DocumentContract.SOURCE_STALE
        else -> DocumentContract.SOURCE_DRAFTED
    }

    /**
     * Where a [DocumentContract.PAGE_PREV] / [DocumentContract.PAGE_NEXT] flip from [index] lands,
     * or null when there is no page in that direction. No wrap: the editor's arrows stay visible
     * and say "First page" / "Last page" rather than going around (and never `isEnabled = false` —
     * a disabled control is invisible on e-ink).
     *
     * An [index] outside the notebook is null too — a caller that could not place its own target
     * has nothing to flip from.
     */
    fun flipIndex(index: Int, direction: Int, pageCount: Int): Int? {
        if (index < 0 || index >= pageCount) return null
        val next = index + direction
        return if (next in 0 until pageCount) next else null
    }

    /**
     * Opening the editor. [docText] is the stored document (null when there is none, or when it
     * holds only blank text — the repository's blank-means-absent rule), and [stagedPageId] /
     * [stagedText] are the seed the notebook staged at the tap.
     *
     * The staged seed is served only when **all three** hold: there is no stored document to
     * overwrite, the stage names this very page, and it actually recognized something. Anything
     * else serves the stored document — including a stage left over from a page the editor is no
     * longer on, which is why the staged id is checked here rather than trusted.
     *
     * "Seed once" needs no flag of its own: a document exists ⇒ it is served, and only a blank
     * (absent) one can be seeded again.
     */
    fun openDecision(
        docText: String?,
        targetPageId: String,
        stagedPageId: String?,
        stagedText: String?,
    ): Serve {
        if (!docText.isNullOrBlank()) return Serve.Stored(docText)
        if (stagedPageId != null && stagedPageId == targetPageId && !stagedText.isNullOrBlank()) {
            return Serve.Seed(stagedText)
        }
        return Serve.Stored(docText.orEmpty())
    }

    /**
     * Flipping to a page. [docText] is that page's stored document; [recognized] is what
     * recognition answered for an **undocumented** page — null when recognition could not run at
     * all (no extension, model not ready, the call failed) and "" when it ran and the page had
     * nothing to give.
     *
     * A documented page serves its document. An undocumented one is seeded exactly like opening
     * one — and when there is nothing to seed with, the flip still lands on an empty window and
     * the page stays seedable. A failed recognition never blocks a flip.
     */
    fun flipDecision(docText: String?, recognized: String?): Serve {
        if (!docText.isNullOrBlank()) return Serve.Stored(docText)
        if (recognized.isNullOrBlank()) return Serve.Stored("")
        return Serve.Seed(recognized)
    }
}
