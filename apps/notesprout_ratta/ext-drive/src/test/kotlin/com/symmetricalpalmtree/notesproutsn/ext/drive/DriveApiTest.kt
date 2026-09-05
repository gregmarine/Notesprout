package com.symmetricalpalmtree.notesproutsn.ext.drive

import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder

/**
 * The REST core over a fake transport (arc 25 / V2) — the path walk, the root cache, replace-by-name
 * and both upload shapes, all without a socket. The URLs are matched **decoded**, so a test reads
 * the way Drive's query language reads.
 */
class DriveApiTest {

    private val ROOT_NAME = "Notesprout SN Dev"

    private val fakeStore = FakeDriveStore()
    private val store = DriveStore(fakeStore)
    private val transport = FakeTransport()
    private val cache = TokenCache().apply { put("ACCESS", Long.MAX_VALUE) }
    private val tokens = TokenSource(store, cache, transport, "CLIENT", "SECRET") { 0L }
    private val api = DriveApi(transport, tokens, store, ROOT_NAME)

    private fun handle(block: (method: String, url: String) -> HttpReply?) {
        transport.handler = { request ->
            val url = URLDecoder.decode(request.url, "UTF-8")
            block(request.method, url) ?: throw AssertionError("unscripted ${request.method} $url")
        }
    }

    /** The usual world: the root already exists as `ROOT`, and every find is answered from [tree]. */
    private fun withRoot(tree: (method: String, url: String) -> HttpReply?) {
        store.put(DriveSql.Keys.ROOT_FOLDER_ID, "ROOT")
        handle { method, url ->
            when {
                method == "GET" && url.contains("/files/ROOT?fields=") ->
                    FakeTransport.ok(FakeTransport.file("ROOT", ROOT_NAME, folder = true))
                else -> tree(method, url)
            }
        }
    }

    // ── The root ─────────────────────────────────────────────────────────────

    @Test
    fun theRoot_isFoundUnderMyDriveAndCached() {
        handle { method, url ->
            when {
                method == "GET" && url.contains("name = '$ROOT_NAME' and 'root' in parents") ->
                    FakeTransport.ok(FakeTransport.fileList(FakeTransport.file("ROOT", ROOT_NAME, folder = true)))
                else -> null
            }
        }
        assertEquals("ROOT", api.rootId())
        assertEquals("ROOT", store.value(DriveSql.Keys.ROOT_FOLDER_ID))
    }

    @Test
    fun theRoot_isCreatedWhenItIsNotThere() {
        handle { method, url ->
            when {
                method == "GET" && url.contains("name = '$ROOT_NAME' and 'root' in parents") ->
                    FakeTransport.ok(FakeTransport.fileList())
                method == "POST" && url.startsWith(DriveRest.FILES) ->
                    FakeTransport.ok(FakeTransport.file("NEW", ROOT_NAME, folder = true))
                else -> null
            }
        }
        assertEquals("NEW", api.rootId())
        assertEquals("NEW", store.value(DriveSql.Keys.ROOT_FOLDER_ID))
    }

