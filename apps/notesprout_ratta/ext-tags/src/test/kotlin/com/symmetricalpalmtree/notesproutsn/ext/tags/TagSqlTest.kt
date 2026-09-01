package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.StoreSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every statement the tag manager sends, pinned as exact text and arguments (arc 22 / X3) — the
 * host's validator is run over each one, so a shape it would refuse fails here rather than on the
 * device.
 */
class TagSqlTest {

    private val t1 = "11111111-1111-4111-8111-111111111111"
    private val t2 = "22222222-2222-4222-8222-222222222222"
    private val n1 = "33333333-3333-4333-8333-333333333333"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"

    // ── Reads ────────────────────────────────────────────────────────────────

    @Test
    fun selectTags_pagesTheBrowseOrder() {
        val s = TagSql.selectTags(500, 1_000)
        assertEquals(
            "SELECT id, display FROM tag ORDER BY identityKey, display LIMIT ? OFFSET ?",
            s.sql,
        )
        assertEquals(listOf<Cell>(Cell.Integer(500), Cell.Integer(1_000)), s.args)
        StoreSql.checkQuery(s.sql)
    }

    /** One read answers both halves of "does it exist, and is it already on this target". */
    @Test
    fun selectTagByIdentity_asksExistenceAndAttachmentTogether() {
        val s = TagSql.selectTagByIdentity("reading list", n1, p1)
        assertEquals(
            "SELECT t.id, t.display, EXISTS(SELECT 1 FROM assignment a WHERE a.tagId = t.id AND " +
                "a.notebookId = ? AND a.pageId = ?) AS attached FROM tag t WHERE t.identityKey = ?",
            s.sql,
        )
        // Bind order is the SQL's, not the signature's: the EXISTS comes first.
        assertEquals(listOf<Cell>(Cell.Text(n1), Cell.Text(p1), Cell.Text("reading list")), s.args)
        StoreSql.checkQuery(s.sql)
    }

