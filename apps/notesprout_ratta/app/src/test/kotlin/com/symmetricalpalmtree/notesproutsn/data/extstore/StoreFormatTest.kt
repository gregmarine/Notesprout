package com.symmetricalpalmtree.notesproutsn.data.extstore

import org.junit.Assert.assertEquals
import org.junit.Test

/** The store file's `user_version` ladder as a decision table (arc 22 / X1). */
class StoreFormatTest {

    @Test
    fun theLadder() {
        assertEquals(2, StoreFormat.VERSION)
        assertEquals(1, StoreFormat.LEGACY_KV_VERSION)
        assertEquals(StoreFormat.Decision.FRESH, StoreFormat.decide(0, hasLegacyTables = false))
        assertEquals(StoreFormat.Decision.WIPE, StoreFormat.decide(0, hasLegacyTables = true))
        assertEquals(StoreFormat.Decision.WIPE, StoreFormat.decide(1, hasLegacyTables = true))
        assertEquals(StoreFormat.Decision.WIPE, StoreFormat.decide(1, hasLegacyTables = false))
        assertEquals(StoreFormat.Decision.OPEN, StoreFormat.decide(2, hasLegacyTables = false))
        assertEquals(StoreFormat.Decision.WIPE, StoreFormat.decide(2, hasLegacyTables = true))
        assertEquals(StoreFormat.Decision.REFUSE, StoreFormat.decide(3, hasLegacyTables = false))
        assertEquals(StoreFormat.Decision.REFUSE, StoreFormat.decide(99, hasLegacyTables = true))
    }

    @Test
    fun theHostTable_isInTheReservedSpace() {
        assertEquals("host_schema", StoreFormat.HOST_SCHEMA_TABLE)
        assertEquals(listOf("kv", "room_master_table"), StoreFormat.LEGACY_TABLES)
        // The gate's own two statements name it — and an extension's statement naming it is refused
        // by StoreSql, which is the whole point of the prefix.
        assertEquals(true, ExtensionStoreGate.READ_VERSION.contains(StoreFormat.HOST_SCHEMA_TABLE))
        assertEquals(true, ExtensionStoreGate.WRITE_VERSION.contains(StoreFormat.HOST_SCHEMA_TABLE))
    }
}
