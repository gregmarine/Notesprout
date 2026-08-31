package com.symmetricalpalmtree.notesproutsn.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `.txt` strip: markdown syntax gone, layout structure kept. The first three cases are the
 * ported regression floor from the original app (a `.txt` export that kept only the first line of a
 * multi-block document) — `toPlainText` must never collapse a document to its opening line.
 */
class MarkdownTextTest {

    /**
     * Recognized page text of a real notebook: a heading, three wrapped handwriting paragraphs, then
     * a markdown text object with bold + an ordered list + a blockquote.
     */
    private val pageText =
        "# Testing RTR\n\nThis is a test of real-time recognition. Let's\n see how well it does with this. It's better\n than I originally thought when exporting. But now\n I'm seeing how well RTR is going to do.\n\n I love how well Notesprout is growing now. It has\n come a long way in such a short amount of\n ago, I started this project just two months\n\n How are this paragraphs shaping up? Is the\n preview as good as the export? Ooh! I should\n try to add markdown in the body and see how\n that goes.\n\n**This is md text inserted.**\n\n1. Cool\n2. Amazing\n3. Sweet\n\n> I love ths"

    @Test
    fun plainTextKeepsEveryBlock() {
        val plain = MarkdownText.toPlainText(pageText)
        // Heading text (without the '#'), the wrapped paragraphs, and — critically — every part of
        // the trailing markdown text object must be present.
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
     * The one genuine MD-vs-TXT divergence: lines separated by **single** newlines are a single
     * markdown paragraph, so plain text joins them with spaces. Markdown export keeps the raw
     * breaks. A "the txt lost my lines" report means the source used soft breaks, not blank lines.
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

    // ── Blocks ────────────────────────────────────────────────────────────────

    @Test
    fun blankInput_isEmpty() {
        assertEquals("", MarkdownText.toPlainText(""))
        assertEquals("", MarkdownText.toPlainText("   \n\n\t  "))
    }

    @Test
    fun headingsOfEveryLevel_loseTheirHashes() {
        for (level in 1..6) {
            assertEquals("Title", MarkdownText.toPlainText("${"#".repeat(level)} Title"))
        }
    }

    @Test
    fun horizontalRule_isAnExtraBlankLine() {
        // Paragraph gap + the rule's own newline = two blank lines between the sections.
        assertEquals(
            listOf("Before", "", "", "After"),
            MarkdownText.toPlainText("Before\n\n---\n\nAfter").lines(),
        )
    }

    @Test
    fun blockquote_keepsTextWithoutTheMarker() {
        assertEquals("Quoted line", MarkdownText.toPlainText("> Quoted line"))
    }

    // ── List items ────────────────────────────────────────────────────────────

    @Test
    fun unorderedItems_keepASimpleMarker() {
        assertEquals(
            listOf("- Milk", "- Eggs"),
            MarkdownText.toPlainText("* Milk\n+ Eggs").lines(),
        )
    }

    @Test
    fun orderedItems_useTheDisplayNumber() {
        // The run's first written number is honoured, and the rest count on from it.
        assertEquals(
            listOf("3. Three", "4. Four", "5. Five"),
            MarkdownText.toPlainText("3. Three\n9. Four\n1. Five").lines(),
        )
    }

    @Test
    fun tasks_keepTheirCheckbox() {
        assertEquals(
            listOf("- [ ] todo", "- [x] done"),
            MarkdownText.toPlainText("- [ ] todo\n- [X] done").lines(),
        )
    }

    @Test
    fun nestedItems_indentTwoSpacesPerDepth() {
        assertEquals(
            listOf("- Top", "  - Nested", "    1. Deeper"),
            MarkdownText.toPlainText("- Top\n  - Nested\n    1. Deeper").lines(),
        )
    }

    @Test
    fun listRun_endsWithASingleNewline() {
        // Items close with one "\n" (not the paragraph's two), so the text after a list follows on
        // the next line rather than after a blank one.
        assertEquals(
            listOf("Intro", "", "- One", "- Two", "Outro"),
            MarkdownText.toPlainText("Intro\n\n- One\n- Two\n\nOutro").lines(),
        )
    }

    // ── Inlines ───────────────────────────────────────────────────────────────

    @Test
    fun emphasisAndCode_flattenToVisibleText() {
        assertEquals("bold italic struck code", MarkdownText.toPlainText("**bold** *italic* ~~struck~~ `code`"))
    }

    @Test
    fun nestedEmphasis_flattensThroughEveryLayer() {
        assertEquals("bold and italic", MarkdownText.toPlainText("**bold and _italic_**"))
    }

    @Test
    fun link_keepsDisplayTextAndDropsTheUrl() {
        val plain = MarkdownText.toPlainText("See [the docs](https://example.com/docs) now.")
        assertEquals("See the docs now.", plain)
    }

    /**
     * `![alt](url)` is folded to an italic alt-text run by the parser (nothing draws images), so the
     * strip sees ordinary emphasis and keeps the alt text — matching what the renderer shows.
     */
    @Test
    fun image_flattensToItsAltText() {
        assertEquals("A diagram", MarkdownText.toPlainText("![A diagram](diagram.png)"))
        // An empty alt leaves nothing at all — the parser emits no inline for it.
        assertEquals("", MarkdownText.toPlainText("![](diagram.png)"))
    }

    @Test
    fun unclosedMarkup_survivesAsTyped() {
        assertEquals("half **typed", MarkdownText.toPlainText("half **typed"))
    }

    // ── Whole documents ───────────────────────────────────────────────────────

    @Test
    fun mixedDocument_keepsEveryBlockInOrder() {
        val md = """
            # Title

            A paragraph with **bold** text.

            - [ ] one task
            - [x] another

            ---

            > A closing quote with a [link](https://example.com).
        """.trimIndent()
        assertEquals(
            listOf(
                "Title",
                "",
                "A paragraph with bold text.",
                "",
                "- [ ] one task",
                "- [x] another",
                "",                     // the rule's extra newline, on top of the list's single one
                "A closing quote with a link.",
            ),
            MarkdownText.toPlainText(md).lines(),
        )
    }
}
