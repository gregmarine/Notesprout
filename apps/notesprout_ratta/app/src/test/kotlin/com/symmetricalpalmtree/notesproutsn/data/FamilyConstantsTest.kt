package com.symmetricalpalmtree.notesproutsn.data

import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.data.index.IndexDatabase
import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Test

/** The family-shared identifiers — each value is Paper's, verbatim; a drift here breaks the
 *  format-compat contract even with matching schemas. */
class FamilyConstantsTest {

    @Test
    fun indexConstants() {
        assertEquals(1, IndexDatabase.VERSION)
        assertEquals("00000000-0000-0000-0000-70696e6e6564", ListIds.PINNED_LIST_ID)
        assertEquals("folder", ObjectType.FOLDER)
        assertEquals("notebook", ObjectType.NOTEBOOK)
        assertEquals("list", ObjectType.LIST)
        assertEquals("list_item", ObjectType.LIST_ITEM)
        assertEquals(1, NotebookFlags.ENCRYPTED)
        assertEquals(2, NotebookFlags.EXCLUDE_FROM_BACKUP)
        assertEquals(4, NotebookFlags.TEXT_DOCUMENT)
        assertEquals("__notesprout_index__", KeyMaterial.INDEX_FILE_ID)
    }

    @Test
    fun soilConstants() {
        assertEquals(1, SoilSchema.SOIL_VERSION)
        assertEquals("notebook", SoilSchema.TABLE)
        assertEquals("notebook_meta", SoilSchema.META_TABLE)
        assertEquals("notebook", SoilSchema.TYPE_NOTEBOOK)
        assertEquals("page", SoilSchema.TYPE_PAGE)
        assertEquals("template", SoilSchema.TYPE_TEMPLATE)
        assertEquals("stroke", SoilSchema.TYPE_STROKE)
        assertEquals("heading", SoilSchema.TYPE_HEADING)
        assertEquals("link", SoilSchema.TYPE_LINK)
        // Arc 19: og's row type, verbatim — a `.soil` written here must read as a document there.
        assertEquals("document", SoilSchema.TYPE_DOCUMENT)
        assertEquals("", SoilSchema.ROOT_PARENT)
        assertEquals("BLANK", SoilSchema.TEMPLATE_BLANK)
        assertEquals("GLOBAL", KEY_SCOPE_GLOBAL)
    }

    @Test
    fun metaDdl_isTheFamilyStatement() {
        assertEquals(
            "CREATE TABLE IF NOT EXISTS notebook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)",
            SoilSchema.CREATE_META,
        )
    }
}
