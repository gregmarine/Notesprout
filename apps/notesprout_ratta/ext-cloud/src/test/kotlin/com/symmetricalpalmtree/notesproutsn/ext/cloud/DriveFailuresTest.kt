package com.symmetricalpalmtree.notesproutsn.ext.cloud

import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLHandshakeException

/**
 * The failure mapping table (arc 25 / V2) — the host compares two of these messages **verbatim**,
 * so they are asserted against [CloudContract]'s own constants rather than against a literal.
 */
class DriveFailuresTest {

    @Test
    fun theTwoTypedRefusals_carryTheSeamsExactMessages() {
        assertEquals(CloudContract.NOT_CONNECTED, DriveFailures.notConnected().message)
        assertEquals(CloudContract.NETWORK, DriveFailures.network().message)
        assertEquals("not connected", CloudContract.NOT_CONNECTED)
        assertEquals("network", CloudContract.NETWORK)
    }

    @Test
    fun everyIoFailure_readsAsTheNetwork() {
        assertTrue(DriveFailures.isNetwork(IOException("boom")))
        assertTrue(DriveFailures.isNetwork(UnknownHostException("dns")))
        assertTrue(DriveFailures.isNetwork(SocketTimeoutException("slow")))
        assertTrue(DriveFailures.isNetwork(ConnectException("refused")))
        assertTrue(DriveFailures.isNetwork(SSLHandshakeException("tls")))
        assertTrue(DriveFailures.isNetwork(GeneralSecurityException("tls")))
    }

    @Test
    fun aPlainRuntimeFailure_isNotTheNetwork() {
        assertFalse(DriveFailures.isNetwork(RuntimeException("boom")))
        assertFalse(DriveFailures.isNetwork(NullPointerException()))
    }

    @Test
    fun serverSideAndRateLimit_readAsTheNetwork() {
        assertTrue(DriveFailures.isRetryable(500))
        assertTrue(DriveFailures.isRetryable(503))
        assertTrue(DriveFailures.isRetryable(429))
        assertEquals(CloudContract.NETWORK, DriveFailures.forHttp(500).message)
        assertEquals(CloudContract.NETWORK, DriveFailures.forHttp(429).message)
    }

    @Test
    fun anOrdinaryFourHundred_namesItsCode() {
        assertFalse(DriveFailures.isRetryable(400))
        assertEquals("http 400", DriveFailures.forHttp(400).message)
        assertEquals("http 403", DriveFailures.forHttp(403).message)
        assertEquals("http 404", DriveFailures.forHttp(404).message)
    }

    @Test
    fun theThreeMarshalableTypes_passThroughUntouched() {
        val security = SecurityException("caller is not the host")
        val argument = IllegalArgumentException("path is null")
        val state = IllegalStateException(CloudContract.NOT_CONNECTED)
        assertSame(security, DriveFailures.marshalable(security))
        assertSame(argument, DriveFailures.marshalable(argument))
        assertSame(state, DriveFailures.marshalable(state))
    }

    @Test
    fun aStoreThatCannotBeReached_saysSo() {
        val refusal = DriveFailures.marshalable(StoreUnavailable(RuntimeException("gone")))
        assertEquals(CloudService.STORE_UNAVAILABLE, refusal.message)
        assertTrue(refusal is IllegalStateException)
    }

    @Test
    fun anIoFailureThatEscaped_stillReadsAsTheNetwork() {
        assertEquals(CloudContract.NETWORK, DriveFailures.marshalable(IOException("late")).message)
    }

    @Test
    fun anythingElse_becomesAMarshalableStateRefusalNamingOnlyTheClass() {
        val refusal = DriveFailures.marshalable(NullPointerException("something with a name in it"))
        assertTrue(refusal is IllegalStateException)
        assertEquals("provider failure (NullPointerException)", refusal.message)
    }
}
