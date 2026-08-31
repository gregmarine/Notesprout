package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.ext.document.AutosaveGovernor.SaveAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The autosave decisions, pinned. Every one of these is a rule that only shows itself on a device as
 * "my words are gone" or "it saved the wrong page", so none of them may live only in the plumbing.
 */
class AutosaveGovernorTest {

    @Test
    fun `nothing loaded yet — even empty text is dirty`() {
        val g = AutosaveGovernor()
        assertNull(g.savedText)
        assertTrue(g.isDirty(""))
        assertEquals(SaveAction.Push(""), g.request(""))
    }

    @Test
    fun `the loaded text counts as saved`() {
        val g = AutosaveGovernor()
        g.markLoaded("hello")
        assertFalse(g.isDirty("hello"))
        assertEquals(SaveAction.Idle, g.request("hello"))
        assertFalse(g.isPushing)
    }

    @Test
    fun `unchanged text never pushes, however often it is asked`() {
        val g = AutosaveGovernor()
        g.markLoaded("hello")
        repeat(5) { assertEquals(SaveAction.Idle, g.request("hello")) }
    }

    @Test
    fun `a change pushes, and the same change afterwards does not`() {
        val g = AutosaveGovernor()
        g.markLoaded("hello")
        assertEquals(SaveAction.Push("hello!"), g.request("hello!"))
        assertTrue(g.isPushing)
        assertEquals(SaveAction.Idle, g.onSaved("hello!"))
        assertFalse(g.isPushing)
        assertEquals("hello!", g.savedText)
        assertEquals(SaveAction.Idle, g.request("hello!"))
    }

    @Test
    fun `a newer snapshot waits for the push in flight and goes after it`() {
        val g = AutosaveGovernor()
        g.markLoaded("a")
        assertEquals(SaveAction.Push("ab"), g.request("ab"))
        assertEquals(SaveAction.Wait, g.request("abc"))
        // The in-flight push lands; the queued snapshot is what goes next — one at a time.
        assertEquals(SaveAction.Push("abc"), g.onSaved("ab"))
        assertEquals("ab", g.savedText)
        assertEquals(SaveAction.Idle, g.onSaved("abc"))
        assertEquals("abc", g.savedText)
    }

    @Test
    fun `only the newest queued snapshot survives`() {
        val g = AutosaveGovernor()
        g.markLoaded("a")
        g.request("ab")
        g.request("abc")
        g.request("abcd")
        assertEquals(SaveAction.Push("abcd"), g.onSaved("ab"))
    }

    @Test
    fun `a queue that the in-flight push made redundant is dropped`() {
        val g = AutosaveGovernor()
        g.markLoaded("a")
        g.request("ab")          // in flight
        g.request("abc")         // queued
        g.request("ab")          // typed back to what is being pushed
        assertEquals(SaveAction.Idle, g.onSaved("ab"))
        assertFalse(g.isDirty("ab"))
    }

    @Test
    fun `a failed push keeps the buffer dirty and savedText where it was`() {
        val g = AutosaveGovernor()
        g.markLoaded("a")
        g.request("ab")
        assertEquals(SaveAction.Retry, g.onFailed())
        assertEquals("a", g.savedText)
        assertTrue(g.isDirty("ab"))
        assertFalse(g.isPushing)
        // The retry snapshots the live buffer again and pushes it.
        assertEquals(SaveAction.Push("abc"), g.request("abc"))
    }

    @Test
    fun `savedText advances only on success`() {
        val g = AutosaveGovernor()
        g.markLoaded("a")
        g.request("ab"); g.onFailed()
        assertEquals("a", g.savedText)
        g.request("ab"); g.onSaved("ab")
        assertEquals("ab", g.savedText)
    }

    @Test
    fun `reconnect flushes only a dirty buffer whose target still matches`() {
        val g = AutosaveGovernor()
        g.markLoaded("a")
        assertTrue(g.shouldFlushOnReconnect("page-1", "page-1", "ab"))
        // Clean buffer: nothing to say.
        assertFalse(g.shouldFlushOnReconnect("page-1", "page-1", "a"))
        // The host came back on another document — these words are not its.
        assertFalse(g.shouldFlushOnReconnect("page-2", "page-1", "ab"))
        // No target learned yet: nothing may be written anywhere.
        assertFalse(g.shouldFlushOnReconnect("page-1", null, "ab"))
    }

    @Test
    fun `abandonQueue drops the outgoing page's queue without stalling the next save`() {
        val g = AutosaveGovernor()
        g.markLoaded("a")
        g.request("ab")
        // Typed again while that push was in flight: queued behind it.
        assertEquals(SaveAction.Wait, g.request("abc"))
        // The push lands and the flip takes the queue away — "abc" belongs to the page being left.
        g.onSaved("ab")
        g.abandonQueue()
        assertFalse(g.isPushing)
        assertEquals("ab", g.savedText)
        // The incoming page's text is what the screen holds now, and it pushes normally.
        g.markLoaded("z")
        assertEquals(SaveAction.Push("zz"), g.request("zz"))
    }

    @Test
    fun `requestDraft pushes even unchanged text — the Bring-in re-anchor`() {
        val g = AutosaveGovernor()
        g.markLoaded("same")
        // The ordinary trigger drops it as unchanged…
        assertEquals(SaveAction.Idle, g.request("same"))
        // …a Bring in's does not: re-anchoring the watermark is the whole act (og's rule).
        assertEquals(SaveAction.Push("same"), g.requestDraft("same"))
        assertTrue(g.isPushing)
        g.onSaved("same")
        assertFalse(g.isPushing)
    }

    @Test
    fun `requestDraft queues behind an in-flight push`() {
        val g = AutosaveGovernor()
        g.markLoaded("a")
        assertEquals(SaveAction.Push("ab"), g.request("ab"))
        assertEquals(SaveAction.Wait, g.requestDraft("ab"))
    }
}
