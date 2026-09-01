package com.symmetricalpalmtree.notesproutsn.extension

import java.util.Locale
import java.util.UUID

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

    /**
     * Whether [id] is a **canonical UUID** — the one shape a tag id, a notebook id or a page id may
     * take, at every door on both sides of the seam (arc 22 / X3, carried over unchanged from
     * arc 21's `CompactId.isId` when the compact encoding was deleted with the index blob).
     *
     * `UUID.fromString` is famously lenient — it accepts `1-2-3-4-5` and pads it out — so the parse
     * is round-tripped through `toString()` and only the canonical `8-4-4-4-12` form is accepted.
     * That also keeps a path character, a tab and a NUL out of every id the seam carries: the UUID
     * alphabet has none of them, which is what lets the parcels stop hand-checking for one.
     *
     * Hex **case is not significant**, exactly as it was not for `CompactId` and is not for arc 16's
     * `SafeImportId` — a `.soil` out of a stranger's file may carry upper-case ids and its pages are
     * still taggable. Ids are compared as they were handed over, and everything this family mints is
     * `UUID.randomUUID().toString()`, which is lower case.
     */
    fun isId(id: String): Boolean {
        val parsed = try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return parsed.toString().equals(id, ignoreCase = true)
    }
}
