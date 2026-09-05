package com.symmetricalpalmtree.notesproutsn.ext.cloud

import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder

/**
 * What [CloudService] does, tested where it can be (arc 25 / V2) — a `Binder.Stub` cannot be
 * instantiated on the JVM, so the service is a thin shell over [DriveOps] and this is where the five
 * operations, `status` and `disconnect` are actually proved.
 */
class DriveOpsTest {

    private val ROOT_NAME = "Notesprout SN Dev"
    private val PROVIDER = "Google Drive"

    private val fakeStore = FakeDriveStore()
    private val store = DriveStore(fakeStore)
    private val transport = FakeTransport()
    private val cache = TokenCache().apply { put("ACCESS", Long.MAX_VALUE) }
    private val tokens = TokenSource(store, cache, transport, "CLIENT", "SECRET") { 0L }
    private val api = DriveApi(transport, tokens, store, ROOT_NAME)

    private fun ops(configured: Boolean = true) = DriveOps(store, api, tokens, PROVIDER, configured)

    private fun withRoot(tree: (method: String, url: String) -> HttpReply?) {
        store.put(DriveSql.Keys.ROOT_FOLDER_ID, "ROOT")
        transport.handler = { request ->
            val url = URLDecoder.decode(request.url, "UTF-8")
            when {
                request.method == "GET" && url.contains("/files/ROOT?fields=") ->
                    FakeTransport.ok(FakeTransport.file("ROOT", ROOT_NAME, folder = true))
                else -> tree(request.method, url) ?: throw AssertionError("unscripted ${request.method} $url")
            }
        }
    }

    // ── status ───────────────────────────────────────────────────────────────

    @Test
    fun status_isNotConnectedOnAFreshStore() {
        val status = ops().status()
        assertFalse(status.connected)
        assertTrue(status.configured)
        assertEquals("", status.accountLabel)
        assertEquals(PROVIDER, status.providerName)
        // Never the network.
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun status_isConnectedWithItsLabelOnceATokenIsStored() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        store.put(DriveSql.Keys.ACCOUNT_LABEL, "person@example.com")
        val status = ops().status()
        assertTrue(status.connected)
        assertEquals("person@example.com", status.accountLabel)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun status_connectedWithNoLabel_isLegal() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        val status = ops().status()
        assertTrue(status.connected)
        assertEquals("", status.accountLabel)
    }

