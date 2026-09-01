package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.Statement

/**
 * Every statement the tag manager sends, as a pure builder (arc 22 / X3) — SQL text and bound
 * arguments and nothing else, so the shapes are JVM-testable without a store.
 *
 * Two of them do more than they look like, and both are there because **the transaction is the
 * lock**: there are two writers (the screen on IO, `assign` on a Binder thread) in one process and,
 * across a restart, in two — and arc 21's process-local monitor could never have covered that.
 *
 *  - [insertTag] carries its own cap: `… SELECT ?, ?, ?, ? WHERE (SELECT COUNT(*) FROM tag) < ?`.
 *    Counting first and inserting second would be two statements with a race between them; this is
 *    one, so the count is taken inside the same transaction as the insert it gates. It also carries
 *    the **assignment** cap, so a refused attachment never leaves behind the tag it was creating.
 *  - [insertAssignment] resolves the tag id **by identity, inside the statement**
 *    (`SELECT id, ?, ?, ? FROM tag WHERE identityKey = ?`) rather than taking an id the caller read
 *    a moment ago. If a concurrent writer created the same tag first, this one's `INSERT OR IGNORE`
 *    into `tag` does nothing and the assignment still lands on **the row that exists** — where an id
 *    read beforehand would have pointed at a tag that was never inserted.
 *
 * A notebook tag's `pageId` argument is `""` everywhere: these builders take the **stored** form,
 * and the `String?` ⇄ `""` mapping happens once, in [TagStore]. `now` is passed in rather than read
 * here so a test can pin it.
 */
object TagSql {

    // ── Reads ──────

    /** One page of the browse order. Stable — `(identityKey, display)` is unique by construction —
     *  which is what makes `LIMIT`/`OFFSET` paging safe rather than merely plausible. */
    fun selectTags(limit: Int, offset: Int): Statement =
        Statement(
            "SELECT id, display FROM tag ORDER BY identityKey, display LIMIT ? OFFSET ?",
            limit.toLong(), offset.toLong(),
        )

    /**
     * One read answering both halves of "does this tag exist, and is it already on this target".
     * `assign` asks it before writing and again after, and the second answer is what turns a cap
     * refusal — which `INSERT OR IGNORE` reports as silence — back into a typed failure.
     */
    fun selectTagByIdentity(identityKey: String, notebookId: String, pageId: String): Statement =
        Statement(
            "SELECT t.id, t.display, EXISTS(SELECT 1 FROM assignment a WHERE a.tagId = t.id AND a.notebookId = ? AND a.pageId = ?) AS attached FROM tag t WHERE t.identityKey = ?",
            notebookId, pageId, identityKey,
        )

    /** The blast radius of deleting a tag, by kind. `SUM` over no rows is NULL — read it as 0. */
    fun selectUsage(tagId: String): Statement =
        Statement(
            "SELECT SUM(pageId = '') AS notebooks, SUM(pageId <> '') AS pages FROM assignment WHERE tagId = ?",
            tagId,
        )

    /** The screen's read: every assignment in one notebook — its own and every one of its pages'. */
    fun selectAssignmentsOfNotebook(notebookId: String): Statement =
        Statement(
            "SELECT tagId, notebookId, pageId FROM assignment WHERE notebookId = ? ORDER BY tagId, pageId",
            notebookId,
        )

    /** The search merge's read: one page of the assignments of the tags that matched the query.
     *  One `?` per id — the caller guarantees `ExtensionContract.ASSIGNMENT_QUERY_TAGS` at most. */
    fun selectAssignmentsOf(tagIds: List<String>, limit: Int, offset: Int): Statement {
        require(tagIds.isNotEmpty()) { "no tag ids" }
        val marks = tagIds.joinToString(", ") { "?" }
        return Statement(
            "SELECT tagId, notebookId, pageId FROM assignment WHERE tagId IN ($marks) " +
                "ORDER BY tagId, notebookId, pageId LIMIT ? OFFSET ?",
            tagIds.map { Cell.Text(it) } + listOf(Cell.Integer(limit.toLong()), Cell.Integer(offset.toLong())),
        )
    }

    // ── Writes ──────

    /**
     * Create the tag unless the identity is taken — and unless [maxTags] is already reached, **or
     * [maxAssignments] is**. The second guard is there because a new tag is only ever created to be
     * attached in the same batch: if the assignment cap is going to refuse that attachment, creating
     * the tag first would leave an orphan row behind a refusal that promises "nothing was written".
     */
    fun insertTag(id: String, display: String, identityKey: String, now: Long, maxTags: Int, maxAssignments: Int): Statement =
        Statement(
            "INSERT OR IGNORE INTO tag (id, display, identityKey, createdAt) SELECT ?, ?, ?, ? WHERE (SELECT COUNT(*) FROM tag) < ? AND (SELECT COUNT(*) FROM assignment) < ?",
            id, display, identityKey, now, maxTags.toLong(), maxAssignments.toLong(),
        )

    /** Attach the tag with [identityKey] to the target — unless it is already there, unless no such
     *  tag exists (the row the `SELECT` would have supplied is simply absent), or unless
     *  [maxAssignments] is reached. */
    fun insertAssignment(
        identityKey: String,
        notebookId: String,
        pageId: String,
        now: Long,
        maxAssignments: Int,
    ): Statement =
        Statement(
            "INSERT OR IGNORE INTO assignment (tagId, notebookId, pageId, createdAt) SELECT id, ?, ?, ? FROM tag WHERE identityKey = ? AND (SELECT COUNT(*) FROM assignment) < ?",
            notebookId, pageId, now, identityKey, maxAssignments.toLong(),
        )

    /** Detach one tag from one target. **The tag itself stays** — the wizard's lifecycle call. */
    fun deleteAssignment(tagId: String, notebookId: String, pageId: String): Statement =
        Statement(
            "DELETE FROM assignment WHERE tagId = ? AND notebookId = ? AND pageId = ?",
            tagId, notebookId, pageId,
        )

    /** Delete a tag **and every assignment of it** — the declared cascade does the second half. */
    fun deleteTag(id: String): Statement =
        Statement("DELETE FROM tag WHERE id = ?", id)
}
