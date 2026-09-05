package com.symmetricalpalmtree.notesproutsn.ext.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/** Drive's query language and URLs (arc 25 / V2) — pure, so the escaping that keeps a file called
 *  `Don't Panic` from rewriting a query is proved without a network. */
class DriveRestTest {

    @Test
    fun escape_leavesAnOrdinaryNameAlone() {
        assertEquals("Exports", DriveRest.escape("Exports"))
    }

    @Test
    fun escape_escapesASingleQuote() {
        assertEquals("Don\\'t Panic", DriveRest.escape("Don't Panic"))
    }

    @Test
    fun escape_escapesABackslash() {
        assertEquals("a\\\\b", DriveRest.escape("a\\b"))
    }

    @Test
    fun escape_doesBackslashBeforeQuote_soAnEscapeIsNotDoubled() {
        // Backslash first, then quote: the other order would escape the backslash this step adds.
        assertEquals("a\\\\\\'b", DriveRest.escape("a\\'b"))
    }

    @Test
    fun childQuery_namesTheParentAndExcludesTrash() {
        val q = DriveRest.childQuery("PARENT", "Exports", foldersOnly = false)
        assertEquals("name = 'Exports' and 'PARENT' in parents and trashed = false", q)
    }

    @Test
    fun childQuery_foldersOnly_addsTheFolderMime() {
        val q = DriveRest.childQuery("PARENT", "Exports", foldersOnly = true)
        assertTrue(q.endsWith("and mimeType = '${DriveRest.FOLDER_MIME}'"))
    }

    @Test
    fun childQuery_escapesTheNameIntoTheLiteral() {
        val q = DriveRest.childQuery("PARENT", "Don't Panic", foldersOnly = false)
        assertTrue(q.startsWith("name = 'Don\\'t Panic'"))
    }

    @Test
    fun childrenQuery_isParentAndTrashOnly() {
        assertEquals("'PARENT' in parents and trashed = false", DriveRest.childrenQuery("PARENT"))
    }

    @Test
    fun findUrl_carriesTheEncodedQueryTheFieldsAndTheSmallPageSize() {
        val url = DriveRest.findUrl("PARENT", "Exports", foldersOnly = true)
        val expected = URLEncoder.encode(DriveRest.childQuery("PARENT", "Exports", true), "UTF-8")
        assertTrue(url.startsWith("${DriveRest.FILES}?q=$expected"))
        assertTrue(url.contains("fields=files(${DriveRest.ENTRY_FIELDS})"))
        assertTrue(url.endsWith("pageSize=${DriveRest.FIND_PAGE_SIZE}"))
    }

    @Test
    fun listUrl_asksForTheBigPageAndTheNextPageToken() {
        val url = DriveRest.listUrl("PARENT", null)
        assertTrue(url.contains("fields=nextPageToken,files(${DriveRest.ENTRY_FIELDS})"))
        assertTrue(url.contains("pageSize=${DriveRest.LIST_PAGE_SIZE}"))
        assertFalse(url.contains("pageToken="))
    }

    @Test
    fun listUrl_appendsAnEncodedPageToken() {
        val url = DriveRest.listUrl("PARENT", "tok en")
        assertTrue(url.endsWith("&pageToken=tok+en"))
    }

    @Test
    fun uploadUrls_pickTheRightShapeForCreateAndReplace() {
        assertTrue(DriveRest.multipartCreateUrl().startsWith("${DriveRest.UPLOAD}?uploadType=multipart"))
        assertTrue(DriveRest.multipartUpdateUrl("FILE").startsWith("${DriveRest.UPLOAD}/FILE?uploadType=multipart"))
        assertTrue(DriveRest.resumableCreateUrl().startsWith("${DriveRest.UPLOAD}?uploadType=resumable"))
        assertTrue(DriveRest.resumableUpdateUrl("FILE").startsWith("${DriveRest.UPLOAD}/FILE?uploadType=resumable"))
    }

    @Test
    fun mediaUrl_isTheOneThatStreamsBytes() {
        assertEquals("${DriveRest.FILES}/FILE?alt=media", DriveRest.mediaUrl("FILE"))
    }
}
