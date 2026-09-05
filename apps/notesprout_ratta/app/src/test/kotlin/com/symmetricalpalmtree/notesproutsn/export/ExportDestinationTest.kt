package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.CloudStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Destination row's rules (arc 25 / V3). Three questions, and the one that matters most is the
 * middle one: a *cloud* answer must never outlive the row that asked for it.
 */
class ExportDestinationTest {

    private fun status(connected: Boolean, configured: Boolean, name: String = "Google Drive") =
        CloudStatus(connected, configured, if (connected) "person@example.com" else "", name)

    @Test
    fun `the row exists only while a provider is installed`() {
        assertTrue(ExportDestination.rowVisible(providerInstalled = true))
        assertFalse(ExportDestination.rowVisible(providerInstalled = false))
    }

    @Test
    fun `a standing cloud answer is forced back to local when the row goes`() {
        assertEquals(
            ExportDestination.Choice.LOCAL,
            ExportDestination.settled(ExportDestination.Choice.CLOUD, rowVisible = false),
        )
        assertEquals(
            ExportDestination.Choice.CLOUD,
            ExportDestination.settled(ExportDestination.Choice.CLOUD, rowVisible = true),
        )
    }

    @Test
    fun `local survives either way — there is nothing to force it back to`() {
        assertEquals(
            ExportDestination.Choice.LOCAL,
            ExportDestination.settled(ExportDestination.Choice.LOCAL, rowVisible = true),
        )
        assertEquals(
            ExportDestination.Choice.LOCAL,
            ExportDestination.settled(ExportDestination.Choice.LOCAL, rowVisible = false),
        )
    }

    @Test
    fun `a connected account is simply selected`() {
        assertEquals(
            ExportDestination.Tap.SELECT,
            ExportDestination.onCloudTap(status(connected = true, configured = true)),
        )
    }

    @Test
    fun `a build with no credentials says so before it says anything about an account`() {
        assertEquals(
            ExportDestination.Tap.NOT_CONFIGURED,
            ExportDestination.onCloudTap(status(connected = false, configured = false)),
        )
    }

    @Test
    fun `no account offers Connect`() {
        assertEquals(
            ExportDestination.Tap.OFFER_CONNECT,
            ExportDestination.onCloudTap(status(connected = false, configured = true)),
        )
    }

    @Test
    fun `a provider that did not answer still offers Connect — never a silent select`() {
        assertEquals(ExportDestination.Tap.OFFER_CONNECT, ExportDestination.onCloudTap(null))
    }

    @Test
    fun `the provider's own name wins, the extension label stands in`() {
        assertEquals(
            "Google Drive",
            ExportDestination.providerName(status(connected = true, configured = true), "NSE · Google Drive"),
        )
        assertEquals("NSE · Google Drive", ExportDestination.providerName(null, "NSE · Google Drive"))
    }
}
