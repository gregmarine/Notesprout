package com.symmetricalpalmtree.notesproutsn.data

import com.symmetricalpalmtree.notesproutsn.data.soil.FolderRef
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookMetaTest {

    /** Exact-output fixtures produced by Paper's `NotebookMeta.toJson()` (byte-compat proof —
     *  identical declaration order + Json config must yield the identical string). */
    @Test
    fun toJson_matchesPaperFixtures_exactly() {
        val full = NotebookMeta(
            notebookId = "3d6b8f2a-1111-2222-3333-444455556666",
            name = "Compat Notebook",
            createdAt = 1755600000000L,
            updatedAt = 1755600000001L,
            folderPath = listOf(FolderRef("f1", "Folder One", null), FolderRef("f2", "Two", "f1")),
            appVersionCode = 12,
        )
        assertEquals(
            """{"formatVersion":1,"notebookId":"3d6b8f2a-1111-2222-3333-444455556666","name":"Compat Notebook","createdAt":1755600000000,"updatedAt":1755600000001,"encrypted":true,"keyScope":"GLOBAL","folderPath":[{"id":"f1","name":"Folder One"},{"id":"f2","name":"Two","parentId":"f1"}],"appVersionCode":12,"textDocument":false}""",
            full.toJson(),
        )
        val minimal = NotebookMeta(notebookId = "nb", name = "N", createdAt = 1L, updatedAt = 2L)
        assertEquals(
            """{"formatVersion":1,"notebookId":"nb","name":"N","createdAt":1,"updatedAt":2,"encrypted":true,"keyScope":"GLOBAL","folderPath":[],"textDocument":false}""",
            minimal.toJson(),
        )
    }

    @Test
    fun decodesPaperFixture() {
        val m = NotebookMeta.fromJson(
            """{"formatVersion":1,"notebookId":"3d6b8f2a-1111-2222-3333-444455556666","name":"Compat Notebook","createdAt":1755600000000,"updatedAt":1755600000001,"encrypted":true,"keyScope":"GLOBAL","folderPath":[{"id":"f1","name":"Folder One"},{"id":"f2","name":"Two","parentId":"f1"}],"appVersionCode":12,"textDocument":false}"""
        )
        assertEquals("Compat Notebook", m.name)
        assertEquals(KEY_SCOPE_GLOBAL, m.keyScope)
        assertEquals(2, m.folderPath.size)
        assertEquals(null, m.folderPath[0].parentId)
        assertEquals("f1", m.folderPath[1].parentId)
        assertTrue(m.encrypted)
    }

    @Test
    fun roundTrips() {
        val m = NotebookMeta(
            notebookId = "id", name = "n", createdAt = 1, updatedAt = 2,
            folderPath = listOf(FolderRef("f", "Folder", null)), appVersionCode = 1,
        )
        assertEquals(m, NotebookMeta.fromJson(m.toJson()))
    }

    @Test
    fun ignoresUnknownKeys() {
        val m = NotebookMeta.fromJson("""{"notebookId":"a","name":"b","createdAt":1,"updatedAt":2,"future":{"x":1}}""")
        assertEquals("a", m.notebookId)
        assertTrue(m.encrypted)
    }
}
