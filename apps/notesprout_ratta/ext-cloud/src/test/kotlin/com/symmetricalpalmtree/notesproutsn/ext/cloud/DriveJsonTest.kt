package com.symmetricalpalmtree.notesproutsn.ext.cloud

import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drive's JSON, both directions (arc 25 / V2) — including the two facts about the wire everything
 *  else is allowed to forget: `size` is a string, `modifiedTime` is RFC 3339. */
class DriveJsonTest {

    @Test
    fun parseFileList_readsAFileWithAStringSize() {
        val page = DriveJson.parseFileList(
            FakeTransport.fileList(FakeTransport.file("ID1", "notes.soil", size = "12345", modifiedTime = "2026-09-04T10:00:00.000Z"))
        )
        val entry = page.entries.single()
        assertEquals("ID1", entry.id)
        assertEquals("notes.soil", entry.name)
        assertEquals(12345L, entry.sizeBytes)
        assertEquals(false, entry.isFolder)
        assertEquals(1_788_516_000_000L, entry.modifiedAt)
    }

    @Test
    fun parseFileList_aMissingModifiedTimeIsZero() {
        val page = DriveJson.parseFileList(FakeTransport.fileList(FakeTransport.file("ID1", "a.soil", size = "1")))
        assertEquals(0L, page.entries.single().modifiedAt)
    }

    @Test
    fun parseFileList_aMissingSizeIsZero() {
        val page = DriveJson.parseFileList(FakeTransport.fileList(FakeTransport.file("ID1", "a.soil")))
        assertEquals(0L, page.entries.single().sizeBytes)
    }

    @Test
    fun parseFileList_aFolderIsAFolderAndHasNoSize() {
        val page = DriveJson.parseFileList(FakeTransport.fileList(FakeTransport.file("ID1", "Exports", folder = true, size = "99")))
        val entry = page.entries.single()
        assertTrue(entry.isFolder)
        assertEquals(0L, entry.sizeBytes)
    }

    @Test
    fun parseFileList_skipsARowThisSeamCannotDescribe() {
        // A slash is a legal Drive name and an impossible CloudEntry name. One strange neighbour
        // must not make a folder unbrowsable.
        val page = DriveJson.parseFileList(
            FakeTransport.fileList(
                FakeTransport.file("ID1", "a/b"),
                FakeTransport.file("ID2", "fine.soil"),
            )
        )
        assertEquals(listOf("fine.soil"), page.entries.map { it.name })
    }

    @Test
    fun parseFileList_carriesTheNextPageToken() {
        val page = DriveJson.parseFileList("""{"files":[],"nextPageToken":"NEXT"}""")
        assertEquals("NEXT", page.nextPageToken)
    }

    @Test
    fun parseFileList_anEmptyTokenIsNoToken() {
        assertNull(DriveJson.parseFileList("""{"files":[],"nextPageToken":""}""").nextPageToken)
    }

    @Test
    fun parseFileList_ignoresAFieldGoogleAddsTomorrow() {
        val page = DriveJson.parseFileList("""{"kind":"drive#fileList","files":[{"id":"ID1","name":"a.soil","capabilities":{"canEdit":true}}]}""")
        assertEquals("ID1", page.entries.single().id)
    }

    @Test
    fun parseFile_readsASingleFile() {
        val entry = DriveJson.parseFile(FakeTransport.file("ID1", "a.soil", size = "7"))
        assertEquals(7L, entry?.sizeBytes)
    }

    @Test
    fun parseId_answersTheIdEvenWhenTheEntryIsUnusable() {
        assertEquals("ID1", DriveJson.parseId("""{"id":"ID1"}"""))
        assertNull(DriveJson.parseFile("""{"id":"ID1"}"""))
    }

    @Test
    fun anUnreadableBody_isTheProviderNotTheNetwork() {
        try {
            DriveJson.parseFileList("not json at all")
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(DriveJson.UNREADABLE, e.message)
        }
    }

    @Test
    fun parseAboutEmail_readsTheAddress() {
        assertEquals("person@example.com", DriveJson.parseAboutEmail("""{"user":{"emailAddress":"person@example.com"}}"""))
    }

    @Test
    fun parseAboutEmail_isEmptyWhenGoogleWouldNotSay() {
        assertEquals("", DriveJson.parseAboutEmail("""{}"""))
        assertEquals("", DriveJson.parseAboutEmail("""{"user":{}}"""))
    }

    @Test
    fun label_refusesSomethingTooLongOrUnprintable() {
        assertEquals("", DriveJson.label("x".repeat(CloudContract.MAX_ACCOUNT_LABEL_CHARS + 1)))
        assertEquals("", DriveJson.label("a\u0007b"))
        assertEquals("person@example.com", DriveJson.label("  person@example.com  "))
    }

    @Test
    fun epochMs_isZeroForAbsentOrGarbage() {
        assertEquals(0L, DriveJson.epochMs(null))
        assertEquals(0L, DriveJson.epochMs(""))
        assertEquals(0L, DriveJson.epochMs("last tuesday"))
    }

    @Test
    fun epochMs_isZeroBeforeTheEpoch() {
        assertEquals(0L, DriveJson.epochMs("1960-01-01T00:00:00Z"))
    }

    @Test
    fun sorted_putsFoldersFirstThenNamesCaseInsensitively() {
        val entries = listOf(
            entry("1", "zebra.soil"),
            entry("2", "Apple.soil"),
            entry("3", "Backups", folder = true),
            entry("4", "archive", folder = true),
        )
        assertEquals(
            listOf("archive", "Backups", "Apple.soil", "zebra.soil"),
            DriveJson.sorted(entries).map { it.name },
        )
    }

    @Test
    fun truncated_cutsAtTheSeamsCeilingRatherThanFailing() {
        val many = (1..CloudContract.MAX_LIST_ENTRIES + 25).map { entry("id$it", "f$it.soil") }
        assertEquals(CloudContract.MAX_LIST_ENTRIES, DriveJson.truncated(many).size)
    }

    @Test
    fun truncated_leavesAShortListAlone() {
        val few = (1..3).map { entry("id$it", "f$it.soil") }
        assertEquals(3, DriveJson.truncated(few).size)
    }

    @Test
    fun folderBody_namesTheParentAndTheFolderMime() {
        val body = DriveJson.folderBody("Exports", "PARENT")
        assertTrue(body.contains("\"name\":\"Exports\""))
        assertTrue(body.contains("\"mimeType\":\"${DriveRest.FOLDER_MIME}\""))
        assertTrue(body.contains("\"parents\":[\"PARENT\"]"))
    }

    @Test
    fun uploadMetaBody_omitsParentsOnAReplace() {
        val create = DriveJson.uploadMetaBody("a.soil", "PARENT")
        assertTrue(create.contains("\"parents\":[\"PARENT\"]"))
        val replace = DriveJson.uploadMetaBody("a.soil", null)
        assertTrue(replace.contains("\"name\":\"a.soil\""))
        assertTrue(!replace.contains("parents"))
    }

    private fun entry(id: String, name: String, folder: Boolean = false): CloudEntry =
        CloudEntry(id, name, folder, 0L, 0L)
}