    @Test
    fun aCachedRoot_costsOneMetadataReadAndNoSearch() {
        store.put(DriveSql.Keys.ROOT_FOLDER_ID, "ROOT")
        handle { method, url ->
            if (method == "GET" && url.contains("/files/ROOT?fields=")) {
                FakeTransport.ok(FakeTransport.file("ROOT", ROOT_NAME, folder = true))
            } else {
                null
            }
        }
        assertEquals("ROOT", api.rootId())
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun aCachedRootThatIsGone_isDroppedAndReResolvedOnce() {
        store.put(DriveSql.Keys.ROOT_FOLDER_ID, "STALE")
        handle { method, url ->
            when {
                method == "GET" && url.contains("/files/STALE?fields=") -> HttpReply(404, "{}", emptyMap())
                method == "GET" && url.contains("name = '$ROOT_NAME' and 'root' in parents") ->
                    FakeTransport.ok(FakeTransport.fileList(FakeTransport.file("FRESH", ROOT_NAME, folder = true)))
                else -> null
            }
        }
        assertEquals("FRESH", api.rootId())
        assertEquals("FRESH", store.value(DriveSql.Keys.ROOT_FOLDER_ID))
    }

    // ── Folders by name ──────────────────────────────────────────────────────

    @Test
    fun ensureFolder_neverCreatesBesideAnExistingName() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'Exports' and 'ROOT' in parents") ->
                    FakeTransport.ok(FakeTransport.fileList(FakeTransport.file("EXPORTS", "Exports", folder = true)))
                else -> null
            }
        }
        assertEquals("EXPORTS", api.ensurePath(arrayOf("Exports")).id)
        // A create would have been a POST; there is none.
        assertTrue(transport.calls.none { it.method == "POST" })
    }

    @Test
    fun ensureFolder_takesTheFirstOfSameNamedSiblings() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'Exports'") -> FakeTransport.ok(
                    FakeTransport.fileList(
                        FakeTransport.file("FIRST", "Exports", folder = true),
                        FakeTransport.file("SECOND", "Exports", folder = true),
                    )
                )
                else -> null
            }
        }
        assertEquals("FIRST", api.ensurePath(arrayOf("Exports")).id)
    }

    @Test
    fun ensurePath_walksTheSegmentsAndCreatesTheMissingOnes() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'Exports' and 'ROOT' in parents") ->
                    FakeTransport.ok(FakeTransport.fileList(FakeTransport.file("EXPORTS", "Exports", folder = true)))
                method == "GET" && url.contains("name = 'Trips' and 'EXPORTS' in parents") ->
                    FakeTransport.ok(FakeTransport.fileList())
                method == "POST" && url.startsWith(DriveRest.FILES) ->
                    FakeTransport.ok(FakeTransport.file("TRIPS", "Trips", folder = true))
                else -> null
            }
        }
        assertEquals("TRIPS", api.ensurePath(arrayOf("Exports", "Trips")).id)
    }

    @Test
    fun anEmptyPath_isTheRootItself() {
        withRoot { _, _ -> null }
        val entry = api.ensurePath(emptyArray())
        assertEquals("ROOT", entry.id)
        assertEquals(ROOT_NAME, entry.name)
        assertTrue(entry.isFolder)
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    @Test
    fun list_ofAPathThatIsNotThere_isEmptyNotAFailure() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'Nowhere'") -> FakeTransport.ok(FakeTransport.fileList())
                else -> null
            }
        }
        assertTrue(api.list(arrayOf("Nowhere")).isEmpty())
    }

    @Test
    fun list_sortsFoldersFirstThenNames() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("'ROOT' in parents and trashed = false") &&
                    !url.contains("name = ") -> FakeTransport.ok(
                    FakeTransport.fileList(
                        FakeTransport.file("F1", "zebra.soil", size = "3"),
                        FakeTransport.file("D1", "Backups", folder = true),
                        FakeTransport.file("F2", "Apple.soil", size = "4"),
                    )
                )
                else -> null
            }
        }
        assertEquals(listOf("Backups", "Apple.soil", "zebra.soil"), api.list(emptyArray()).map { it.name })
    }

    @Test
    fun list_pagesUntilThereIsNoTokenLeft() {
        var page = 0
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("'ROOT' in parents") && !url.contains("name = ") -> {
                    page++
                    if (page == 1) {
                        FakeTransport.ok("""{"files":[${FakeTransport.file("A", "a.soil")}],"nextPageToken":"P2"}""")
                    } else {
                        FakeTransport.ok(FakeTransport.fileList(FakeTransport.file("B", "b.soil")))
                    }
                }
                else -> null
            }
        }
        assertEquals(listOf("a.soil", "b.soil"), api.list(emptyArray()).map { it.name })
        assertEquals(2, page)
    }

    @Test
    fun list_truncatesRatherThanFailsAndStopsAskingForMore() {
        val rows = (1..CloudContract.MAX_LIST_ENTRIES + 5).map { FakeTransport.file("ID$it", "f$it.soil") }
        var pages = 0
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("'ROOT' in parents") && !url.contains("name = ") -> {
                    pages++
                    FakeTransport.ok("""{"files":[${rows.joinToString(",")}],"nextPageToken":"MORE"}""")
                }
                else -> null
            }
        }
        assertEquals(CloudContract.MAX_LIST_ENTRIES, api.list(emptyArray()).size)
        assertEquals(1, pages)
    }

    // ── Uploads ──────────────────────────────────────────────────────────────

    @Test
    fun aSmallUpload_isOneMultipartCreateWhenTheNameIsFree() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'a.soil'") -> FakeTransport.ok(FakeTransport.fileList())
                method == "POST" && url.contains("uploadType=multipart") ->
                    FakeTransport.ok(FakeTransport.file("NEW", "a.soil", size = "5"))
                else -> null
            }
        }
        val entry = api.upload(emptyArray(), "a.soil", "application/octet-stream", ByteArrayInputStream(ByteArray(5)), 5L)
        assertEquals("NEW", entry.id)
        assertEquals(5L, entry.sizeBytes)
    }

    @Test
    fun aSmallUpload_replacesByNameWithAPatchOnTheExistingId() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'a.soil'") ->
                    FakeTransport.ok(FakeTransport.fileList(FakeTransport.file("OLD", "a.soil", size = "1")))
                method == "PATCH" && url.contains("/files/OLD?uploadType=multipart") ->
                    FakeTransport.ok(FakeTransport.file("OLD", "a.soil", size = "5"))
                else -> null
            }
        }
        val entry = api.upload(emptyArray(), "a.soil", "application/octet-stream", ByteArrayInputStream(ByteArray(5)), 5L)
        assertEquals("OLD", entry.id)
        assertTrue(transport.calls.none { it.method == "POST" })
    }

    @Test
    fun theMultipartBody_isPrefixThenTheBytesThenSuffix() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'a.soil'") -> FakeTransport.ok(FakeTransport.fileList())
                method == "POST" && url.contains("uploadType=multipart") ->
                    FakeTransport.ok(FakeTransport.file("NEW", "a.soil", size = "3"))
                else -> null
            }
        }
        api.upload(emptyArray(), "a.soil", "text/plain", ByteArrayInputStream("abc".toByteArray()), 3L)
        val written = transport.calls.single { it.method == "POST" }.bodyText
        assertTrue(written.startsWith("--${DriveMultipart.BOUNDARY}\r\nContent-Type: application/json"))
        assertTrue(written.contains("Content-Type: text/plain\r\n\r\nabc\r\n"))
        assertTrue(written.endsWith("--${DriveMultipart.BOUNDARY}--\r\n"))
    }

    @Test
    fun aShortSource_refusesTheUpload() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'a.soil'") -> FakeTransport.ok(FakeTransport.fileList())
                else -> null
            }
        }
        try {
            api.upload(emptyArray(), "a.soil", "text/plain", ByteArrayInputStream(ByteArray(2)), 10L)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(ExactCopy.SHORT_READ, e.message)
        }
    }

    @Test
    fun aLargeUpload_takesAResumableSessionAndPutsTheBytesToTheLocation() {
        val big = DriveMultipart.MULTIPART_MAX_BYTES + 1
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'big.soil'") -> FakeTransport.ok(FakeTransport.fileList())
                method == "POST" && url.contains("uploadType=resumable") ->
                    HttpReply(200, "", mapOf("location" to "https://upload.example/session"))
                method == "PUT" && url == "https://upload.example/session" ->
                    FakeTransport.ok(FakeTransport.file("BIG", "big.soil", size = big.toString()))
                else -> null
            }
        }
        val entry = api.upload(
            emptyArray(), "big.soil", "application/octet-stream",
            ByteArrayInputStream(ByteArray(big.toInt())), big,
        )
        assertEquals("BIG", entry.id)
        val init = transport.calls.single { it.method == "POST" }
        assertEquals(big.toString(), init.request.headers["X-Upload-Content-Length"])
        // The session URI carries its own authorization: no bearer is sent with the bytes.
        assertNull(transport.calls.single { it.method == "PUT" }.request.headers["Authorization"])
    }

    @Test
    fun aResumableSessionWithNoLocation_isRefused() {
        val big = DriveMultipart.MULTIPART_MAX_BYTES + 1
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'big.soil'") -> FakeTransport.ok(FakeTransport.fileList())
                method == "POST" && url.contains("uploadType=resumable") -> FakeTransport.ok("")
                else -> null
            }
        }
        try {
            api.upload(emptyArray(), "big.soil", "application/octet-stream", ByteArrayInputStream(ByteArray(1)), big)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals("no upload session", e.message)
        }
    }

    @Test
    fun uploadingOverAFolderOfTheSameName_isRefusedRatherThanDuplicated() {
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'Exports'") ->
                    FakeTransport.ok(FakeTransport.fileList(FakeTransport.file("EXPORTS", "Exports", folder = true)))
                else -> null
            }
        }
        try {
            api.upload(emptyArray(), "Exports", "text/plain", ByteArrayInputStream(ByteArray(1)), 1L)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals("name is a folder", e.message)
        }
    }

    // ── Download and delete ──────────────────────────────────────────────────

    @Test
    fun download_streamsTheBytesAndAnswersTheCount() {
        handle { method, url ->
            if (method == "GET" && url.contains("/files/FILE?fields=")) {
                FakeTransport.ok(FakeTransport.file("FILE", "a.soil", size = "4"))
            } else {
                null
            }
        }
        transport.streamHandler = { _, out ->
            out.write("abcd".toByteArray())
            HttpStreamReply(200, 4L, "")
        }
        val sink = ByteArrayOutputStream()
        assertEquals(4L, api.download("FILE", sink))
        assertEquals("abcd", sink.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun downloadingAFolder_isTheCallersMistake() {
        handle { method, url ->
            if (method == "GET" && url.contains("/files/DIR?fields=")) {
                FakeTransport.ok(FakeTransport.file("DIR", "Exports", folder = true))
            } else {
                null
            }
        }
        try {
            api.download("DIR", ByteArrayOutputStream())
            throw AssertionError("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertEquals("entry is a folder", e.message)
        }
    }

    @Test
    fun anIdTheProviderNoLongerKnows_isGone() {
        handle { method, url ->
            if (method == "GET" && url.contains("/files/DEAD?fields=")) HttpReply(404, "{}", emptyMap()) else null
        }
        try {
            api.download("DEAD", ByteArrayOutputStream())
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(DriveApi.GONE, e.message)
        }
    }

    @Test
    fun delete_acceptsBoth204AndAlreadyGone() {
        handle { method, _ -> if (method == "DELETE") HttpReply(204, "", emptyMap()) else null }
        api.delete("FILE")
        handle { method, _ -> if (method == "DELETE") HttpReply(404, "", emptyMap()) else null }
        api.delete("FILE")
    }

    @Test
    fun delete_refusesOnAnythingElse() {
        handle { method, _ -> if (method == "DELETE") HttpReply(403, "", emptyMap()) else null }
        try {
            api.delete("FILE")
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals("http 403", e.message)
        }
    }

    // ── The account, and the failure edges ───────────────────────────────────

    @Test
    fun about_readsTheEmail() {
        handle { method, url ->
            if (method == "GET" && url.startsWith(DriveRest.ABOUT.substringBefore('?'))) {
                FakeTransport.ok("""{"user":{"emailAddress":"person@example.com"}}""")
            } else {
                null
            }
        }
        assertEquals("person@example.com", api.about())
    }

    @Test
    fun aServerSideFailure_readsAsTheNetwork() {
        handle { _, _ -> HttpReply(503, "", emptyMap()) }
        try {
            api.about()
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(CloudContract.NETWORK, e.message)
        }
    }

    @Test
    fun anOrdinaryFourHundred_namesItsCode() {
        handle { _, _ -> HttpReply(403, "", emptyMap()) }
        try {
            api.about()
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals("http 403", e.message)
        }
    }

    @Test
    fun oneUnauthorized_costsARefreshAndOneRetry() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        var seen = 0
        transport.handler = { request ->
            when {
                request.url == DriveAuth.TOKEN_URL -> FakeTransport.ok("""{"access_token":"FRESH","expires_in":3600}""")
                else -> {
                    seen++
                    if (seen == 1) HttpReply(401, "", emptyMap())
                    else FakeTransport.ok("""{"user":{"emailAddress":"person@example.com"}}""")
                }
            }
        }
        assertEquals("person@example.com", api.about())
        assertEquals(2, seen)
    }

    @Test
    fun aSecondUnauthorized_isNotConnected() {
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        transport.handler = { request ->
            if (request.url == DriveAuth.TOKEN_URL) FakeTransport.ok("""{"access_token":"FRESH"}""")
            else HttpReply(401, "", emptyMap())
        }
        try {
            api.about()
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(CloudContract.NOT_CONNECTED, e.message)
        }
    }

    @Test
    fun anUnauthorizedUpload_isNeverReplayed() {
        // An fd cannot be rewound, so a 401 on the byte-streaming leg refuses at once.
        store.put(DriveSql.Keys.REFRESH_TOKEN, "REFRESH")
        withRoot { method, url ->
            when {
                method == "GET" && url.contains("name = 'a.soil'") -> FakeTransport.ok(FakeTransport.fileList())
                method == "POST" && url.contains("uploadType=multipart") -> HttpReply(401, "", emptyMap())
                else -> null
            }
        }
        try {
            api.upload(emptyArray(), "a.soil", "text/plain", ByteArrayInputStream(ByteArray(3)), 3L)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(CloudContract.NOT_CONNECTED, e.message)
        }
        assertEquals(1, transport.calls.count { it.url.contains("uploadType=multipart") })
    }
}
