package com.notesprout.android.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `![alt](url)` in the shared parser.
 *
 * The renderer draws no images, so an image reference shows its alt text in italic — the way a caption
 * reads. Before this, the `!` was left as literal text and the rest parsed as a link, so an image came
 * out as "!alt", underlined and pretending to be one.
 */
class MarkdownParserImageTest {

    private fun inlines(markdown: String): List<Inline> =
        (MarkdownParser.parse(markdown).single() as Block.Paragraph).inlines

    private fun flatten(inline: Inline): String = when (inline) {
        is Inline.Text -> inline.text
        is Inline.Italic -> inline.children.joinToString("") { flatten(it) }
        is Inline.Bold -> inline.children.joinToString("") { flatten(it) }
        is Inline.Link -> inline.displayText
        else -> ""
    }

    @Test
    fun `an image renders as its alt text in italic`() {
        val parsed = inlines("![the barn](url)")
        assertEquals(1, parsed.size)
        assertEquals(Inline.Italic(listOf(Inline.Text("the barn"))), parsed.single())
    }

    @Test
    fun `the bang is not left behind as text`() {
        assertEquals("before the barn after", inlines("before ![the barn](url) after").joinToString("") { flatten(it) })
    }

    @Test
    fun `a link is still a link`() {
        val parsed = inlines("[the barn](url)")
        assertEquals(Inline.Link("the barn", "url"), parsed.single())
    }

    @Test
    fun `an image with no description renders as nothing`() {
        // Nothing to caption with — better an empty gap than a stray bracket.
        assertEquals("before  after", inlines("before ![](url) after").joinToString("") { flatten(it) })
    }

    @Test
    fun `an unclosed image is left as written`() {
        assertEquals("![the barn", inlines("![the barn").joinToString("") { flatten(it) })
        assertEquals("![the barn](url", inlines("![the barn](url").joinToString("") { flatten(it) })
    }

    @Test
    fun `a bare bang is untouched`() {
        assertEquals("hey! there", inlines("hey! there").joinToString("") { flatten(it) })
    }
}
