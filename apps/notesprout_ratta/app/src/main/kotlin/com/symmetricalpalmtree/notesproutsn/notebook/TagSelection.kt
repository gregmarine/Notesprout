package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.TagRules

/** What tapping the selection toolbar's Tag button does with what is currently lassoed (arc 21 / W3). */
enum class TagFlow {
    /** Not offered — the button is GONE for this selection. */
    NONE,

    /** Exactly one heading: its own text becomes a page tag with no screen and no question. */
    SILENT,

    /** Ink alone: recognize it, then open the tag screen with the result to correct. */
    RECOGNIZE,
}

/**
 * The lasso's Tag button, decided (arc 21 / W3) — which selections are offered it, which of the two
 * flows a tap takes, and what text is fit to carry into either. Pure, so the rule is a table a test
 * can read rather than a chain of `if`s inside a 2 800-line Activity.
 *
 * **The rule, in one sentence: exactly one heading is silent, a selection with no content objects at
 * all is recognized, and anything else is not offered** (the user's W3 call, taken over the planner's
 * "recognize whatever is in there"). The reason is that a mixed selection has two answers and no way
 * to ask which one is meant: a heading already carries the text a tag would be made of, while the ink
 * beside it carries different text, and re-recognizing the heading's strokes could come back with
 * something other than the words on the glass. A button that quietly picks one of those is worse than
 * a button that is not there.
 *
 * A link-bearing selection is not offered either, for the same reason — a link is content with a
 * payload, not ink — which makes the offered set exactly [SelectionMode.HEADING] and
 * [SelectionMode.STROKES].
 *
 * **What is not gated here: the recognizer.** The Tag button stands or falls with the *tag* extension
 * only. A missing recognizer is explained by the same problem dialog the H button beside it already
 * gives (the user's W3 call) — H and Tag sit in the same bar and both go out through the recognizer,
 * so one vanishing while the other stayed would read as a bug rather than as a rule.
 */
object TagSelection {

    /** Which flow [mode] takes, [TagFlow.NONE] when the button is not offered at all. */
    fun flowFor(mode: SelectionMode): TagFlow = when (mode) {
        SelectionMode.HEADING -> TagFlow.SILENT
        SelectionMode.STROKES -> TagFlow.RECOGNIZE
        SelectionMode.LINK, SelectionMode.MIXED, SelectionMode.MIXED_WITH_LINK -> TagFlow.NONE
    }

    /** Whether the bar shows Tag: a flow to take, and a tag manager installed to take it to. */
    fun offered(mode: SelectionMode, tagsAvailable: Boolean): Boolean =
        tagsAvailable && flowFor(mode) != TagFlow.NONE

    /**
     * Whether [text] can be attached as it stands — the silent flow's gate. False sends the heading
     * flow to the screen instead of the toast, because a tap that can do nothing must still land
     * somewhere the user can finish the job.
     */
    fun isTag(text: String): Boolean = TagRules.isValid(text)

    /**
     * [text] as a starting point for the add field: normalized the way a tag is
     * ([TagRules.display]) and cut to [ExtensionContract.MAX_TAG_CHARS], or **null** when nothing is
     * left of it.
     *
     * The cut is not a silent truncation of a tag — it is a prefill the user is about to read and
     * edit, and a `TagShowing` whose prefill is over the cap does not marshal at all (its
     * constructor refuses), so the alternative to cutting is a crash on the way to the screen.
     *
     * A cut never splits a surrogate pair: the [ExtensionContract.MAX_TAG_CHARS]th char being a high
     * surrogate means its partner is the one being dropped, so the pair goes together (the
     * `TextChunks` backoff, one character wide).
     */
    fun prefill(text: String): String? {
        val display = TagRules.display(text)
        if (display.isEmpty()) return null
        if (display.length <= ExtensionContract.MAX_TAG_CHARS) return display
        var end = ExtensionContract.MAX_TAG_CHARS
        if (display[end - 1].isHighSurrogate()) end--
        return display.substring(0, end).takeIf { it.isNotEmpty() }
    }
}
