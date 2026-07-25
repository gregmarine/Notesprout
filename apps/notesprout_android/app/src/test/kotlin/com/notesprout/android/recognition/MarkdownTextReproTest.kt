package com.notesprout.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the reported "`.txt` export only kept the first line" bug. The recognized
 * page text of notebook `20260703_191745` (a heading, three wrapped handwriting paragraphs, a
 * Markdown text object with bold + ordered list + blockquote) must survive plain-text conversion
 * with every block intact. `MarkdownText.toPlainText` must never collapse a multi-block document
 * to its first line.
 */
class MarkdownTextReproTest {

    private val pageText =
        "# Testing RTR\n\nThis is a test of real-time recognition. Let's\n see how well it does with this. It's better\n than I originally thought when exporting. But now\n I'm seeing how well RTR is going to do.\n\n I love how well Notesprout is growing now. It has\n come a long way in such a short amount of\n ago, I started this project just two months\n\n How are this paragraphs shaping up? Is the\n preview as good as the export? Ooh! I should\n try to add markdown in the body and see how\n that goes.\n\n**This is md text inserted.**\n\n1. Cool\n2. Amazing\n3. Sweet\n\n> I love ths"

    @Test
    fun plainTextKeepsEveryBlock() {
        val plain = MarkdownText.toPlainText(pageText)
        // Heading text (without the '#'), the wrapped paragraphs, and — critically — every part of
        // the trailing Markdown text object must be present.
        listOf(
            "Testing RTR",
            "This is a test of real-time recognition.",
            "I love how well Notesprout is growing now.",
            "How are this paragraphs shaping up?",
            "This is md text inserted.",
            "1. Cool", "2. Amazing", "3. Sweet",
            "I love ths",
        ).forEach { assertTrue("plain text is missing: \"$it\"\n---\n$plain", plain.contains(it)) }
        // Markdown syntax is stripped.
        assertTrue("'#' should be stripped", !plain.contains("#"))
        assertTrue("'**' should be stripped", !plain.contains("**"))
    }

    /**
     * Documents the one genuine MD-vs-TXT divergence: a text object whose lines are separated by
     * **single** newlines (no blank line) is a single Markdown paragraph, so plain text joins the
     * lines with spaces. Markdown export keeps the raw line breaks. This is the likely source of a
     * "the txt lost my lines" report when the text object used soft breaks rather than blank lines.
     */
    @Test
    fun singleNewlineLinesAreJoinedInPlainText() {
        val softBreaks = "Groceries\nMilk\nEggs\nBread"
        assertEquals("Groceries Milk Eggs Bread", MarkdownText.toPlainText(softBreaks))

        val blankLineSeparated = "Groceries\n\nMilk\n\nEggs\n\nBread"
        assertEquals(
            listOf("Groceries", "", "Milk", "", "Eggs", "", "Bread"),
            MarkdownText.toPlainText(blankLineSeparated).lines(),
        )
    }

    @Test
    fun textObjectMarkdownConvertsFully() {
        val textObject = "**This is md text inserted.**\n\n1. Cool\n2. Amazing\n3. Sweet\n\n> I love ths"
        val plain = MarkdownText.toPlainText(textObject)
        assertEquals(
            listOf("This is md text inserted.", "", "1. Cool", "2. Amazing", "3. Sweet", "I love ths"),
            plain.lines(),
        )
    }
}
