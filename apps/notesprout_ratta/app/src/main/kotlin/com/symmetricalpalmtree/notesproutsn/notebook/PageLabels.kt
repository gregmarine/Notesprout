package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.core.markdown.HeadingPrefix

/**
 * The heading-as-page-name rule (arc 6 / K2, the og feature Paper skipped): a page's display
 * title in the link picker is its **topmost heading's** bare text — topmost by `(y, x)`, prefix
 * stripped — or nothing, in which case the card says "Page n" from position alone.
 *
 * **A wrapped heading counts too.** A wrap re-parents its children page → link (arc 6 / K1) but
 * leaves their `(x, y)` page-absolute, so a heading turned into a link's title is still the topmost
 * thing written on the page and still names it. The same call the Contents outline makes when it
 * hops link → page ([ContentsSource]) — one answer to "what did the user write on this page",
 * in both places.
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

    /** [titleOf] over everything a page holds — its loose headings **and** the ones its links wrap.
     *  The one call site the picker uses; the list overload stays the tested primitive. */
    fun titleOf(content: PageContent): String? =
        titleOf(content.headings + content.links.flatMap { it.headings })
}
