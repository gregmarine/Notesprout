package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A schema fails at its declaration, on the extension's side (arc 22 / X1). */
class StoreSchemaTest {

    private val v1 = listOf(
        "CREATE TABLE page (id TEXT PRIMARY KEY, position INTEGER NOT NULL)",
        "CREATE INDEX page_position ON page(position)",
    )
    private val v2 = listOf("ALTER TABLE page ADD COLUMN width REAL NOT NULL DEFAULT 0")

    @Test
    fun aGoodSchemaConstructs() {
        val s = StoreSchema(2, listOf(v1, v2))
        assertEquals(2, s.version)
        assertEquals(2, s.steps.size)
        StoreSchema.requireValid(1, listOf(v1))
    }

    @Test
    fun versionMustMatchStepCount() {
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(1, listOf(v1, v2)) }
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(2, listOf(v1)) }
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(0, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(-1, emptyList()) }
    }

    @Test
    fun badDdl_failsAtConstruction_namingTheStep() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            StoreSchema(2, listOf(v1, listOf("PRAGMA user_version = 3")))
        }
        assertTrue(e.message!!, e.message!!.startsWith("step 2 statement 1:"))
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(1, listOf(listOf("CREATE TABLE host_x (id)"))) }
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(1, listOf(listOf("INSERT INTO page VALUES (1)"))) }
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(1, listOf(listOf("CREATE VIEW v AS SELECT 1"))) }
    }

    @Test
    fun stepCaps() {
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(1, listOf(emptyList())) }
        val tooManyStatements = List(ExtensionContract.STORE_MAX_STEP_STATEMENTS + 1) { "CREATE INDEX i$it ON page(position)" }
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(1, listOf(tooManyStatements)) }
        val maxStatements = List(ExtensionContract.STORE_MAX_STEP_STATEMENTS) { "CREATE INDEX i$it ON page(position)" }
        StoreSchema(1, listOf(maxStatements))
        val tooManySteps = List(ExtensionContract.STORE_MAX_SCHEMA_STEPS + 1) { listOf("CREATE INDEX i$it ON page(position)") }
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(tooManySteps.size, tooManySteps) }
    }

    @Test
    fun tableCap_countsCreateTableOverEveryStep() {
        val steps = List(ExtensionContract.STORE_MAX_TABLES) { listOf("CREATE TABLE t$it (id)") }
        StoreSchema(steps.size, steps)
        val oneMore = steps + listOf(listOf("CREATE TABLE t_more (id)"))
        assertThrows(IllegalArgumentException::class.java) { StoreSchema(oneMore.size, oneMore) }
        // Indexes do not count.
        val withIndexes = steps + listOf(listOf("CREATE INDEX i ON t0(id)"))
        StoreSchema(withIndexes.size, withIndexes)
    }
}
