package com.symmetricalpalmtree.notesproutsn.core.markdown

/**
 * Shared helpers for the parser suites. Kept in one place so every suite flattens inline trees the
 * same way — a test that flattened differently would pass on its own reading of the tree.
 */

/** The inlines of a source that is expected to parse to exactly one paragraph. */
internal fun paragraphInlines(markdown: String): List<Inline> =
    (MarkdownParser.parse(markdown).single() as Block.Paragraph).inlines

/** Visible text of an inline, ignoring the markup that produced it. */
internal fun flatten(inline: Inline): String = when (inline) {
    is Inline.Text -> inline.text
    is Inline.Bold -> inline.children.joinToString("") { flatten(it) }
    is Inline.Italic -> inline.children.joinToString("") { flatten(it) }
    is Inline.Strikethrough -> inline.children.joinToString("") { flatten(it) }
    is Inline.Code -> inline.text
    is Inline.Link -> inline.displayText
}

internal fun flatten(inlines: List<Inline>): String = inlines.joinToString("") { flatten(it) }
