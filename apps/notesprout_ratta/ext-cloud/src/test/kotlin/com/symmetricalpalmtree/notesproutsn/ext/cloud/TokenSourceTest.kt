package com.symmetricalpalmtree.notesproutsn.ext.cloud

import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The access token's life (arc 25 / V2): cached while fresh, refreshed when stale, and the account
 *  forgotten the moment Google says the refresh token is dead. */
class TokenSourceTest {

    private val fake = FakeDriveStore()
    private val store = DriveStore(fake)
    private val transport = FakeTransport()
    private val cache = TokenCache()
    private var now = 1_000_000L

    private fun source() = TokenSource(store, cache, transport, "CLIENT", "SECRET") { now }

    @Test
    fun aFreshCachedToken_costsNoCallAtAll() {
        cache.put("ACCESS", now + 60_000L)
        assertEquals("ACCESS", source().access())
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun aStaleToken_isRefreshedAndRecached() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        cache.put("OLD", now - 1L)
        transport.handler = { FakeTransport.ok("""{"access_token":"NEW","expires_in":3600}""") }
        assertEquals("NEW", source().access())
        assertEquals(1, transport.calls.size)
        assertEquals(DriveAuth.TOKEN_URL, transport.calls[0].url)
        // Cached now, so a second ask is free.
        assertEquals("NEW", source().access())
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun theRefreshBody_carriesTheGrantTypeAndTheStoredToken() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        transport.handler = { FakeTransport.ok("""{"access_token":"NEW"}""") }
        source().access()
        val body = transport.calls[0].bodyText
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=REFRESH"))
        assertTrue(body.contains("client_id=CLIENT"))
    }

    @Test
    fun noTokenInTheStore_isNotConnected() {
        try {
            source().access()
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(CloudContract.NOT_CONNECTED, e.message)
        }
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun aBlankTokenInTheStore_isNotConnected() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "   ")
        try {
            source().access()
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(CloudContract.NOT_CONNECTED, e.message)
        }
    }

    @Test
    fun aRevokedToken_forgetsTheWholeAccountAndRefuses() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        store.put(DriveSql.Keys.ACCOUNT_LABEL, "person@example.com")
        store.put(DriveSql.Keys.ROOT_FOLDER_ID, "ROOT")
        transport.handler = { HttpReply(400, """{"error":"invalid_grant"}""", emptyMap()) }
        try {
            source().access()
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(CloudContract.NOT_CONNECTED, e.message)
        }
        assertNull(store.value(DriveSql.Keys.REFRESH_TOKEN))
        assertNull(store.value(DriveSql.Keys.ACCOUNT_LABEL))
        assertNull(store.value(DriveSql.Keys.ROOT_FOLDER_ID))
        assertNull(cache.peek(now))
    }

    @Test
    fun anyOtherRefreshFailure_isTheNetwork() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        transport.handler = { HttpReply(400, """{"error":"invalid_client"}""", emptyMap()) }
        try {
            source().access()
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(CloudContract.NETWORK, e.message)
        }
        // Nothing was forgotten — nothing changed.
        assertEquals("REFRESH", store.value(DriveSql.Keys.REFRESH_TOKEN))
    }

    @Test
    fun invalidate_forcesTheNextAskToRefresh() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        cache.put("ACCESS", now + 60_000L)
        val tokens = source()
        tokens.invalidate()
        transport.handler = { FakeTransport.ok("""{"access_token":"NEW"}""") }
        assertEquals("NEW", tokens.access())
    }

    @Test
    fun revoke_postsTheStoredTokenToTheRevokeEndpoint() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        transport.handler = { FakeTransport.ok("") }
        source().revoke()
        assertEquals(DriveAuth.REVOKE_URL, transport.calls.single().url)
        assertEquals("token=REFRESH", transport.calls.single().bodyText)
    }

    @Test
    fun revoke_withNothingToRevoke_doesNothing() {
        source().revoke()
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun revoke_swallowsAFailure() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        transport.handler = { throw DriveFailures.network() }
        source().revoke()
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun theCache_expiresWithTheSkewAlreadyApplied() {
        cache.put("ACCESS", 2_000L)
        assertEquals("ACCESS", cache.peek(1_999L))
        assertNull(cache.peek(2_000L))
        cache.clear()
        assertNull(cache.peek(0L))
    }
}
