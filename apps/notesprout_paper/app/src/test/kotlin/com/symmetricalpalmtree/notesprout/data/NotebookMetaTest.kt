package com.symmetricalpalmtree.notesprout.data

import com.symmetricalpalmtree.notesprout.data.soil.FolderRef
import com.symmetricalpalmtree.notesprout.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesprout.data.soil.NotebookMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookMetaTest {
    @Test
    fun roundTrips_and_staysInFamily() {
        val m = NotebookMeta(
            notebookId = "id", name = "n", createdAt = 1, updatedAt = 2,
            folderPath = listOf(FolderRef("f", "Folder", null)), appVersionCode = 1,
        )
        val json = m.toJson()
        assertTrue(json.contains("\"formatVersion\":1"))
        assertTrue(json.contains("\"encrypted\":true"))
        assertTrue(json.contains("\"keyScope\":\"$KEY_SCOPE_GLOBAL\""))
        assertTrue(json.contains("\"textDocument\":false"))
        assertEquals(m, NotebookMeta.fromJson(json))
    }

    @Test
    fun ignoresUnknownKeys() {
        val m = NotebookMeta.fromJson("""{"notebookId":"a","name":"b","createdAt":1,"updatedAt":2,"future":{"x":1}}""")
        assertEquals("a", m.notebookId)
        assertTrue(m.encrypted)
    }
}
