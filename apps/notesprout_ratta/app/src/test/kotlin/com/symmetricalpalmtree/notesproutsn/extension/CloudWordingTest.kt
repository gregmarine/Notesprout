package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Backup screen's Cloud line (arc 25 / V2). Four cases and an order that matters — a build with
 * no credentials must say so before it says anything about an account.
 */
class CloudWordingTest {

    private val words = CloudWords(
        notConnected = "not connected",
        connected = "connected",
        notConfigured = "not configured",
        unavailable = "unavailable",
    )

    private fun status(connected: Boolean, configured: Boolean, label: String) =
        CloudStatus(connected, configured, label, "Google Drive")

    @Test
    fun `an unconfigured build says so and nothing else`() {
        assertEquals(
            "Google Drive: not configured",
            CloudWording.statusLine(status(connected = false, configured = false, label = ""), words),
        )
    }

    @Test
    fun `configured with no account reads not connected`() {
        assertEquals(
            "Google Drive: not connected",
            CloudWording.statusLine(status(connected = false, configured = true, label = ""), words),
        )
    }

    @Test
    fun `a connected account prints its label`() {
        assertEquals(
            "Google Drive: someone@example.com",
            CloudWording.statusLine(
                status(connected = true, configured = true, label = "someone@example.com"), words,
            ),
        )
    }

    @Test
    fun `a connected account with no label reads connected`() {
        assertEquals(
            "Google Drive: connected",
            CloudWording.statusLine(status(connected = true, configured = true, label = ""), words),
        )
    }

    @Test
    fun `a provider that did not answer reads unavailable`() {
        assertEquals("Google Drive: unavailable", CloudWording.unavailableLine("Google Drive", words))
    }

    @Test
    fun `the joiner is the caller's`() {
        assertEquals(
            "Google Drive — not connected",
            CloudWording.statusLine(status(false, configured = true, label = ""), words) { p, d -> "$p — $d" },
        )
    }

    @Test
    fun `only a live connection turns the button over`() {
        assertTrue(CloudWording.showsDisconnect(status(true, configured = true, label = "a@b.c")))
        assertFalse(CloudWording.showsDisconnect(status(false, configured = true, label = "")))
        assertFalse(CloudWording.showsDisconnect(status(false, configured = false, label = "")))
        assertFalse("an unavailable provider still offers Connect", CloudWording.showsDisconnect(null))
    }
}
