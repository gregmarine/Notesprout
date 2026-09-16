package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.notesproutsn.ext.sketch.SketchSaveGovernor.SaveAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a drawing is written (arc 43 / K5). Every one of them is a rule
 * about *pixels that have no other copy*, which is why they live in a class with no Android in it
 * and are proved here rather than by watching a Supernote.
 */
class SketchSaveGovernorTest {

    private val g = SketchSaveGovernor()

    @Test
    fun `a clean page has nothing to write`() {
        assertEquals(SaveAction.Idle, g.request())
        assertFalse(g.inFlight)
    }

    @Test
    fun `a dirty page is saved, and the flag clears when the copy is taken`() {
        g.markDirty()
        assertTrue(g.dirty)
        assertEquals(SaveAction.Save, g.request())
        assertFalse("dirty clears at the copy, not at the write", g.dirty)
        assertTrue(g.inFlight)
    }

    @Test
    fun `a mark during the push re-dirties the page and is written after it`() {
        g.markDirty()
        g.request()
        // The hand goes on drawing while the encode and the chunk stream run.
        g.markDirty()
        assertEquals("newest wins, but it waits its turn", SaveAction.Wait, g.request())
        assertEquals("the in-flight push's completion is what starts it", SaveAction.Save, g.onSaved())
        assertFalse(g.dirty)
    }

    @Test
    fun `nothing new during the push means nothing follows it`() {
        g.markDirty()
        g.request()
        assertEquals(SaveAction.Idle, g.onSaved())
        assertFalse(g.inFlight)
    }

    @Test
    fun `a failed push leaves the page dirty and asks for a retry`() {
        g.markDirty()
        g.request()
        assertEquals(SaveAction.Retry, g.onFailed())
        assertTrue("a failure never advances anything", g.dirty)
        assertFalse(g.inFlight)
        assertEquals("the retry re-encodes the same page", SaveAction.Save, g.request())
    }

    @Test
    fun `a copy that could not be taken leaves the page dirty and nothing in flight`() {
        g.markDirty()
        g.request()
        g.onCopyFailed()
        assertTrue(g.dirty)
        assertFalse(g.inFlight)
    }

    @Test
    fun `a leave flush ignores a push in flight`() {
        g.markDirty()
        g.request()          // one push claimed
        g.markDirty()        // and a mark since
        // The exclusion that matters is the caller's push lock; refusing here would mean leaving
        // with the newest pixels unwritten because an older push was still in the air.
        assertEquals(SaveAction.Save, g.flushRequest())
        assertFalse(g.dirty)
    }

    @Test
    fun `a leave flush of a clean page writes nothing`() {
        assertEquals(SaveAction.Idle, g.flushRequest())
    }

    @Test
    fun `a page just loaded is clean`() {
        g.markDirty()
        g.markClean()
        assertEquals(SaveAction.Idle, g.request())
    }

    @Test
    fun `a page dirtied again after a load is written`() {
        g.markClean()
        g.markDirty()
        assertEquals(SaveAction.Save, g.request())
    }
}
