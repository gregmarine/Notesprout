package com.symmetricalpalmtree.notesproutsn.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `![alt](url)`.
 *
 * Nothing in the app draws images, so an image reference shows its alt text in italic, the way a
 * caption reads. Parsed as a link instead, the `!` would be stranded in front of an underlined
 * "alt" that is not a link at all.
 */
class MarkdownParserImageTest {

    @Test
    fun image_rendersAltTextInItalic() {
        val parsed = paragraphInlines("![the barn](url)")
        assertEquals(1, parsed.size)
        assertEquals(Inline.Italic(listOf(Inline.Text("the barn"))), parsed.single())
    }

    @Test
    fun bang_isNotLeftBehind() {
        assertEquals("before the barn after", flatten(paragraphInlines("before ![the barn](url) after")))
    }

    @Test
    fun link_isStillALink() {
        assertEquals(Inline.Link("the barn", "url"), paragraphInlines("[the barn](url)").single())
    }

    @Test
    fun emptyAlt_rendersNothing() {
        // Nothing to caption with — an empty gap beats a stray bracket.
        assertEquals("before  after", flatten(paragraphInlines("before ![](url) after")))
    }

    @Test
    fun unclosedImage_isLeftAsWritten() {
        assertEquals("![the barn", flatten(paragraphInlines("![the barn")))
        assertEquals("![the barn](url", flatten(paragraphInlines("![the barn](url")))
    }

    @Test
    fun bareBang_isUntouched() {
        assertEquals("hey! there", flatten(paragraphInlines("hey! there")))
    }
}