    @Test
    fun selectUsage_countsBothKindsInOneRow() {
        val s = TagSql.selectUsage(t1)
        assertEquals(
            "SELECT SUM(pageId = '') AS notebooks, SUM(pageId <> '') AS pages FROM assignment WHERE tagId = ?",
            s.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text(t1)), s.args)
        StoreSql.checkQuery(s.sql)
    }

    @Test
    fun selectAssignmentsOfNotebook_isTheScreensRead() {
        val s = TagSql.selectAssignmentsOfNotebook(n1)
        assertEquals(
            "SELECT tagId, notebookId, pageId FROM assignment WHERE notebookId = ? ORDER BY tagId, pageId",
            s.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text(n1)), s.args)
        StoreSql.checkQuery(s.sql)
    }

    /** One `?` per id, then the two paging binds — the arity is the whole reason the host chunks. */
    @Test
    fun selectAssignmentsOf_buildsOneMarkPerId() {
        val s = TagSql.selectAssignmentsOf(listOf(t1, t2), 1_000, 2_000)
        assertEquals(
            "SELECT tagId, notebookId, pageId FROM assignment WHERE tagId IN (?, ?) " +
                "ORDER BY tagId, notebookId, pageId LIMIT ? OFFSET ?",
            s.sql,
        )
        assertEquals(
            listOf<Cell>(Cell.Text(t1), Cell.Text(t2), Cell.Integer(1_000), Cell.Integer(2_000)),
            s.args,
        )
        StoreSql.checkQuery(s.sql)
    }

    /** A full chunk must fit SQLite's bind cap and the seam's SQL length cap, both with room over. */
    @Test
    fun selectAssignmentsOf_atTheChunkCapFitsEveryBound() {
        val ids = List(ExtensionContract.ASSIGNMENT_QUERY_TAGS) { t1 }
        val s = TagSql.selectAssignmentsOf(ids, 1_000, 0)
        assertEquals(ExtensionContract.ASSIGNMENT_QUERY_TAGS + 2, s.args.size)
        assertTrue(s.args.size <= ExtensionContract.STORE_MAX_ARGS)
        assertTrue(s.sql.length <= ExtensionContract.STORE_MAX_SQL_CHARS)
        StoreSql.checkQuery(s.sql)
    }

    @Test
    fun selectAssignmentsOf_refusesAnEmptyList() {
        try {
            TagSql.selectAssignmentsOf(emptyList(), 10, 0)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /** The cap is INSIDE the insert: counting first and inserting second would be a race. */
    @Test
    fun insertTag_carriesItsOwnCap() {
        val s = TagSql.insertTag(t1, "Reading List", "reading list", 99L, 5_000, 50_000)
        assertEquals(
            "INSERT OR IGNORE INTO tag (id, display, identityKey, createdAt) SELECT ?, ?, ?, ? " +
                "WHERE (SELECT COUNT(*) FROM tag) < ? AND (SELECT COUNT(*) FROM assignment) < ?",
            s.sql,
        )
        assertEquals(
            listOf<Cell>(
                Cell.Text(t1), Cell.Text("Reading List"), Cell.Text("reading list"),
                Cell.Integer(99), Cell.Integer(5_000), Cell.Integer(50_000),
            ),
            s.args,
        )
        StoreSql.checkExec(s.sql)
    }

    /** The tag id is resolved BY IDENTITY inside the statement, so a concurrent creator of the same
     *  tag cannot leave this assignment pointing at a row that was never inserted. */
    @Test
    fun insertAssignment_resolvesTheTagIdItself() {
        val s = TagSql.insertAssignment("reading list", n1, "", 99L, 50_000)
        assertEquals(
            "INSERT OR IGNORE INTO assignment (tagId, notebookId, pageId, createdAt) " +
                "SELECT id, ?, ?, ? FROM tag WHERE identityKey = ? AND (SELECT COUNT(*) FROM assignment) < ?",
            s.sql,
        )
        assertEquals(
            listOf<Cell>(
                Cell.Text(n1), Cell.Text(""), Cell.Integer(99),
                Cell.Text("reading list"), Cell.Integer(50_000),
            ),
            s.args,
        )
        // A notebook tag's page is the empty string, never NULL — in SQL NULL is not equal to NULL,
        // and the row's primary key is (tagId, notebookId, pageId).
        assertEquals(Cell.Text(""), s.args[1])
        StoreSql.checkExec(s.sql)
    }

    @Test
    fun deleteAssignment_namesTheWholeTarget() {
        val s = TagSql.deleteAssignment(t1, n1, p1)
        assertEquals("DELETE FROM assignment WHERE tagId = ? AND notebookId = ? AND pageId = ?", s.sql)
        assertEquals(listOf<Cell>(Cell.Text(t1), Cell.Text(n1), Cell.Text(p1)), s.args)
        StoreSql.checkExec(s.sql)
    }

    /** One statement — the declared `ON DELETE CASCADE` takes every assignment with the row. */
    @Test
    fun deleteTag_isOneStatementAndTheCascadeDoesTheRest() {
        val s = TagSql.deleteTag(t1)
        assertEquals("DELETE FROM tag WHERE id = ?", s.sql)
        assertEquals(listOf<Cell>(Cell.Text(t1)), s.args)
        assertTrue("the cascade is the schema's, not a second statement", "assignment" !in s.sql)
        StoreSql.checkExec(s.sql)
    }

    // ── The schema ───────────────────────────────────────────────────────────

    /** Constructing [TagSchema.V1] IS the DDL validator run; this pins what it declares. */
    @Test
    fun theSchemaIsOneVersionOfThreeStatements() {
        assertEquals(1, TagSchema.V1.version)
        val step = TagSchema.V1.steps.single()
        assertEquals(3, step.size)
        assertTrue(step[0].startsWith("CREATE TABLE tag"))
        assertTrue(step[1].startsWith("CREATE TABLE assignment"))
        assertEquals("CREATE INDEX assignment_target ON assignment(notebookId, pageId);", step[2])
        // The two rules the rest of the file rests on.
        assertTrue("the cascade", "REFERENCES tag(id) ON DELETE CASCADE" in step[1])
        assertTrue("the target key", "PRIMARY KEY (tagId, notebookId, pageId)" in step[1])
        assertTrue("the identity index", "identityKey TEXT NOT NULL UNIQUE" in step[0])
    }
}
