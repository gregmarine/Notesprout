package com.symmetricalpalmtree.notesproutsn.extension

import java.util.Locale

/**
 * What makes two pieces of typed text **the same tag** (arc 21 / W1). Pure, stdlib only, shared by
 * both sides of the seam and by every test — the host validates before it asks, the extension
 * validates again on the way in, and they must agree to the character.
 *
 * The wizard's rule, spelled out: **trim the ends, collapse internal whitespace runs to one space,
 * fold case.** "  Reading   List " and "reading list" are one tag. Nothing else is restricted —
 * multi-word tags are the point of the feature, punctuation and digits are ordinary text, and the
 * only bound is [ExtensionContract.MAX_TAG_CHARS], measured on the **display** form.
 *
 * Two forms, and the difference matters:
 *  - [display] — what a person sees and what is stored. Trimmed and collapsed, case **kept**: a tag
 *    wears the casing of whoever entered it first (the wizard's call), so the list reads the way it
 *    was typed.
 *  - [identityKey] — what "already exists?" is asked with. [display] folded to lower case with
 *    [Locale.ROOT], never the device locale: a Turkish device must not decide that "I" and "ı" are
 *    the same tag when the same library on another device says they are not. Locale-neutral is what
 *    makes a `.soil` library portable, which is this family's oldest rule.
 *
 * The fold is not stored — it is re-derived from [display] every time an index is read. One
 * function is the identity, so an index can never disagree with the rule that built it.
 */
object TagRules {

    /**
     * [text] as it will be shown and stored: ends trimmed, every internal run of whitespace
     * collapsed to a single space. Case untouched.
     *
     * Whitespace here is Kotlin's `Char.isWhitespace` — which covers the tab and newline the codec
     * would otherwise have to escape, so a normalized tag is a codec-safe tag by construction.
     */
    fun display(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                // A run of any length is one space — and never a leading one, which is the trim.
                if (sb.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                sb.append(' ')
                pendingSpace = false
            }
            sb.append(ch)
        }
        // A trailing run leaves `pendingSpace` set and is simply never appended — the other trim.
        return sb.toString()
    }

    /** The identity of the tag [text] names: its [display] form, case-folded locale-neutrally. */
    fun identityKey(text: String): String = display(text).lowercase(Locale.ROOT)

    /**
     * Whether [text] can become a tag: something is left after normalizing, and what is left is
     * within [ExtensionContract.MAX_TAG_CHARS]. Blank in, false out — a tag of nothing is not a tag.
     */
    fun isValid(text: String): Boolean {
        val d = display(text)
        return d.isNotEmpty() && d.length <= ExtensionContract.MAX_TAG_CHARS
    }
}
