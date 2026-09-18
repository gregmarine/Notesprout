package com.symmetricalpalmtree.notesproutsn.data.prefs

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchToolSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored-tools decode (arc 44 / T2) — [SketchToolCodec.decode] treating three stored prefs
 * values as untrusted input: a complete legal trio is the settings the face asked for, and anything
 * else at all is **nothing remembered**, never an exception on a Binder thread.
 *
 * The other half of what is pinned here is what the host must *not* do: it stores indices and hands
 * them back unchanged, and never clamps them to a palette it does not know.
 */
class SketchToolCodecTest {

    @Test
    fun `a complete legal trio decodes to exactly those three values`() {
        val out = SketchToolCodec.decode(SketchContract.TOOL_PEN, 3, 2)
        assertEquals(SketchToolSettings(SketchContract.TOOL_PEN, 3, 2), out)
    }

    @Test
    fun `any missing value is nothing remembered`() {
        assertNull(SketchToolCodec.decode(null, 3, 2))
        assertNull(SketchToolCodec.decode(SketchContract.TOOL_PENCIL, null, 2))
        assertNull(SketchToolCodec.decode(SketchContract.TOOL_PENCIL, 3, null))
        assertNull(SketchToolCodec.decode(null, null, null))
    }

    @Test
    fun `a value that could not be an index at all is nothing remembered, in every field`() {
        val over = SketchContract.MAX_TOOL_SETTING_INDEX + 1
        assertNull(SketchToolCodec.decode(-1, 0, 0))
        assertNull(SketchToolCodec.decode(0, -1, 0))
        assertNull(SketchToolCodec.decode(0, 0, -1))
        assertNull(SketchToolCodec.decode(over, 0, 0))
        assertNull(SketchToolCodec.decode(0, over, 0))
        assertNull(SketchToolCodec.decode(0, 0, over))
        assertNull(SketchToolCodec.decode(Int.MIN_VALUE, Int.MAX_VALUE, 0))
    }

    @Test
    fun `both ends of the sanity bound are legal`() {
        assertEquals(SketchToolSettings(0, 0, 0), SketchToolCodec.decode(0, 0, 0))
        val max = SketchContract.MAX_TOOL_SETTING_INDEX
        assertEquals(SketchToolSettings(max, max, max), SketchToolCodec.decode(max, max, max))
    }

    /**
     * The host never clamps to the face's palette. Shade 14 and size 4 are the far ends of arc 44's
     * own lists, and an index past the end of whatever list `:ext-sketch` ships is the **face's**
     * fallback to its default, not a number the host is allowed to rewrite on the way through.
     */
    @Test
    fun `an index the face may not have a swatch for still round-trips unchanged`() {
        assertEquals(SketchToolSettings(0, 14, 4), SketchToolCodec.decode(0, 14, 4))
        assertEquals(SketchToolSettings(0, 200, 199), SketchToolCodec.decode(0, 200, 199))
    }

    /** The tool field is bounded the same way rather than to the two constants that exist today, so
     *  a later tool is a new constant and not a stored value this decode starts refusing. */
    @Test
    fun `a tool number beyond the two that exist today is still a legal decode`() {
        assertEquals(SketchToolSettings(7, 0, 0), SketchToolCodec.decode(7, 0, 0))
    }
}
