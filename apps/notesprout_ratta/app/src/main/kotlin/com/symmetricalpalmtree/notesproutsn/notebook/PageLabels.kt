package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.core.markdown.HeadingPrefix

/**
 * The heading-as-page-name rule (arc 6 / K2, the og feature Paper skipped): a page's display
 * title in the link picker is its **topmost heading's** bare text — topmost by `(y, x)`, prefix
 * stripped — or nothing, in which case the card says "Page n" from position alone.
 *
 * Only the page's **loose** headings count: a heading wrapped inside a link belongs to the link
 * (its `parentId` is the link, not the page), exactly as it leaves the Contents outline (K1) —
 * one rule for "whose heading is it", everywhere.
 *
 * Pure Kotlin — JVM-tested. The "Page n" / "n · title" wording itself is the picker's string
 * resource; this only answers *whether there is a title and what it says*.
 */
object PageLabels {

    /** The bare title of the topmost heading, or null when the page has none (or only blank ones —
     *  a title that strips to nothing names nothing). */
    fun titleOf(headings: List<Heading>): String? =
        headings.minWithOrNull(compareBy({ it.y }, { it.x }))
            ?.let { HeadingPrefix.stripHeadingPrefix(it.text).trim().ifEmpty { null } }
}