    @Test
    fun status_anUnconfiguredBuildIsNeverConnected() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        store.put(DriveSql.Keys.ACCOUNT_LABEL, "person@example.com")
        val status = ops(configured = false).status()
        assertFalse(status.configured)
        assertFalse(status.connected)
        assertEquals("", status.accountLabel)
    }

    // ── disconnect ───────────────────────────────────────────────────────────

    @Test
    fun disconnect_revokesThenForgetsEverything() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        store.put(DriveSql.Keys.ACCOUNT_LABEL, "person@example.com")
        store.put(DriveSql.Keys.ROOT_FOLDER_ID, "ROOT")
        transport.handler = { FakeTransport.ok("") }
        ops().disconnect()
        assertEquals(DriveAuth.REVOKE_URL, transport.calls.single().url)
        assertNull(store.value(DriveSql.Keys.REFRESH_TOKEN))
        assertNull(store.value(DriveSql.Keys.ACCOUNT_LABEL))
        assertNull(store.value(DriveSql.Keys.ROOT_FOLDER_ID))
        assertNull(cache.peek(0L))
        assertFalse(ops().status().connected)
    }

    @Test
    fun disconnect_forgetsEvenWhenTheRevokeCouldNotBeMade() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        transport.handler = { throw DriveFailures.network() }
        ops().disconnect()
        assertNull(store.value(DriveSql.Keys.REFRESH_TOKEN))
    }

    @Test
    fun disconnect_onAnAccountThatWasNeverConnected_isFine() {
        ops().disconnect()
        assertTrue(transport.calls.isEmpty())
    }

    // ── the five file operations ─────────────────────────────────────────────

    @Test
    fun list_answersAnArrayTheSeamCanCarry() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("'ROOT' in parents") && !url.contains("name = ") ->
                    FakeTransport.ok(FakeTransport.fileList(FakeTransport.file("A", "a.soil", size = "9")))
                else -> null
            }
        }
        val entries = ops().list(emptyArray())
        assertEquals(1, entries.size)
        assertEquals("a.soil", entries[0].name)
        assertEquals(9L, entries[0].sizeBytes)
    }

    @Test
    fun list_withoutAnAccount_isNotConnected() {
        // No cached token and nothing in the store: the very first thing the root needs is a bearer.
        cache.clear()
        try {
            ops().list(emptyArray())
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(CloudContract.NOT_CONNECTED, e.message)
        }
    }

    @Test
    fun ensureFolder_answersTheDeepestSegment() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'Backups' and 'ROOT' in parents") ->
                    FakeTransport.ok(FakeTransport.fileList(FakeTransport.file("B", "Backups", folder = true)))
                method == "GET" && url.contains("name = 'Nomad' and 'B' in parents") ->
                    FakeTransport.ok(FakeTransport.fileList())
                method == "POST" && url.startsWith(DriveRest.FILES) ->
                    FakeTransport.ok(FakeTransport.file("N", "Nomad", folder = true))
                else -> null
            }
        }
        val entry = ops().ensureFolder(arrayOf("Backups", "Nomad"))
        assertEquals("N", entry.id)
        assertTrue(entry.isFolder)
    }

    @Test
    fun upload_answersTheEntryTheProviderReportsAfterTheWrite() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'a.soil'") -> FakeTransport.ok(FakeTransport.fileList())
                method == "POST" && url.contains("uploadType=multipart") ->
                    FakeTransport.ok(FakeTransport.file("NEW", "a.soil", size = "3", modifiedTime = "2026-09-04T10:00:00Z"))
                else -> null
            }
        }
        val entry = ops().upload(emptyArray(), "a.soil", "text/plain", ByteArrayInputStream("abc".toByteArray()), 3L)
        assertEquals("NEW", entry.id)
        assertEquals(3L, entry.sizeBytes)
        assertEquals(1_788_516_000_000L, entry.modifiedAt)
    }

    @Test
    fun upload_refusesASourceThatDoesNotHoldWhatTheHostPromised() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'a.soil'") -> FakeTransport.ok(FakeTransport.fileList())
                else -> null
            }
        }
        try {
            ops().upload(emptyArray(), "a.soil", "text/plain", ByteArrayInputStream(ByteArray(1)), 9L)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(ExactCopy.SHORT_READ, e.message)
        }
    }

    @Test
    fun download_streamsAndCounts() {
        transport.handler = { request ->
            val url = URLDecoder.decode(request.url, "UTF-8")
            if (url.contains("/files/FILE?fields=")) FakeTransport.ok(FakeTransport.file("FILE", "a.soil", size = "4"))
            else throw AssertionError("unscripted $url")
        }
        transport.streamHandler = { _, out ->
            out.write("wxyz".toByteArray())
            HttpStreamReply(200, 4L, "")
        }
        val sink = ByteArrayOutputStream()
        assertEquals(4L, ops().download("FILE", sink))
        assertEquals("wxyz", sink.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun delete_isIdempotentOnAnIdAlreadyGone() {
        transport.handler = { HttpReply(404, "", emptyMap()) }
        ops().delete("FILE")
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun aStoreThatCannotBeReached_surfacesAsTheProvidersOwnRefusal() {
        fakeStore.failWith = { RuntimeException("gone") }
        try {
            ops().status()
            throw AssertionError("expected a refusal")
        } catch (e: StoreUnavailable) {
            assertEquals(CloudService.STORE_UNAVAILABLE, DriveFailures.marshalable(e).message)
        }
    }
}
