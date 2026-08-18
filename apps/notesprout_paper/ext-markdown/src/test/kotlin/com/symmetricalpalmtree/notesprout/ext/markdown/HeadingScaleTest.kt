package com.symmetricalpalmtree.notesprout.ext.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** The six heading multipliers are the original Notesprout's, baked into the extension (arc 4, Q8). */
class HeadingScaleTest {

    @Test
    fun `H1 to H6 multipliers`() {
        assertEquals(2.0f, MarkdownSpans.headingSizeMultiplier(1), 0f)
        assertEquals(1.75f, MarkdownSpans.headingSizeMultiplier(2), 0f)
        assertEquals(1.5f, MarkdownSpans.headingSizeMultiplier(3), 0f)
        assertEquals(1.25f, MarkdownSpans.headingSizeMultiplier(4), 0f)
        assertEquals(1.1f, MarkdownSpans.headingSizeMultiplier(5), 0f)
        assertEquals(1.0f, MarkdownSpans.headingSizeMultiplier(6), 0f)
    }

    @Test
    fun `out of range levels are body size`() {
        assertEquals(1.0f, MarkdownSpans.headingSizeMultiplier(0), 0f)
        assertEquals(1.0f, MarkdownSpans.headingSizeMultiplier(7), 0f)
    }

    @Test
    fun `base size is 24 sp converted by dpi`() {
        assertEquals(24f, MarkdownBitmap.textSizePx(160f), 0f)
        assertEquals(48f, MarkdownBitmap.textSizePx(320f), 0f)
        assertEquals(1.875f, MarkdownBitmap.density(300f), 0.0001f)
    }
}
