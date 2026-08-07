package com.notesprout.android.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the three-site rule in [SoilSchema]: a `.soil` schema change has to land in the fresh-file
 * `CREATE TABLE` **and** in the migration list, or a new notebook and an upgraded one disagree and
 * Room's on-open validation crashes on one of them.
 */
class SoilSchemaTest {

    private fun declares(column: String): Boolean =
        Regex("""(^|[\s"])${Regex.escape(column)}([\s"])""")
            .containsMatchIn(SoilSchema.CREATE_NOTEBOOK_TABLE)

    @Test
    fun everyMigratedColumnExistsInTheFreshTable() {
        for ((name, _) in SoilSchema.ADDED_COLUMNS_V4 + SoilSchema.ADDED_COLUMNS_V5) {
            assertTrue("CREATE_NOTEBOOK_TABLE is missing migrated column `$name`", declares(name))
        }
    }

    @Test
    fun migrationListsDoNotOverlap() {
        val v4 = SoilSchema.ADDED_COLUMNS_V4.map { it.first }.toSet()
        val v5 = SoilSchema.ADDED_COLUMNS_V5.map { it.first }.toSet()
        // A column in both lists would be added twice on a v3 file — `ALTER TABLE` fails the second
        // time and the whole migration aborts.
        assertTrue("column listed in both v4 and v5: ${v4 intersect v5}", (v4 intersect v5).isEmpty())
    }
}
