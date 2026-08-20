package com.symmetricalpalmtree.notesprout.extension

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The host-process New-notebook relay (arc 7 / L3) — parked prepare + read-and-clear created. */
class LinkCreateRelayTest {

    @Before
    fun setUp() {
        LinkCreateRelay.clear()
    }

    @After
    fun tearDown() {
        LinkCreateRelay.clear()
    }

    @Test
    fun preparedReturnsTheParkedFolderAndDefaultName() {
        LinkCreateRelay.prepare("folder-1", "Untitled 3")
        val prepared = LinkCreateRelay.prepared()
        assertEquals("folder-1", prepared?.parentFolderId)
        assertEquals("Untitled 3", prepared?.defaultName)
    }

    @Test
    fun preparedDoesNotClearOnRead() {
        LinkCreateRelay.prepare("folder-1", "Untitled 3")
        LinkCreateRelay.prepared()   // first read
        val secondRead = LinkCreateRelay.prepared()   // recreation must still find it
        assertEquals("folder-1", secondRead?.parentFolderId)
        assertEquals("Untitled 3", secondRead?.defaultName)
    }

    @Test
    fun prepareWithNullsRoundTripsAsNulls() {
        LinkCreateRelay.prepare(null, null)
        val prepared = LinkCreateRelay.prepared()
        assertNull(prepared?.parentFolderId)
        assertNull(prepared?.defaultName)
    }

    @Test
    fun takeCreatedReturnsTheCreatedNotebookOnceThenNull() {
        LinkCreateRelay.setCreated("id-1", "New Notebook")
        val first = LinkCreateRelay.takeCreated()
        assertEquals("id-1", first?.id)
        assertEquals("New Notebook", first?.name)
        assertNull(LinkCreateRelay.takeCreated())   // read-and-clear
    }

    @Test
    fun takeCreatedWithNothingSetIsNull() {
        assertNull(LinkCreateRelay.takeCreated())
    }

    @Test
    fun prepareDropsAStaleCreatedValueFromAPreviousArming() {
        LinkCreateRelay.prepare("folder-1", "Untitled 3")
        LinkCreateRelay.setCreated("id-1", "New Notebook")
        LinkCreateRelay.prepare("folder-2", "Untitled 4")   // a fresh arming
        assertNull(LinkCreateRelay.takeCreated())
    }

    @Test
    fun clearDropsBothSlots() {
        LinkCreateRelay.prepare("folder-1", "Untitled 3")
        LinkCreateRelay.setCreated("id-1", "New Notebook")
        LinkCreateRelay.clear()
        assertNull(LinkCreateRelay.prepared())
        assertNull(LinkCreateRelay.takeCreated())
    }
}
