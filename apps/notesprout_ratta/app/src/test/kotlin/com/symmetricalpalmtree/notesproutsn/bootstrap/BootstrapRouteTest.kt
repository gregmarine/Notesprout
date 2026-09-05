package com.symmetricalpalmtree.notesproutsn.bootstrap

import com.symmetricalpalmtree.notesproutsn.bootstrap.BootstrapRoute.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where an opened index sends the person (arc 26 / U3): the key first, then a resume, then the library. */
class BootstrapRouteTest {

    @Test
    fun decision() {
        assertEquals(Next.LIBRARY, BootstrapRoute.afterOpen(acknowledged = true, hasMarker = false))
        assertEquals(Next.ENCRYPTION, BootstrapRoute.afterOpen(acknowledged = true, hasMarker = true))
        assertEquals(Next.RECOVERY_KEY, BootstrapRoute.afterOpen(acknowledged = false, hasMarker = false))
        // Both set = a commit that died between clearing the ack and clearing the marker: the key
        // shown IS the marker's, so it goes first.
        assertEquals(Next.RECOVERY_KEY, BootstrapRoute.afterOpen(acknowledged = false, hasMarker = true))
    }

    @Test
    fun thenBackupRidesEverythingButAResume() {
        assertTrue(BootstrapRoute.carriesThenBackup(Next.LIBRARY))
        assertTrue(BootstrapRoute.carriesThenBackup(Next.RECOVERY_KEY))
        assertFalse(BootstrapRoute.carriesThenBackup(Next.ENCRYPTION))
    }
}
