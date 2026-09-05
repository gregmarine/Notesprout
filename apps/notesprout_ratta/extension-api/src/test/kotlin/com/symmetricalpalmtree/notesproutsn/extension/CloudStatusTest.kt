package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [CloudStatus]'s constructor `require`s — unmarshal is the validation (family rule). A real
 * `Parcel` round trip is not available here (plain JVM, no Robolectric), so what is pinned is the
 * gate either side of the wire, and that the label never reaches `toString`.
 */
class CloudStatusTest {

    private fun assertRefused(build: () -> CloudStatus) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun theThreeHonestStates() {
        val unconfigured = CloudStatus(connected = false, configured = false, accountLabel = "", providerName = "Google Drive")
        val disconnected = CloudStatus(connected = false, configured = true, accountLabel = "", providerName = "Google Drive")
        val connected = CloudStatus(connected = true, configured = true, accountLabel = "someone@example.com", providerName = "Google Drive")
        assertFalse(unconfigured.connected)
        assertFalse(unconfigured.configured)
        assertFalse(disconnected.connected)
        assertTrue(disconnected.configured)
        assertTrue(connected.connected)
        assertEquals("someone@example.com", connected.accountLabel)
    }

    @Test
    fun connectedImpliesConfigured() {
        assertRefused { CloudStatus(connected = true, configured = false, accountLabel = "a@b.c", providerName = "Drive") }
    }

    @Test
    fun aLabelNeedsAConnection() {
        assertRefused { CloudStatus(connected = false, configured = true, accountLabel = "a@b.c", providerName = "Drive") }
    }

    @Test
    fun aConnectedStatusMayHaveNoLabel() {
        // The provider knows it is connected but has not (yet) learned the account's label.
        val s = CloudStatus(connected = true, configured = true, accountLabel = "", providerName = "Drive")
        assertTrue(s.connected)
    }

    @Test
    fun theLabelIsBoundedDisplayText() {
        assertRefused { CloudStatus(true, true, "x".repeat(CloudContract.MAX_ACCOUNT_LABEL_CHARS + 1), "Drive") }
        assertRefused { CloudStatus(true, true, "a" + '\u0007' + "b", "Drive") }
        CloudStatus(true, true, "x".repeat(CloudContract.MAX_ACCOUNT_LABEL_CHARS), "Drive")
    }

    @Test
    fun theProviderNameIsNonBlankTrimmedDisplayText() {
        assertRefused { CloudStatus(false, true, "", "") }
        assertRefused { CloudStatus(false, true, "", "   ") }
        assertRefused { CloudStatus(false, true, "", " Drive") }
        assertRefused { CloudStatus(false, true, "", "Drive ") }
        assertRefused { CloudStatus(false, true, "", "x".repeat(CloudContract.MAX_PROVIDER_NAME_CHARS + 1)) }
        CloudStatus(false, true, "", "x".repeat(CloudContract.MAX_PROVIDER_NAME_CHARS))
    }

    @Test
    fun theLabelNeverReachesToString() {
        val s = CloudStatus(true, true, "someone@example.com", "Google Drive")
        assertFalse(s.toString().contains("someone"))
        assertTrue(s.toString().contains("Google Drive"))
    }

    @Test
    fun equalityIsByValue() {
        val a = CloudStatus(true, true, "a@b.c", "Drive")
        val b = CloudStatus(true, true, "a@b.c", "Drive")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == CloudStatus(true, true, "z@b.c", "Drive"))
        assertFalse(a == CloudStatus(false, true, "", "Drive"))
    }
}
