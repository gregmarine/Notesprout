package com.notesprout.android.data.backup

import org.junit.Assert.*
import org.junit.Test

class DriveQueryTest {

    private val client = DriveApiClient("dummy-token-for-tests")

    @Test
    fun escapeDriveString_plain() {
        assertEquals("hello", client.escapeDriveString("hello"))
    }

    @Test
    fun escapeDriveString_singleQuote() {
        assertEquals("it\\'s", client.escapeDriveString("it's"))
    }

    @Test
    fun escapeDriveString_backslash() {
        assertEquals("a\\\\b", client.escapeDriveString("a\\b"))
    }

    @Test
    fun escapeDriveString_bothSpecial() {
        assertEquals("a\\\\\\'b", client.escapeDriveString("a\\'b"))
    }

    @Test
    fun buildChildQuery_withoutFolderFilter() {
        val q = client.buildChildQuery("myfile.soil", "parent123", foldersOnly = false)
        assertEquals(
            "name = 'myfile.soil' and 'parent123' in parents and trashed = false",
            q
        )
    }

    @Test
    fun buildChildQuery_withFolderFilter() {
        val q = client.buildChildQuery("Backups", "root", foldersOnly = true)
        assertTrue(q.contains("mimeType = 'application/vnd.google-apps.folder'"))
        assertTrue(q.contains("'root' in parents"))
    }

    @Test
    fun buildChildQuery_escapesNameQuotes() {
        val q = client.buildChildQuery("O'Brien's Notes", "parent456", foldersOnly = false)
        assertTrue(q.contains("name = 'O\\'Brien\\'s Notes'"))
    }
}
