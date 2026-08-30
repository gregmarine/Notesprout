package com.symmetricalpalmtree.notesproutsn.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** Span-level markup: emphasis, code, nesting, and what happens when a marker is never closed. */
class MarkdownParserInlineTest {

    @Test
    fun bold_bothMarkers() {
        val expected = listOf(Inline.Bold(listOf(Inline.Text("loud"))))
        assertEquals(expected, paragraphInlines("**loud**"))
        assertEquals(expected, paragraphInlines("__loud__"))
    }

    @Test
    fun italic_bothMarkers() {
        val expected = listOf(Inline.Italic(listOf(Inline.Text("soft"))))
        assertEquals(expected, paragraphInlines("*soft*"))
        assertEquals(expected, paragraphInlines("_soft_"))
    }

    @Test
    fun strikethrough() {
        assertEquals(
            listOf(Inline.Strikethrough(listOf(Inline.Text("gone")))),
            paragraphInlines("~~gone~~"),
        )
    }

    @Test
    fun code_contentStaysLiteral() {
        // Backticks win over everything: markup inside them is content, not markup.
        assertEquals(listOf(Inline.Code("**not bold**")), paragraphInlines("`**not bold**`"))
    }

    @Test
    fun nesting_recursesIntoChildren() {
        assertEquals(
            listOf(
                Inline.Bold(
                    listOf(
                        Inline.Text("bold "),
                        Inline.Italic(listOf(Inline.Text("italic"))),
                        Inline.Text(" bold"),
                    ),
                ),
            ),
            paragraphInlines("**bold *italic* bold**"),
        )
    }

    @Test
    fun unclosedMarkers_stayLiteral() {
        // Half-typed markup shows exactly as typed rather than swallowing the rest of the line.
        assertEquals(listOf(Inline.Text("**bold")), paragraphInlines("**bold"))
        assertEquals(listOf(Inline.Text("~~gone")), paragraphInlines("~~gone"))
        assertEquals(listOf(Inline.Text("`code")), paragraphInlines("`code"))
        assertEquals(listOf(Inline.Text("*soft")), paragraphInlines("*soft"))
        assertEquals(listOf(Inline.Text("_soft")), paragraphInlines("_soft"))
        assertEquals(listOf(Inline.Text("[link](url")), paragraphInlines("[link](url"))
    }

    @Test
    fun literals_coalesceIntoOneNode() {
        // One node per character would make every span operation downstream quadratic.
        assertEquals(listOf(Inline.Text("just plain text")), paragraphInlines("just plain text"))
    }

    @Test
    fun link_keepsUrlAndDisplayTextApart() {
        assertEquals(
            listOf(Inline.Text("see "), Inline.Link("the barn", "https://example.test/barn")),
            paragraphInlines("see [the barn](https://example.test/barn)"),
        )
    }
}
